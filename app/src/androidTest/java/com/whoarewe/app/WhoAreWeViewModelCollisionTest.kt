package com.whoarewe.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whoarewe.app.crypto.HexCodec
import com.whoarewe.app.crypto.QrCodeUtils
import com.whoarewe.app.data.AppDatabase
import com.whoarewe.app.data.ContactDao
import com.whoarewe.app.data.TrustedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * Exercises the collision glue in [WhoAreWeViewModel.onQrScanned] (and the
 * three confirm/cancel handlers) against a real `WhoAreWeViewModel` wired to
 * the production `AppDatabase` singleton — i.e. the same construction path
 * `MainActivity` uses. The `DAO` and `NameMatcher` pieces are already covered
 * in isolation by `ContactDaoTest` and `NameMatcherTest`; this file covers
 * the *wiring between them* so a refactor that (say) swaps the name-match
 * comparator or drops the `first()` call in the collision scan will fire a
 * test rather than silently accept an impersonation QR.
 *
 * Deliberately does **not** assert on the biometric-request side of the
 * confirm handlers. That path goes through `KeyManager.getDecryptionCipher`
 * which requires a real Keystore-bound identity key, and standing one up in
 * an instrumented test would overlap with (and be more brittle than) the
 * two-emulator `scripts/e2e-pairing.sh` harness. What this test file does
 * assert is the observable VM state for collision detection itself: the
 * `_pendingNameCollision` flow gets the right payload set, and the three
 * user-choice handlers clear it on the way out. The DAO-level "the swap is
 * atomic" guarantee is pinned by `ContactDaoTest.replaceContact_*` and
 * `PairingIntegrationTest.nameCollision_*`; the end-to-end cross-device
 * ECDH + TOTP pairing is pinned by `scripts/run-local-e2e.sh`.
 */
@RunWith(AndroidJUnit4::class)
class WhoAreWeViewModelCollisionTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getInstance(app)
        dao = db.contactDao()
        // Start from a clean slate — `AppDatabase.getInstance` is a
        // file-backed singleton, so rows from a prior test in this
        // instrumentation process would otherwise leak in.
        db.clearAllTables()
    }

    @After
    fun tearDown() = runBlocking {
        db.clearAllTables()
    }

    // ---- helpers ----

    private fun generateEd25519PublicKey(): ByteArray {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        return (generator.generateKeyPair().public as Ed25519PublicKeyParameters).encoded
    }

    private suspend fun seedContact(name: String, publicKey: ByteArray): Long =
        dao.insertContact(
            TrustedContact(
                displayName = name,
                publicKey = HexCodec.bytesToHex(publicKey),
                totpSecret = "deadbeef"
            )
        )

    private suspend fun makeVm(): WhoAreWeViewModel {
        val vm = withContext(Dispatchers.Main) { WhoAreWeViewModel(app) }
        // Wait for the init block's `viewModelScope.launch` to move off
        // Loading so subsequent `onQrScanned` calls see a stable state.
        withTimeout(5_000) {
            vm.uiState.first { it !is UiState.Loading }
        }
        return vm
    }

    private suspend fun scan(vm: WhoAreWeViewModel, qr: String) {
        withContext(Dispatchers.Main) { vm.onQrScanned(qr) }
    }

    // ---- collision detection (the core new behaviour) ----

    @Test
    fun onQrScanned_withCollidingName_setsPendingCollision() = runBlocking {
        val existingKey = generateEd25519PublicKey()
        val oldId = seedContact("Alice", existingKey)

        val attackerKey = generateEd25519PublicKey()
        val attackerQr = QrCodeUtils.encode("Alice", attackerKey)

        val vm = makeVm()
        scan(vm, attackerQr)

        val pending = withTimeout(5_000) {
            vm.pendingNameCollision.first { it != null }
        }!!

        assertEquals(
            "Collision must surface the pre-existing Alice row",
            "Alice",
            pending.existing.displayName
        )
        assertEquals(
            "Collision must carry the pre-existing row's primary key so the Replace handler can target it in the atomic DAO swap",
            oldId,
            pending.existing.id
        )
        assertEquals(
            "Existing row's public key must be the seeded one, not the attacker's",
            HexCodec.bytesToHex(existingKey),
            pending.existing.publicKey
        )
        assertArrayEquals(
            "Incoming payload must carry the attacker's public key so Replace/Add pair with the right key",
            attackerKey,
            pending.incoming.publicKey
        )
        assertEquals("Alice", pending.incoming.displayName)
    }

    @Test
    fun onQrScanned_withMultipleMatchingNames_deterministicallyTargetsLowestId() = runBlocking {
        // When the user has previously accepted "Add as a second <name>",
        // two existing rows can legitimately share a display name. A third
        // scan of the same name with yet another key must still produce a
        // deterministic collision target — specifically the lowest-id
        // (oldest) matching row — rather than something dependent on the
        // SQLite tiebreaker for `ORDER BY displayName`, which is
        // implementation-defined and would make the Replace branch swap
        // "whichever Alice came back first in this query." Pinning the
        // oldest-wins policy here so a refactor that reintroduces
        // `firstOrNull` on the Flow list fires this test. See Copilot
        // round 1 on PR #37.
        val firstKey = generateEd25519PublicKey()
        val firstId = seedContact("Alice", firstKey)
        val secondKey = generateEd25519PublicKey()
        val secondId = seedContact("Alice", secondKey)
        // Sanity: the two auto-generated ids are ordered the way Room
        // normally hands them out, so "lowest id = oldest" is a real
        // policy here and not a definition-by-accident.
        assertTrue(
            "Precondition: the second insertion must receive a higher id than the first",
            secondId > firstId
        )

        val attackerKey = generateEd25519PublicKey()
        val attackerQr = QrCodeUtils.encode("Alice", attackerKey)

        val vm = makeVm()
        scan(vm, attackerQr)

        val pending = withTimeout(5_000) {
            vm.pendingNameCollision.first { it != null }
        }!!

        assertEquals(
            "Multi-match collision target must be the oldest (lowest-id) matching row",
            firstId,
            pending.existing.id
        )
    }

    @Test
    fun onQrScanned_withCaseDifferentName_stillDetectsCollision() = runBlocking {
        // Pins that the collision scan uses `NameMatcher` (case-insensitive,
        // NFC-normalized) rather than a naive string equality — otherwise an
        // attacker could sneak past by lowercasing the target's name. If
        // someone later swaps the comparator back to `==`, this fires.
        val existingKey = generateEd25519PublicKey()
        seedContact("Alice", existingKey)

        val attackerKey = generateEd25519PublicKey()
        val attackerQr = QrCodeUtils.encode("alice", attackerKey)

        val vm = makeVm()
        scan(vm, attackerQr)

        val pending = withTimeout(5_000) {
            vm.pendingNameCollision.first { it != null }
        }!!
        assertEquals("Alice", pending.existing.displayName)
    }

    @Test
    fun onQrScanned_withDistinctName_doesNotSetPendingCollision() = runBlocking {
        // Negative control: scanning a QR for a genuinely new contact must
        // NOT fire the collision dialog. We can't easily assert "and the
        // biometric request fired instead" without standing up a keystore
        // identity, but we can pin that the collision flow is silent.
        val existingKey = generateEd25519PublicKey()
        seedContact("Alice", existingKey)

        val bobKey = generateEd25519PublicKey()
        val bobQr = QrCodeUtils.encode("Bob", bobKey)

        val vm = makeVm()
        scan(vm, bobQr)

        // Give the async collision scan a beat to run, then assert it
        // did not flip pending. 1s is comfortably more than a Room query
        // round-trip on the emulator.
        val leaked = withTimeoutOrNull(1_000) {
            vm.pendingNameCollision.first { it != null }
        }
        assertNull("Non-colliding name must not raise the collision dialog", leaked)
    }

    @Test
    fun onQrScanned_samePublicKeyShortCircuitsBeforeCollisionCheck() = runBlocking {
        // Pin that the existing same-pubkey dedup (step 1) runs *before*
        // the name collision check (step 2). A QR that is identical to an
        // existing contact — same name, same key — must route to the
        // "already in your contacts" error path, not the collision dialog.
        // Otherwise rescanning a paired contact would pop a scary warning.
        val aliceKey = generateEd25519PublicKey()
        seedContact("Alice", aliceKey)

        val sameQr = QrCodeUtils.encode("Alice", aliceKey)

        val vm = makeVm()
        scan(vm, sameQr)

        val leaked = withTimeoutOrNull(1_000) {
            vm.pendingNameCollision.first { it != null }
        }
        assertNull(
            "Identical QR must hit the pubkey-dedup short-circuit, not the collision dialog",
            leaked
        )
    }

    // ---- confirm / cancel handlers ----

    @Test
    fun cancelNameCollision_clearsPendingWithoutSideEffects() = runBlocking {
        val existingKey = generateEd25519PublicKey()
        seedContact("Alice", existingKey)
        val attackerQr = QrCodeUtils.encode("Alice", generateEd25519PublicKey())

        val vm = makeVm()
        scan(vm, attackerQr)
        assertNotNull(
            "Precondition: collision must be pending before we cancel it",
            withTimeout(5_000) { vm.pendingNameCollision.first { it != null } }
        )

        withContext(Dispatchers.Main) { vm.cancelNameCollision() }

        assertNull(
            "Cancel must clear the pending collision state",
            vm.pendingNameCollision.value
        )
    }

    @Test
    fun confirmReplaceContact_clearsPending() = runBlocking {
        // The `requestBiometricForContact` tail may no-op (cipher init
        // throws on a test device with no identity key) — that's fine.
        // What we pin here is that the handler always drops the pending
        // state on its way out, so the dialog can't get stuck re-showing.
        val existingKey = generateEd25519PublicKey()
        seedContact("Alice", existingKey)
        val attackerQr = QrCodeUtils.encode("Alice", generateEd25519PublicKey())

        val vm = makeVm()
        scan(vm, attackerQr)
        withTimeout(5_000) { vm.pendingNameCollision.first { it != null } }

        withContext(Dispatchers.Main) { vm.confirmReplaceContact() }

        assertNull(vm.pendingNameCollision.value)
    }

    @Test
    fun confirmAddAsSecondContact_clearsPending() = runBlocking {
        val existingKey = generateEd25519PublicKey()
        seedContact("Alice", existingKey)
        val attackerQr = QrCodeUtils.encode("Alice", generateEd25519PublicKey())

        val vm = makeVm()
        scan(vm, attackerQr)
        withTimeout(5_000) { vm.pendingNameCollision.first { it != null } }

        withContext(Dispatchers.Main) { vm.confirmAddAsSecondContact() }

        assertNull(vm.pendingNameCollision.value)
    }

    @Test
    fun cancelNameCollision_onFreshVmIsANoop() = runBlocking {
        // The handler must tolerate being called without a pending state —
        // e.g. if the dialog is dismissed twice in quick succession, or if
        // some future glue fires it defensively. Must not NPE or flip any
        // observable state.
        val vm = makeVm()
        withContext(Dispatchers.Main) { vm.cancelNameCollision() }
        assertNull(vm.pendingNameCollision.value)
    }
}
