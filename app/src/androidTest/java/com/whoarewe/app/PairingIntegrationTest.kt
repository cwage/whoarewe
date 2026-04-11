package com.whoarewe.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whoarewe.app.crypto.EcdhExchange
import com.whoarewe.app.crypto.HexCodec
import com.whoarewe.app.crypto.QrCodeUtils
import com.whoarewe.app.crypto.TotpGenerator
import com.whoarewe.app.crypto.TotpSecretCodec
import com.whoarewe.app.data.AppDatabase
import com.whoarewe.app.data.Identity
import com.whoarewe.app.data.NameMatcher
import com.whoarewe.app.data.TrustedContact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * End-to-end integration test for the full pairing flow.
 *
 * Exercises the real code path that runs when two devices pair:
 *   keygen → QR encode → QR decode → ECDH → Room insert → TOTP generation
 *
 * Verifies the core property: two parties who exchange public keys via QR
 * will derive identical shared secrets and matching TOTP codes.
 */
@RunWith(AndroidJUnit4::class)
class PairingIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: com.whoarewe.app.data.ContactDao

    // Per-test DEK — a real app would read this out of the biometric-
    // wrapped identity blob at unlock time. For DAO-level integration
    // tests we just hold it in-memory alongside the in-memory DB.
    private val testDek = ByteArray(32) { it.toByte() }

    /**
     * Build a [TrustedContact] carrying an encrypted version of
     * [plaintextSecret]. The caller keeps the plaintext so assertions
     * can re-encrypt or re-decrypt and compare against what the DAO
     * round-tripped. This mirrors the shape of what
     * `WhoAreWeViewModel.persistPairedContact` writes at pair time.
     */
    private fun trustedContact(
        displayName: String,
        publicKey: String,
        plaintextSecret: ByteArray,
        notes: String? = null
    ): TrustedContact {
        val encrypted = TotpSecretCodec.encrypt(plaintextSecret, testDek)
        return TrustedContact(
            displayName = displayName,
            publicKey = publicKey,
            encryptedTotpSecret = encrypted.ciphertext,
            totpSecretIv = encrypted.iv,
            notes = notes
        )
    }

    /**
     * Decrypt the ciphertext + IV stored on a row back to plaintext.
     * Used by assertions that want to check "what would the tick loop
     * feed into TotpGenerator for this row".
     */
    private fun decryptStoredSecret(contact: TrustedContact): ByteArray {
        return TotpSecretCodec.decrypt(
            TotpSecretCodec.EncryptedSecret(
                ciphertext = contact.encryptedTotpSecret,
                iv = contact.totpSecretIv
            ),
            testDek
        )
    }

    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val privateKey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
        val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded
        return privateKey to publicKey
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.contactDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── Full pairing: Alice scans Bob's QR, both derive matching codes ──

    @Test
    fun fullPairingFlow_producesMatchingTotpCodes() = runTest {
        // Device A: Alice
        val (alicePrivate, alicePublic) = generateEd25519KeyPair()
        dao.saveIdentity(Identity(displayName = "Alice", publicKey = alicePublic.toHex()))

        // Device B: Bob creates his QR code
        val (bobPrivate, bobPublic) = generateEd25519KeyPair()
        val bobQr = QrCodeUtils.encode("Bob", bobPublic)

        // Alice scans Bob's QR
        val decoded = QrCodeUtils.decode(bobQr)
        assertNotNull("QR decode should succeed", decoded)
        assertEquals("Bob", decoded!!.displayName)
        assertArrayEquals(bobPublic, decoded.publicKey)

        // Alice derives shared secret and stores contact
        val aliceSecret = EcdhExchange.deriveSharedSecret(alicePrivate, decoded.publicKey)
        dao.insertContact(
            trustedContact(
                displayName = decoded.displayName,
                publicKey = decoded.publicKey.toHex(),
                plaintextSecret = aliceSecret
            )
        )

        // Verify contact stored correctly
        val contacts = dao.getAllContacts().first()
        assertEquals(1, contacts.size)
        assertEquals("Bob", contacts[0].displayName)

        // Bob derives his side of the shared secret
        val bobSecret = EcdhExchange.deriveSharedSecret(bobPrivate, alicePublic)

        // THE KEY ASSERTION: shared secrets must be identical
        assertArrayEquals("ECDH shared secrets must match", aliceSecret, bobSecret)

        // Verify TOTP codes match at multiple time windows
        val periodMillis = TotpGenerator.PERIOD_SECONDS * 1000
        val times = listOf(
            System.currentTimeMillis(),
            System.currentTimeMillis() + periodMillis,
            System.currentTimeMillis() + (2 * periodMillis),
            1_000_000_000L * 1000
        )
        for (time in times) {
            val aliceCode = TotpGenerator.generateCode(aliceSecret, time)
            val bobCode = TotpGenerator.generateCode(bobSecret, time)
            assertEquals("TOTP codes must match at time=$time", aliceCode, bobCode)
        }
    }

    // ── Bidirectional: both sides scan, both store, codes match ──

    @Test
    fun bidirectionalPairing_storedSecretsProduceMatchingCodes() = runTest {
        val (alicePrivate, alicePublic) = generateEd25519KeyPair()
        val (bobPrivate, bobPublic) = generateEd25519KeyPair()

        dao.saveIdentity(Identity(displayName = "Alice", publicKey = alicePublic.toHex()))

        val aliceQr = QrCodeUtils.encode("Alice", alicePublic)
        val bobQr = QrCodeUtils.encode("Bob", bobPublic)

        // Alice scans Bob's QR → derives secret → stores contact
        val bobPayload = QrCodeUtils.decode(bobQr)!!
        val secretAliceSide = EcdhExchange.deriveSharedSecret(alicePrivate, bobPayload.publicKey)
        dao.insertContact(
            trustedContact(
                displayName = bobPayload.displayName,
                publicKey = bobPayload.publicKey.toHex(),
                plaintextSecret = secretAliceSide
            )
        )

        // Bob scans Alice's QR → derives secret
        val alicePayload = QrCodeUtils.decode(aliceQr)!!
        val secretBobSide = EcdhExchange.deriveSharedSecret(bobPrivate, alicePayload.publicKey)

        // Secrets match
        assertArrayEquals(secretAliceSide, secretBobSide)

        // What's stored in the DB also matches — decrypt the ciphertext
        // column the same way the tick loop does and compare.
        val storedContact = dao.getAllContacts().first()[0]
        val storedSecret = decryptStoredSecret(storedContact)
        val now = System.currentTimeMillis()
        assertEquals(
            TotpGenerator.generateCode(secretBobSide, now),
            TotpGenerator.generateCode(storedSecret, now)
        )
    }

    // ── Multiple contacts: each pairing is independent ──

    @Test
    fun multipleContacts_eachHasIndependentSecret() = runTest {
        val (alicePrivate, alicePublic) = generateEd25519KeyPair()
        dao.saveIdentity(Identity(displayName = "Alice", publicKey = alicePublic.toHex()))

        val (bobPrivate, bobPublic) = generateEd25519KeyPair()
        val (carolPrivate, carolPublic) = generateEd25519KeyPair()

        // Pair with Bob
        val bobPayload = QrCodeUtils.decode(QrCodeUtils.encode("Bob", bobPublic))!!
        val secretBob = EcdhExchange.deriveSharedSecret(alicePrivate, bobPayload.publicKey)
        dao.insertContact(
            trustedContact(
                displayName = "Bob",
                publicKey = bobPayload.publicKey.toHex(),
                plaintextSecret = secretBob
            )
        )

        // Pair with Carol
        val carolPayload = QrCodeUtils.decode(QrCodeUtils.encode("Carol", carolPublic))!!
        val secretCarol = EcdhExchange.deriveSharedSecret(alicePrivate, carolPayload.publicKey)
        dao.insertContact(
            trustedContact(
                displayName = "Carol",
                publicKey = carolPayload.publicKey.toHex(),
                plaintextSecret = secretCarol
            )
        )

        // Two contacts stored
        val contacts = dao.getAllContacts().first()
        assertEquals(2, contacts.size)

        // Secrets are different
        assertNotEquals(
            "Different contacts must have different shared secrets",
            secretBob.toList(),
            secretCarol.toList()
        )

        // Each contact's code matches what their device would produce
        val now = System.currentTimeMillis()
        val bobExpected = TotpGenerator.generateCode(
            EcdhExchange.deriveSharedSecret(bobPrivate, alicePublic), now
        )
        val carolExpected = TotpGenerator.generateCode(
            EcdhExchange.deriveSharedSecret(carolPrivate, alicePublic), now
        )

        val bobStored = contacts.first { it.displayName == "Bob" }
        val carolStored = contacts.first { it.displayName == "Carol" }

        assertEquals(bobExpected, TotpGenerator.generateCode(decryptStoredSecret(bobStored), now))
        assertEquals(carolExpected, TotpGenerator.generateCode(decryptStoredSecret(carolStored), now))
    }

    // ── Duplicate detection: same public key is caught ──

    @Test
    fun duplicateContact_detectedByPublicKey() = runTest {
        val (_, bobPublic) = generateEd25519KeyPair()
        val pubKeyHex = bobPublic.toHex()

        dao.insertContact(
            trustedContact(
                displayName = "Bob",
                publicKey = pubKeyHex,
                plaintextSecret = byteArrayOf(0xaa.toByte(), 0xbb.toByte())
            )
        )

        val existing = dao.getContactByPublicKey(pubKeyHex)
        assertNotNull("Should detect existing contact by public key", existing)
        assertEquals("Bob", existing!!.displayName)
    }

    // ── Name collision with a different key (cwage/whoarewe#33) ──
    //
    // Covers the full attacker shape end-to-end: Alice is already paired;
    // an attacker generates a *fresh* keypair and hands over a QR whose
    // display name is "Alice". The legitimate dedup check
    // (`getContactByPublicKey`) misses — by design, since it keys on pubkey
    // — and the collision is only caught by iterating stored contacts and
    // comparing normalized names. The Replace path must end with exactly
    // one row, atomically swapped, carrying the attacker's key; the
    // Add-as-second path must end with two rows sharing a display name.

    @Test
    fun nameCollision_dedupeByPublicKeyMissesTheAttack() = runTest {
        // Victim already has an Alice row with key K1.
        val (_, alicePublic) = generateEd25519KeyPair()
        dao.insertContact(
            trustedContact(
                displayName = "Alice",
                publicKey = alicePublic.toHex(),
                plaintextSecret = "legitsecret".toByteArray()
            )
        )

        // Attacker generates their own keypair and crafts an "Alice" QR.
        val (_, attackerPublic) = generateEd25519KeyPair()
        val attackerQr = QrCodeUtils.encode("Alice", attackerPublic)
        val decoded = QrCodeUtils.decode(attackerQr)!!

        // The existing dedup path does not catch this — the attacker's
        // public key is novel. This is the gap that collision detection
        // has to close; pinning the behavior here makes it obvious why
        // a name-based check is necessary on top of the pubkey check.
        val byKey = dao.getContactByPublicKey(decoded.publicKey.toHex())
        assertNull("Pubkey dedup must miss — this is the whole bug", byKey)

        // The name-based scan (what WhoAreWeViewModel does) finds it.
        val byName = dao.getAllContacts().first().firstOrNull { row ->
            NameMatcher.matches(row.displayName, decoded.displayName)
        }
        assertNotNull("Name-based collision check must catch the attacker's QR", byName)
        assertEquals("Alice", byName!!.displayName)
        // assertNotEquals, not Kotlin/Java `assert(...)` — the latter is
        // a runtime-disabled no-op unless the instrumentation runner is
        // launched with `-ea`, which ours is not. See Copilot round 1
        // on PR #37.
        assertNotEquals(
            "Collided row must have a different key from the incoming payload",
            decoded.publicKey.toHex(),
            byName.publicKey
        )
    }

    @Test
    fun nameCollision_replacePath_atomicallySwapsAlice() = runTest {
        // Two contacts: Alice (the one we will replace) and Bob (who must
        // be untouched as a basic blast-radius check).
        val (_, oldAlicePublic) = generateEd25519KeyPair()
        val oldAliceId = dao.insertContact(
            trustedContact(
                displayName = "Alice",
                publicKey = oldAlicePublic.toHex(),
                plaintextSecret = "oldalicesecret".toByteArray()
            )
        )
        val (_, bobPublic) = generateEd25519KeyPair()
        dao.insertContact(
            trustedContact(
                displayName = "Bob",
                publicKey = bobPublic.toHex(),
                plaintextSecret = "bobsecret".toByteArray()
            )
        )

        // User picked "Replace existing Alice" on the collision dialog.
        val (victimPrivate, _) = generateEd25519KeyPair()
        val (_, newAlicePublic) = generateEd25519KeyPair()
        val newSecret = EcdhExchange.deriveSharedSecret(victimPrivate, newAlicePublic)
        dao.replaceContact(
            oldAliceId,
            trustedContact(
                displayName = "Alice",
                publicKey = newAlicePublic.toHex(),
                plaintextSecret = newSecret
            )
        )

        val all = dao.getAllContacts().first()
        assertEquals("Replace must leave exactly two rows (Alice + Bob)", 2, all.size)
        assertNull("Old Alice row must be gone", dao.getContactById(oldAliceId))

        val alice = all.first { it.displayName == "Alice" }
        assertEquals(
            "Alice row must now carry the freshly-paired public key",
            newAlicePublic.toHex(),
            alice.publicKey
        )
        assertArrayEquals(
            "Alice row must decrypt to the freshly-derived TOTP secret",
            newSecret,
            decryptStoredSecret(alice)
        )
        assertEquals(
            "Bob must be untouched by the replace",
            bobPublic.toHex(),
            all.first { it.displayName == "Bob" }.publicKey
        )
    }

    @Test
    fun nameCollision_replacePath_preservesExistingNotesAndDisplayNameCasing() = runTest {
        // Pin the "continuity of the user's view" contract for Replace
        // that `persistPairedContact` implements (cwage/whoarewe#33,
        // Copilot round 1). When the user says "this is still the same
        // person with a new key":
        //   - the label they're used to seeing ("Alice") must survive,
        //     even if the attacker's QR typed "alice" with a different
        //     casing — preserving existing.displayName is the only way
        //     to avoid letting a hostile QR silently reformat a trusted
        //     contact's label out from under the user,
        //   - and any annotation the user has ("my sister") must
        //     survive because it's user-owned metadata, not identity
        //     material.
        // Only the publicKey and totpSecret should actually change.
        //
        // This test mirrors the exact shape of the `existingRow`-aware
        // TrustedContact construction in `persistPairedContact`. If a
        // future refactor drops those preserved fields, this test fires
        // even though the VM-level glue is not directly invoked.
        val (_, oldAlicePublic) = generateEd25519KeyPair()
        val oldAliceId = dao.insertContact(
            trustedContact(
                displayName = "Alice",
                publicKey = oldAlicePublic.toHex(),
                plaintextSecret = "oldalicesecret".toByteArray(),
                notes = "my sister"
            )
        )

        // Incoming QR has the same name with *different* casing to make
        // the "did we preserve the existing casing?" assertion non-trivial.
        val (victimPrivate, _) = generateEd25519KeyPair()
        val (_, newAlicePublic) = generateEd25519KeyPair()
        val attackerPayload = QrCodeUtils.decode(
            QrCodeUtils.encode("alice", newAlicePublic)
        )!!
        val newSecret = EcdhExchange.deriveSharedSecret(victimPrivate, attackerPayload.publicKey)

        // Build the replacement the same way persistPairedContact does
        // when replaceId != null: load the old row, carry displayName
        // and notes from it, swap in the new key and derived secret.
        val existingRow = dao.getContactById(oldAliceId)!!
        dao.replaceContact(
            oldAliceId,
            trustedContact(
                displayName = existingRow.displayName,
                publicKey = attackerPayload.publicKey.toHex(),
                plaintextSecret = newSecret,
                notes = existingRow.notes
            )
        )

        val all = dao.getAllContacts().first()
        assertEquals(1, all.size)
        val replaced = all[0]
        assertEquals(
            "displayName must keep the existing casing, not adopt the scanned 'alice'",
            "Alice",
            replaced.displayName
        )
        assertEquals(
            "notes (user-owned annotation) must survive the identity swap",
            "my sister",
            replaced.notes
        )
        assertEquals(
            "publicKey must be the freshly-paired one",
            attackerPayload.publicKey.toHex(),
            replaced.publicKey
        )
        assertArrayEquals(
            "totpSecret must decrypt to the freshly-derived ECDH output",
            newSecret,
            decryptStoredSecret(replaced)
        )
    }

    @Test
    fun nameCollision_addAsSecondPath_leavesTwoAliceRows() = runTest {
        // User picked "Add as a second Alice" on the collision dialog.
        // The DB must end with two rows both named "Alice", each with
        // its own cryptographic identity — this is the long-tail "two
        // friends named Chris" path, kept as an escape hatch.
        val (_, firstAlicePublic) = generateEd25519KeyPair()
        dao.insertContact(
            trustedContact(
                displayName = "Alice",
                publicKey = firstAlicePublic.toHex(),
                plaintextSecret = "firstsecret".toByteArray()
            )
        )

        val (victimPrivate, _) = generateEd25519KeyPair()
        val (_, secondAlicePublic) = generateEd25519KeyPair()
        val secondSecret = EcdhExchange.deriveSharedSecret(victimPrivate, secondAlicePublic)
        dao.insertContact(
            trustedContact(
                displayName = "Alice",
                publicKey = secondAlicePublic.toHex(),
                plaintextSecret = secondSecret
            )
        )

        val all = dao.getAllContacts().first()
        assertEquals(2, all.size)
        val publicKeys = all.map { it.publicKey }.toSet()
        assertEquals(
            "Both Alice rows must be present with their distinct public keys",
            setOf(firstAlicePublic.toHex(), secondAlicePublic.toHex()),
            publicKeys
        )
    }

    // ── QR round-trip preserves key material for valid ECDH ──

    @Test
    fun qrRoundtrip_preservesKeyMaterialForEcdh() {
        val (_, publicKey) = generateEd25519KeyPair()

        val encoded = QrCodeUtils.encode("TestUser", publicKey)
        val decoded = QrCodeUtils.decode(encoded)!!

        assertArrayEquals(publicKey, decoded.publicKey)

        // The decoded key produces a valid 20-byte TOTP secret
        val (otherPrivate, _) = generateEd25519KeyPair()
        val secret = EcdhExchange.deriveSharedSecret(otherPrivate, decoded.publicKey)
        assertEquals("TOTP key must be 20 bytes", 20, secret.size)
    }

    // ── QR bitmap generation works on Android ──

    @Test
    fun qrBitmap_generatesCorrectDimensions() {
        val (_, publicKey) = generateEd25519KeyPair()
        val content = QrCodeUtils.encode("TestUser", publicKey)
        val bitmap = QrCodeUtils.generateBitmap(content, 512)

        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
    }

    // ── Helpers ──

    private fun ByteArray.toHex(): String = HexCodec.bytesToHex(this)
    private fun String.hexToBytes(): ByteArray = HexCodec.hexToBytes(this)
}
