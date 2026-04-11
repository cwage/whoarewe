package com.whoarewe.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM AES-256-GCM wrap/unwrap for per-contact TOTP shared secrets
 * (cwage/whoarewe#32).
 *
 * The caller supplies a 32-byte DEK held in JVM heap — typically read out of
 * the biometric-unlocked identity blob by [KeyManager.decryptIdentityBlob] at
 * app unlock time. This codec intentionally does **not** touch Android
 * Keystore: after the unlock event has happened once, every TOTP tick
 * decryption is a pure software AES-GCM `doFinal`, with no biometric prompt
 * and no Keystore round-trip in the hot path. See `docs/pairing.md` for the
 * threat-model rationale.
 *
 * Each call to [encrypt] generates a fresh 12-byte IV via [SecureRandom].
 * Callers must store `(ciphertext, iv)` as a pair — the DEK is a single
 * long-lived key, so IV reuse under the same DEK would be catastrophic for
 * AES-GCM, which is why this class owns the IV generation and does not
 * accept a caller-supplied one.
 *
 * Host-JVM unit-testable: see `TotpSecretCodecTest`.
 */
object TotpSecretCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    const val DEK_BYTES = 32

    /** Output of [encrypt]: the authenticated ciphertext plus its 12-byte IV. */
    class EncryptedSecret(val ciphertext: ByteArray, val iv: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedSecret) return false
            return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
        }

        override fun hashCode(): Int =
            31 * ciphertext.contentHashCode() + iv.contentHashCode()
    }

    private val random = SecureRandom()

    /**
     * Encrypt [plaintext] under [dek] with a fresh random 12-byte IV.
     * The returned [EncryptedSecret] is what gets stored in Room.
     */
    fun encrypt(plaintext: ByteArray, dek: ByteArray): EncryptedSecret {
        require(dek.size == DEK_BYTES) { "DEK must be $DEK_BYTES bytes (AES-256)" }
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(dek, KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        val ct = cipher.doFinal(plaintext)
        return EncryptedSecret(ct, iv)
    }

    /**
     * Decrypt [encrypted] under [dek]. Throws the underlying JCE exception
     * (typically `AEADBadTagException`) if the ciphertext has been tampered
     * with or the DEK is wrong.
     */
    fun decrypt(encrypted: EncryptedSecret, dek: ByteArray): ByteArray {
        require(dek.size == DEK_BYTES) { "DEK must be $DEK_BYTES bytes (AES-256)" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(dek, KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_BITS, encrypted.iv)
        )
        return cipher.doFinal(encrypted.ciphertext)
    }

    /** Generate a fresh random 32-byte DEK. Caller owns zeroing. */
    fun generateDek(): ByteArray = ByteArray(DEK_BYTES).also { random.nextBytes(it) }
}
