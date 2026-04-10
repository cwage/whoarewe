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
        return getPublicKeyBytes()?.joinToString("") { "%02x".format(it) }
    }

    fun getFingerprint(): String? {
        val pubKey = getPublicKeyBytes() ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKey)
        return hash.take(8).joinToString(":") { "%02X".format(it) }
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

    suspend fun generateKey(cipher: Cipher): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val generator = Ed25519KeyPairGenerator()
            generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = generator.generateKeyPair()

            val privateKey = keyPair.private as Ed25519PrivateKeyParameters
            val publicKey = keyPair.public as Ed25519PublicKeyParameters

            val privateKeyBytes = privateKey.encoded
            val publicKeyBytes = publicKey.encoded

            // Encrypt private key with biometric-bound AES key
            val encrypted = cipher.doFinal(privateKeyBytes)
            encryptedKeyFile().writeBytes(encrypted)
            ivFile().writeBytes(cipher.iv)

            // Public key stored as raw bytes
            publicKeyFile().writeBytes(publicKeyBytes)

            // Zero out private key bytes
            privateKeyBytes.fill(0)

            getFingerprint()!!
        }
    }

    fun decryptPrivateKey(cipher: Cipher): ByteArray {
        val encrypted = encryptedKeyFile().readBytes()
        return cipher.doFinal(encrypted)
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
