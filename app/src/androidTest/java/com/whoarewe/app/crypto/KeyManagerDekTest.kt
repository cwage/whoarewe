package com.whoarewe.app.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Instrumentation test for the identity-blob format introduced in
 * cwage/whoarewe#32. Exercises [KeyManager.generateKey] and
 * [KeyManager.decryptIdentityBlob] together using a plain JCE AES-GCM
 * key (generated in-process, not Keystore-bound) so the test doesn't
 * need to drive a BiometricPrompt — the thing under test here is the
 * `[len][DEK][privateKey]` pack/unpack contract plus the on-disk layout,
 * not the biometric gating, which the two-emulator e2e harness covers.
 *
 * Deliberately does *not* use the production `AndroidKeyStore` path —
 * that path enforces per-op biometric auth via [setUserAuthenticationParameters]
 * and there is no way to satisfy it from an instrumentation test without
 * user interaction. What we're pinning here is:
 *   - pack/unpack is a round-trip
 *   - the Ed25519 private key is exactly 32 bytes on the way out
 *   - the DEK is exactly [TotpSecretCodec.DEK_BYTES] bytes on the way out
 *   - the IV file contains a non-trivial IV (not all zeros)
 *   - the public key file carries the raw 32-byte Ed25519 point
 */
@RunWith(AndroidJUnit4::class)
class KeyManagerDekTest {

    private lateinit var context: Context
    private lateinit var keyManager: KeyManager
    private lateinit var aesKey: SecretKey

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any prior identity files the process may have left behind
        // from another instrumentation test in this same run.
        val keysDir = File(context.filesDir, "keys")
        if (keysDir.exists()) {
            keysDir.listFiles()?.forEach { it.delete() }
        }
        keyManager = KeyManager(context)

        // Plain JCE AES-256 key, generated in-process. Not Keystore-bound.
        val kgen = KeyGenerator.getInstance("AES")
        kgen.init(256)
        aesKey = kgen.generateKey()
    }

    private fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey)
        return cipher
    }

    private fun decryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return cipher
    }

    @Test
    fun generateKey_writesFilesAndReturnsFingerprintPlusDek() = runBlocking {
        val result = keyManager.generateKey(encryptCipher())
        val generated = result.getOrThrow()

        assertNotNull("fingerprint must be non-null", generated.fingerprint)
        assertEquals(
            "DEK must be exactly the codec's DEK size",
            TotpSecretCodec.DEK_BYTES,
            generated.dek.size
        )
        // The DEK must not be all-zero (the default ByteArray state) —
        // catches the "oops I forgot to generate random bytes" failure.
        var sawNonZero = false
        for (b in generated.dek) if (b != 0.toByte()) { sawNonZero = true; break }
        assertEquals("DEK must contain random bytes, not be zero-initialized", true, sawNonZero)

        // Files landed where KeyManager expects them.
        val keysDir = File(context.filesDir, "keys")
        assertEquals(true, File(keysDir, "identity.enc").exists())
        assertEquals(true, File(keysDir, "identity.iv").exists())
        assertEquals(true, File(keysDir, "identity.pub").exists())
        assertEquals(
            "public key file should carry the raw 32-byte Ed25519 point",
            32,
            File(keysDir, "identity.pub").length().toInt()
        )
    }

    @Test
    fun decryptIdentityBlob_roundTripsDekAndPrivateKey() = runBlocking {
        val generated = keyManager.generateKey(encryptCipher()).getOrThrow()
        val originalDek = generated.dek.copyOf()

        // Reconstruct the decryption cipher from the IV the generator
        // wrote, then ask KeyManager to unpack the blob.
        val iv = File(context.filesDir, "keys/identity.iv").readBytes()
        val decrypted = keyManager.decryptIdentityBlob(decryptCipher(iv))

        assertArrayEquals(
            "DEK recovered from the blob must match the one returned at generate time",
            originalDek,
            decrypted.dek
        )
        assertEquals(
            "Ed25519 private key must be exactly 32 bytes",
            32,
            decrypted.privateKey.size
        )
        // Don't bother checking the private-key value itself — we don't
        // have a reference for it. The point is that unpack returned
        // *something* of the right shape, and the DEK round-trip above
        // plus the length check prove the length-prefix slicing works.
    }

    @Test
    fun twoGenerations_produceDistinctDeksAndDistinctIvs() = runBlocking {
        val first = keyManager.generateKey(encryptCipher()).getOrThrow()
        val firstIv = File(context.filesDir, "keys/identity.iv").readBytes()
        val firstDekCopy = first.dek.copyOf()

        // Second generate overwrites the identity files — this simulates
        // a re-setup after a user wipes their identity. Distinct DEKs
        // must be produced for security; distinct IVs must be produced
        // because same-key-same-IV is the AES-GCM catastrophic failure
        // mode. The GCM layer in getEncryptionCipher() handles the IV,
        // so this is really a sanity check on the Keystore/JCE provider.
        val second = keyManager.generateKey(encryptCipher()).getOrThrow()
        val secondIv = File(context.filesDir, "keys/identity.iv").readBytes()

        assertNotEquals(
            "Successive generate calls must produce distinct DEKs",
            firstDekCopy.toList(),
            second.dek.toList()
        )
        assertNotEquals(
            "Successive generate calls must produce distinct GCM IVs",
            firstIv.toList(),
            secondIv.toList()
        )
    }

    @Test
    fun hasSentinelKey_falseForRealGenerateKey() = runBlocking {
        keyManager.generateKey(encryptCipher()).getOrThrow()
        assertEquals(
            "A real encrypted identity blob must never match the sentinel shape",
            false,
            keyManager.hasSentinelKey()
        )
    }
}
