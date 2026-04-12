package com.whoarewe.app.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
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
        private const val TAG = "KeyManager"
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

    // `internal` rather than `private` so the androidTest harness can
    // exercise the orphan-alias recovery path from cwage/whoarewe#30
    // directly, without going through `getEncryptionCipher` (which
    // requires biometric auth on the legacy API < R path and so isn't
    // callable from a non-interactive test). See `KeyManagerInvalidationTest`.
    internal fun ensureKeyStoreKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            // Probe the existing alias for permanent invalidation. Without
            // this probe an orphaned-and-invalidated alias would block every
            // subsequent identity creation forever: `containsAlias` returns
            // true → we'd return early → the next `cipher.init` in
            // `getEncryptionCipher` / `getDecryptionCipher` would throw
            // `KeyPermanentlyInvalidatedException` → the catch block calls
            // `deleteKeys()` (which can itself fail to clear the alias on
            // some OEMs) → the user taps "create identity" → we land back
            // here, the alias still exists, and the loop never breaks.
            // The only escape from that loop is uninstalling the app,
            // which most users will not figure out. See cwage/whoarewe#30.
            //
            // The probe is intentionally narrow: only
            // [KeyPermanentlyInvalidatedException] is treated as "alias is
            // toast, regenerate." Anything else — most importantly
            // `UserNotAuthenticatedException` on the legacy API < R
            // time-bound auth path, which `cipher.init` *legitimately*
            // throws when the auth window has lapsed on a perfectly
            // healthy key — must leave the alias alone and let the real
            // call site handle it. See [usesLegacyAuth] for that flow.
            if (isAliasHealthy(keyStore)) return
            // The alias is permanently invalidated. Drop it and fall
            // through to regeneration. We don't reuse [deleteKeys] here
            // because that also clears the on-disk identity files, which
            // the caller may still want — `getEncryptionCipher` is about
            // to overwrite them with a fresh blob anyway, but the cleaner
            // contract is "ensureKeyStoreKey only touches the keystore."
            try {
                keyStore.deleteEntry(KEYSTORE_ALIAS)
            } catch (e: Exception) {
                // If even the delete fails, log loudly and let the
                // subsequent `keyGenerator.generateKey()` throw with its
                // own diagnostic — there's nothing useful we can do here
                // beyond making the failure visible in logcat.
                Log.w(TAG, "ensureKeyStoreKey: failed to clear invalidated alias", e)
            }
        }

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

    /**
     * Returns true if the existing keystore alias can still be initialized
     * for encryption — i.e. it has not been permanently invalidated by a
     * biometric re-enrollment or device-credential change.
     *
     * Used by [ensureKeyStoreKey] to detect the orphan-alias case described
     * in cwage/whoarewe#30. Catches *only* [KeyPermanentlyInvalidatedException]
     * — every other failure mode (most importantly the legacy
     * `UserNotAuthenticatedException` thrown by `cipher.init` outside the
     * auth window on API < R) is treated as "alias is fine, the caller's
     * own error handling will deal with it." Mis-classifying a stale auth
     * window as a dead alias would silently destroy a healthy identity on
     * legacy devices.
     */
    private fun isAliasHealthy(keyStore: KeyStore): Boolean {
        return try {
            val key = keyStore.getKey(KEYSTORE_ALIAS, null) ?: return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            true
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "isAliasHealthy: existing alias is permanently invalidated", e)
            false
        } catch (e: Exception) {
            // Anything else — `UserNotAuthenticatedException` on legacy
            // auth, transient JCE provider hiccups, etc. — is "we can't
            // tell from here, leave it alone." See class kdoc.
            true
        }
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
        // Strict size validation: a tampered or corrupted blob must be
        // rejected here rather than downstream at `TotpSecretCodec.decrypt`
        // (which only knows "DEK wasn't 32 bytes") or at ECDH (which only
        // knows "private key wasn't 32 bytes"). Pin the exact expected
        // layout so either failure mode surfaces as KeyInvalidatedException
        // — the same class callers already handle for "re-enrollment
        // needed". See Copilot round 1 on PR #38.
        val expectedSize = 4 + TotpSecretCodec.DEK_BYTES + Ed25519PrivateKeyParameters.KEY_SIZE
        if (blob.size != expectedSize) {
            throw KeyInvalidatedException(
                "Identity blob has unexpected total size ${blob.size}, expected $expectedSize"
            )
        }
        val dekLen = ByteBuffer.wrap(blob).getInt(0)
        if (dekLen != TotpSecretCodec.DEK_BYTES) {
            throw KeyInvalidatedException(
                "Identity blob has unexpected DEK length $dekLen, expected ${TotpSecretCodec.DEK_BYTES}"
            )
        }
        val dek = ByteArray(TotpSecretCodec.DEK_BYTES)
        System.arraycopy(blob, 4, dek, 0, TotpSecretCodec.DEK_BYTES)
        val privateKey = ByteArray(Ed25519PrivateKeyParameters.KEY_SIZE)
        System.arraycopy(
            blob,
            4 + TotpSecretCodec.DEK_BYTES,
            privateKey,
            0,
            Ed25519PrivateKeyParameters.KEY_SIZE
        )
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
     * Detection requires **both** [encryptedKeyFile] and [ivFile] to match
     * the sentinel exactly — `e2eWriteIdentityFilesForTest` writes the
     * same one-byte payload to both, so a real encrypted identity would
     * never match (its IV alone is 12 bytes). Matching on both narrows
     * the chance of a partially-written or corrupted blob accidentally
     * triggering the bypass path. Callers should additionally gate on
     * `BuildConfig.DEBUG` so a release build cannot be tricked into
     * skipping the biometric unlock even in principle — see Copilot
     * round 1 on PR #38.
     */
    fun hasSentinelKey(): Boolean {
        val enc = encryptedKeyFile()
        val iv = ivFile()
        if (!enc.exists() || !iv.exists()) return false
        return enc.readBytes().contentEquals(SENTINEL_BLOB) &&
            iv.readBytes().contentEquals(SENTINEL_BLOB)
    }

    // `internal` for the same testing-from-androidTest reason as
    // [ensureKeyStoreKey]. See `KeyManagerInvalidationTest`.
    internal fun deleteKeys() {
        // Order matters (cwage/whoarewe#30): clear the keystore alias FIRST,
        // then the on-disk files. The previous order was the reverse, which
        // had a nasty failure mode — if the keystore delete threw (not
        // unheard-of on some OEM Android variants, or if the keystore is
        // wedged), the on-disk files were already gone but the alias
        // remained, leaving the app in an inconsistent state. Doing the
        // keystore first means a mid-operation failure leaves a state
        // that is at least self-consistent: files still exist, `hasKey()`
        // still reports true, the user can retry. The probe in
        // [ensureKeyStoreKey] is the last line of defense: even if both
        // halves end up out of sync somehow, the next call into
        // ensureKeyStoreKey will detect an invalidated alias and recover.
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            // Don't throw — the [ensureKeyStoreKey] probe added in
            // cwage/whoarewe#30 will catch the orphaned alias on the next
            // identity-creation attempt and clear it then. The fix-on-next
            // -call path is tested in `ensureKeyStoreKey_regeneratesAfterManualDelete`
            // (any leftover alias gets reclaimed by ensureKeyStoreKey).
            // We just need a loud signal in logcat so the failure is
            // diagnosable if it ever shows up in the wild.
            Log.w(TAG, "deleteKeys: failed to clear keystore alias", e)
        }

        // Check `File.delete()` return values. The previous code ignored
        // them, which would silently mask a storage-full or permissions
        // failure that left identity files on disk after a "successful"
        // delete. Log on failure but continue — the keystore alias is
        // already gone, so the app is no longer in the bricked-loop
        // state from cwage/whoarewe#30 even if a stray file lingers.
        deleteIfExists(encryptedKeyFile(), "encrypted key")
        deleteIfExists(ivFile(), "IV")
        deleteIfExists(publicKeyFile(), "public key")
    }

    private fun deleteIfExists(file: File, label: String) {
        if (!file.exists()) return
        if (!file.delete()) {
            Log.w(TAG, "deleteKeys: failed to delete $label file at ${file.absolutePath}")
        }
    }
}

class KeyInvalidatedException(message: String) : Exception(message)
