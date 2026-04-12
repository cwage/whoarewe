package com.whoarewe.app.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Instrumented coverage for the keystore-alias recovery work in
 * cwage/whoarewe#30.
 *
 * The bug being prevented: prior to this change, `KeyManager.deleteKeys`
 * could leave the app in an unrecoverable loop if `keyStore.deleteEntry`
 * threw — the on-disk identity files were already gone (deleted earlier in
 * the same method), but the AndroidKeyStore alias was orphaned, and
 * `ensureKeyStoreKey` would early-return on `containsAlias == true` without
 * probing the alias for permanent invalidation. Every subsequent attempt
 * to (re)create the identity would land back in the same catch block,
 * forever, with no escape short of uninstalling the app.
 *
 * The fix has three moving parts that this file pins:
 *   1. `ensureKeyStoreKey` is now idempotent on a healthy alias *and*
 *      regenerates when the alias is gone — see [ensureKeyStoreKey_isIdempotent]
 *      and [ensureKeyStoreKey_regeneratesAfterManualDelete].
 *   2. `deleteKeys` now does the keystore delete *first* (so a mid-op
 *      failure leaves a self-consistent state) and clears both halves
 *      atomically from the caller's perspective — see [deleteKeys_clearsBothAliasAndFiles].
 *   3. The probe in `ensureKeyStoreKey` (`isAliasHealthy`) catches *only*
 *      [android.security.keystore.KeyPermanentlyInvalidatedException], so
 *      legacy `UserNotAuthenticatedException` on API < R does not
 *      mis-classify a healthy time-bound key as dead. This branch can't
 *      be triggered from a non-interactive test (would need a real
 *      biometric re-enrollment) — it's enforced by code review and the
 *      narrow `catch` clause in [KeyManager.isAliasHealthy].
 *
 * Skip behavior: the default API 28 x86_64 emulator system image used by
 * `scripts/local-instrumented.sh` and the `instrumented-tests (28)` CI job
 * does not ship a working Gatekeeper HAL. That means
 * `setUserAuthenticationRequired(true)` — which the production keygen
 * unconditionally sets — throws `InvalidAlgorithmParameterException`
 * wrapping `IllegalStateException("Gatekeeper service not available")`
 * at `cipher.init` time, regardless of whether a PIN has been set with
 * `locksettings set-pin`. The existing [KeyManagerDekTest] sidesteps the
 * problem by generating its own non-Keystore JCE key; *this* file
 * fundamentally needs the real production AndroidKeyStore path because
 * the bug being fixed is about the AndroidKeyStore alias lifecycle.
 *
 * Resolution: the [setUp] block probes the environment with a real
 * `ensureKeyStoreKey` call and uses [Assume.assumeNoException] to skip
 * the suite if keygen blows up. On API ≥ R emulators and on real
 * hardware (which is what should be running these tests anyway, since
 * the modern path is what the production code primarily targets), the
 * probe succeeds and every test runs normally.
 *
 * Manual verification of the path that can't be automated: on an API 33
 * emulator with a PIN configured, generate an identity, then change the
 * device PIN (which triggers `KeyPermanentlyInvalidated`), reopen the app,
 * and tap "regenerate identity." The recovery should succeed in-app
 * without an uninstall — see the issue body for the full repro.
 */
@RunWith(AndroidJUnit4::class)
class KeyManagerInvalidationTest {

    private lateinit var context: Context
    private lateinit var keyManager: KeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyManager = KeyManager(context)
        clearKeystoreAndFiles()

        // Probe the environment for working AndroidKeyStore key generation
        // with `setUserAuthenticationRequired(true)`. The default API 28
        // x86_64 emulator system image used by CI lacks a Gatekeeper HAL
        // and throws here even with a PIN set. Skip the whole suite when
        // we can't run it cleanly — see the class kdoc for the full
        // explanation, including which CI jobs this affects.
        try {
            keyManager.ensureKeyStoreKey()
        } catch (e: Exception) {
            Assume.assumeNoException(
                "Skipping: AndroidKeyStore on this device cannot create " +
                    "user-auth-required keys. Likely the API 28 emulator " +
                    "image without a working Gatekeeper service.",
                e
            )
        }
        // Drop the probe alias so the actual @Test method starts from a
        // known-empty state, identical to a fresh KeyManager.
        clearKeystoreAndFiles()
    }

    @After
    fun tearDown() {
        clearKeystoreAndFiles()
    }

    @Test
    fun ensureKeyStoreKey_createsAlias() {
        assertFalse("alias must not exist before ensureKeyStoreKey", aliasExists())
        keyManager.ensureKeyStoreKey()
        assertTrue("alias must exist after ensureKeyStoreKey", aliasExists())
    }

    @Test
    fun ensureKeyStoreKey_isIdempotent() {
        // The two-call sequence pins that calling ensureKeyStoreKey on a
        // pre-existing healthy alias does not throw and does not regenerate
        // it — the latter is what makes the modern probe path safe to call
        // unconditionally on every getEncryptionCipher / getDecryptionCipher.
        // We can't directly assert "didn't regenerate" without inspecting
        // private key material we don't have access to, but we can pin
        // that containsAlias remains true across both calls and that no
        // exception is thrown.
        keyManager.ensureKeyStoreKey()
        assertTrue(aliasExists())
        keyManager.ensureKeyStoreKey()
        assertTrue("alias must still exist after the second call", aliasExists())
    }

    @Test
    fun ensureKeyStoreKey_regeneratesAfterManualDelete() {
        // Simulates the recovered state after a successful (or partially
        // successful) deleteKeys: alias gone. The next call must
        // re-create it — this is the "fall through to regenerate"
        // behavior that future-proofs the orphan-alias loop fix.
        keyManager.ensureKeyStoreKey()
        assertTrue(aliasExists())

        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        ks.deleteEntry("whoarewe_identity_key")
        assertFalse("alias must be gone after manual deleteEntry", aliasExists())

        keyManager.ensureKeyStoreKey()
        assertTrue("alias must be re-created on the next ensureKeyStoreKey", aliasExists())
    }

    @Test
    fun deleteKeys_clearsBothAliasAndFiles() {
        // Set up a state where both halves exist: a freshly created
        // alias plus stub on-disk files matching the names KeyManager
        // uses. We don't go through generateKey/decryptIdentityBlob —
        // that would require a usable Cipher.doFinal which itself
        // requires biometric auth on the modern path and is what
        // KeyManagerDekTest deliberately sidesteps. The bytes we write
        // here are arbitrary; deleteKeys only cares whether the files
        // exist when it's called.
        keyManager.ensureKeyStoreKey()
        val keysDir = File(context.filesDir, "keys").also { it.mkdirs() }
        File(keysDir, "identity.enc").writeBytes(byteArrayOf(1, 2, 3))
        File(keysDir, "identity.iv").writeBytes(byteArrayOf(4, 5, 6))
        File(keysDir, "identity.pub").writeBytes(byteArrayOf(7, 8, 9))

        assertTrue(aliasExists())
        assertTrue(File(keysDir, "identity.enc").exists())
        assertTrue(File(keysDir, "identity.iv").exists())
        assertTrue(File(keysDir, "identity.pub").exists())

        keyManager.deleteKeys()

        // The keystore alias was the first thing to go (per the
        // cwage/whoarewe#30 reorder) and the files followed.
        assertFalse("alias must be cleared by deleteKeys", aliasExists())
        assertFalse(
            "encrypted key file must be cleared by deleteKeys",
            File(keysDir, "identity.enc").exists()
        )
        assertFalse(
            "IV file must be cleared by deleteKeys",
            File(keysDir, "identity.iv").exists()
        )
        assertFalse(
            "public key file must be cleared by deleteKeys",
            File(keysDir, "identity.pub").exists()
        )
    }

    private fun aliasExists(): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        return ks.containsAlias("whoarewe_identity_key")
    }

    private fun clearKeystoreAndFiles() {
        // Best-effort: another instrumentation test in the same run may
        // have left state behind. We do exactly the cleanup deleteKeys
        // would do, but with no assertions, so a stale state from a
        // prior test class can't fail us before the @Test even runs.
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias("whoarewe_identity_key")) {
                ks.deleteEntry("whoarewe_identity_key")
            }
        } catch (_: Exception) {
            // If keystore is wedged before the test even starts, we
            // can't recover here — let the test fail naturally.
        }
        val keysDir = File(context.filesDir, "keys")
        if (keysDir.exists()) {
            keysDir.listFiles()?.forEach { it.delete() }
        }
    }
}
