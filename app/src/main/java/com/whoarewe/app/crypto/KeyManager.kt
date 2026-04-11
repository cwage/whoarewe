package com.whoarewe.app.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class KeyManager(private val context: Context) {
    companion object {
        private const val KEYSTORE_ALIAS = "whoarewe_identity_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * Exact-byte sentinel written by [e2eWriteIdentityFilesForTest].
         * Matched by [hasSentinelKey] so the vm can skip the unlock
         * biometric gate in Maestro flows. See cwage/whoarewe#32.
         */
        private val SENTINEL_BLOB = byteArrayOf(0)
    }

    private val keysDir: File
        get() = File(context.filesDir, "keys").also { it.mkdirs() }

    private fun encryptedKeyFile(): File = File(keysDir, "identity.enc")
    private fun ivFile(): File = File(keysDir, "identity.iv")
    private fun publicKeyFile(): File = File(keysDir, "identity.pub")

    fun hasKey(): Boolean = encryptedKeyFile().exists() && publicKeyFile().exists()

    fun getPublicKeyBytes(): ByteArray? {
        val f = publicKeyFile()
        return if (f.exists()) f.readBytes() else null
    }

    fun getPublicKeyHex(): String? {
        return getPublicKeyBytes()?.let { HexCodec.bytesToHex(it) }
    }

    fun getFingerprint(): String? {
        val pubKey = getPublicKeyBytes() ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKey)
        // Format the leading 8 bytes as colon-separated uppercase hex pairs
        // (e.g. "A1:B2:C3:..."). Encode through HexCodec to keep all hex
        // conversion routed through one place.
        return HexCodec.bytesToHex(hash.copyOfRange(0, 8)).uppercase().chunked(2).joinToString(":")
    }

    fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * On API < R the keystore key uses the deprecated time-bound auth model
     * (`setUserAuthenticationValidityDurationSeconds`). In that model, calling
     * `cipher.init(...)` itself enforces the auth window — if the user has not
     * authenticated to the device within the validity period, init throws
     * `UserNotAuthenticatedException` *before* `BiometricPrompt` ever runs.
     *
     * The fix is to invert the order on legacy: prompt first (without a
     * CryptoObject), let success refresh the auth window, *then* init the
     * cipher inside that window. Callers branch on this flag to pick the
     * right ordering.
     *
     * On API ≥ R the key uses `setUserAuthenticationParameters`, which moves
     * the auth check to CryptoObject unlock at prompt-time, so the cipher
     * can be initialized up front and the prompt does the unlocking.
     */
    fun usesLegacyAuth(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    fun getAuthenticatorTypes(): Int {
        val manager = BiometricManager.from(context)
        val biometricAvailable = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        return if (biometricAvailable) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    private fun ensureKeyStoreKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) return

        val biometricAvailable = isBiometricAvailable()

        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(10)
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    fun getEncryptionCipher(): Cipher {
        try {
            ensureKeyStoreKey()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val key = keyStore.getKey(KEYSTORE_ALIAS, null)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKeys()
            throw KeyInvalidatedException(
                "Biometric enrollment changed. Please regenerate your identity."
            )
        }
    }

    fun getDecryptionCipher(): Cipher {
        try {
            ensureKeyStoreKey()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val key = keyStore.getKey(KEYSTORE_ALIAS, null)
            val ivf = ivFile()
            if (!ivf.exists()) {
                deleteKeys()
                throw KeyInvalidatedException(
                    "Key data is corrupt. Please regenerate your identity."
                )
            }
            val iv = ivf.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKeys()
            throw KeyInvalidatedException(
                "Biometric enrollment changed. Please regenerate your identity."
            )
        }
    }

    /**
     * Result of [generateKey]: the displayed fingerprint plus the freshly
     * generated 32-byte TOTP DEK (see cwage/whoarewe#32). The caller takes
     * ownership of [dek] — typically stuffing it into the VM's in-memory
     * cache — and is responsible for zeroing it when the session ends.
     */
    class GenerateResult(val fingerprint: String, val dek: ByteArray)

    /**
     * Result of [decryptIdentityBlob]: both halves of the encrypted identity
     * blob. Callers at *pair time* use [privateKey] (for ECDH) and discard
     * [dek] (the VM cache already has it). Callers at *unlock time* use
     * [dek] (to populate the cache) and discard [privateKey]. In either
     * case the caller is responsible for zeroing the half it doesn't need,
     * and eventually the half it does.
     */
    class DecryptedIdentity(val dek: ByteArray, val privateKey: ByteArray)

    suspend fun generateKey(cipher: Cipher): Result<GenerateResult> = withContext(Dispatchers.IO) {
        runCatching {
            val generator = Ed25519KeyPairGenerator()
            generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = generator.generateKeyPair()

            val privateKey = keyPair.private as Ed25519PrivateKeyParameters
            val publicKey = keyPair.public as Ed25519PublicKeyParameters

            val privateKeyBytes = privateKey.encoded
            val publicKeyBytes = publicKey.encoded

            // Fresh TOTP DEK lives inside the same biometric-wrapped blob
            // as the Ed25519 private key. See the identity blob format
            // comment on `packIdentityBlob` — length-prefixed so the
            // unpack code doesn't need to hard-code the DEK size.
            val dek = TotpSecretCodec.generateDek()
            val plaintextBlob = packIdentityBlob(dek, privateKeyBytes)

            try {
                val encrypted = cipher.doFinal(plaintextBlob)
                encryptedKeyFile().writeBytes(encrypted)
                ivFile().writeBytes(cipher.iv)

                // Public key stored as raw bytes
                publicKeyFile().writeBytes(publicKeyBytes)

                GenerateResult(fingerprint = getFingerprint()!!, dek = dek.copyOf())
            } finally {
                // Zero every ephemeral secret buffer we own. The caller
                // gets their own copy of the DEK via `dek.copyOf()` above
                // and is responsible for its own zeroization.
                plaintextBlob.fill(0)
                dek.fill(0)
                privateKeyBytes.fill(0)
            }
        }
    }

    /**
     * Decrypt the biometric-wrapped identity blob and return both halves.
     * See [DecryptedIdentity] for the caller responsibility split.
     */
    fun decryptIdentityBlob(cipher: Cipher): DecryptedIdentity {
        val encrypted = encryptedKeyFile().readBytes()
        val plaintext = cipher.doFinal(encrypted)
        try {
            return unpackIdentityBlob(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Identity blob format (cwage/whoarewe#32):
     *
     *   [4-byte BE length-of-DEK][DEK bytes][Ed25519 private key bytes]
     *
     * Length-prefixed so the unpack path doesn't need to hard-code the DEK
     * size — if [TotpSecretCodec.DEK_BYTES] ever moves from 32 to 64 or
     * similar, only the pack/unpack pair needs updating.
     *
     * The Ed25519 private key is always [Ed25519PrivateKeyParameters.KEY_SIZE]
     * bytes; it lives at the end of the blob and occupies whatever is left
     * after the length-prefixed DEK.
     */
    private fun packIdentityBlob(dek: ByteArray, privateKey: ByteArray): ByteArray {
        val blob = ByteArray(4 + dek.size + privateKey.size)
        ByteBuffer.wrap(blob).putInt(0, dek.size)
        System.arraycopy(dek, 0, blob, 4, dek.size)
        System.arraycopy(privateKey, 0, blob, 4 + dek.size, privateKey.size)
        return blob
    }

    private fun unpackIdentityBlob(blob: ByteArray): DecryptedIdentity {
        if (blob.size < 4) {
            throw KeyInvalidatedException("Identity blob is truncated (len=${blob.size})")
        }
        val dekLen = ByteBuffer.wrap(blob).getInt(0)
        if (dekLen <= 0 || 4 + dekLen > blob.size) {
            throw KeyInvalidatedException("Identity blob has invalid DEK length $dekLen")
        }
        val dek = ByteArray(dekLen)
        System.arraycopy(blob, 4, dek, 0, dekLen)
        val privateKeyLen = blob.size - 4 - dekLen
        if (privateKeyLen <= 0) {
            throw KeyInvalidatedException("Identity blob has no private key material")
        }
        val privateKey = ByteArray(privateKeyLen)
        System.arraycopy(blob, 4 + dekLen, privateKey, 0, privateKeyLen)
        return DecryptedIdentity(dek = dek, privateKey = privateKey)
    }

    /**
     * Debug seam used by `MainActivity.handleE2eIntent.e2e_create_identity`
     * (cwage/whoarewe#11). Writes a real public key file plus a sentinel
     * encrypted-key blob, so `hasKey()` returns true and the vm transitions
     * straight into the main contact list state without ever invoking
     * `BiometricPrompt` or touching Android Keystore.
     *
     * The encrypted blob is intentionally garbage. Any code path that tries
     * to call `decryptIdentityBlob` against it will fail. The only consumer
     * of this state — the Maestro `pair-wizard-navigation` flow — only reads
     * the public key (for QR display), never the private one, and scripts/
     * e2e-pairing.sh exercises the real biometric flow for actual pairing.
     *
     * The Locked → unlock biometric path added in cwage/whoarewe#32 detects
     * this sentinel state via [hasSentinelKey] and bypasses the prompt,
     * because Maestro cannot drive the system credential bouncer.
     *
     * Should only be called from a debug-only intent handler. The method
     * itself is unconditionally compiled (Kotlin has no debug-only modifier),
     * but the caller is gated on `BuildConfig.DEBUG`.
     */
    fun e2eWriteIdentityFilesForTest(publicKey: ByteArray) {
        publicKeyFile().writeBytes(publicKey)
        encryptedKeyFile().writeBytes(SENTINEL_BLOB)
        ivFile().writeBytes(SENTINEL_BLOB)
    }

    /**
     * True when the identity files on disk were written by
     * [e2eWriteIdentityFilesForTest] — a Maestro test bootstrap rather
     * than a real biometric-unlocked identity. The vm uses this to skip
     * the unlock biometric gate in e2e mode. See cwage/whoarewe#32.
     *
     * The detection is by exact byte match against [SENTINEL_BLOB]. A real
     * encrypted identity blob is at minimum `4 + 32 + 32` bytes plaintext
     * plus a GCM tag after encryption, so the one-byte sentinel cannot
     * collide with a legitimately-wrapped identity.
     */
    fun hasSentinelKey(): Boolean {
        val f = encryptedKeyFile()
        if (!f.exists()) return false
        return f.readBytes().contentEquals(SENTINEL_BLOB)
    }

    private fun deleteKeys() {
        encryptedKeyFile().delete()
        ivFile().delete()
        publicKeyFile().delete()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) {}
    }
}

class KeyInvalidatedException(message: String) : Exception(message)
