package com.whoami.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whoami.app.crypto.EcdhExchange
import com.whoami.app.crypto.QrCodeUtils
import com.whoami.app.crypto.TotpGenerator
import com.whoami.app.data.AppDatabase
import com.whoami.app.data.Identity
import com.whoami.app.data.TrustedContact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private lateinit var dao: com.whoami.app.data.ContactDao

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
            TrustedContact(
                displayName = decoded.displayName,
                publicKey = decoded.publicKey.toHex(),
                totpSecret = aliceSecret.toHex()
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
        val times = listOf(
            System.currentTimeMillis(),
            System.currentTimeMillis() + 60_000,
            System.currentTimeMillis() + 120_000,
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
            TrustedContact(
                displayName = bobPayload.displayName,
                publicKey = bobPayload.publicKey.toHex(),
                totpSecret = secretAliceSide.toHex()
            )
        )

        // Bob scans Alice's QR → derives secret
        val alicePayload = QrCodeUtils.decode(aliceQr)!!
        val secretBobSide = EcdhExchange.deriveSharedSecret(bobPrivate, alicePayload.publicKey)

        // Secrets match
        assertArrayEquals(secretAliceSide, secretBobSide)

        // What's stored in the DB also matches
        val storedContact = dao.getAllContacts().first()[0]
        val storedSecret = storedContact.totpSecret.hexToBytes()
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
            TrustedContact(
                displayName = "Bob",
                publicKey = bobPayload.publicKey.toHex(),
                totpSecret = secretBob.toHex()
            )
        )

        // Pair with Carol
        val carolPayload = QrCodeUtils.decode(QrCodeUtils.encode("Carol", carolPublic))!!
        val secretCarol = EcdhExchange.deriveSharedSecret(alicePrivate, carolPayload.publicKey)
        dao.insertContact(
            TrustedContact(
                displayName = "Carol",
                publicKey = carolPayload.publicKey.toHex(),
                totpSecret = secretCarol.toHex()
            )
        )

        // Two contacts stored
        val contacts = dao.getAllContacts().first()
        assertEquals(2, contacts.size)

        // Secrets are different
        assert(!secretBob.contentEquals(secretCarol)) {
            "Different contacts must have different shared secrets"
        }

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

        assertEquals(bobExpected, TotpGenerator.generateCode(bobStored.totpSecret.hexToBytes(), now))
        assertEquals(carolExpected, TotpGenerator.generateCode(carolStored.totpSecret.hexToBytes(), now))
    }

    // ── Duplicate detection: same public key is caught ──

    @Test
    fun duplicateContact_detectedByPublicKey() = runTest {
        val (_, bobPublic) = generateEd25519KeyPair()
        val pubKeyHex = bobPublic.toHex()

        dao.insertContact(
            TrustedContact(displayName = "Bob", publicKey = pubKeyHex, totpSecret = "aabb")
        )

        val existing = dao.getContactByPublicKey(pubKeyHex)
        assertNotNull("Should detect existing contact by public key", existing)
        assertEquals("Bob", existing!!.displayName)
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
