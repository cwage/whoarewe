package com.whoarewe.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whoarewe.app.crypto.EcdhExchange
import com.whoarewe.app.crypto.HexCodec
import com.whoarewe.app.crypto.KeyManager
import com.whoarewe.app.crypto.QrCodeUtils
import com.whoarewe.app.crypto.TotpGenerator
import com.whoarewe.app.ui.screens.PairStep
import com.whoarewe.app.data.AppDatabase
import com.whoarewe.app.data.Identity
import com.whoarewe.app.data.TrustedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

sealed class BiometricPurpose {
    data object Generate : BiometricPurpose()
    data class AddContact(val payload: QrCodeUtils.QrPayload) : BiometricPurpose()
}

/**
 * A pending biometric authentication request.
 *
 * `cipher` is non-null on API ≥ R: the vm pre-initialized the keystore cipher
 * and `BiometricPrompt` will unlock it via `CryptoObject` on success.
 *
 * `cipher` is null on API < R: the legacy `setUserAuthenticationValidityDurationSeconds`
 * key model would have thrown `UserNotAuthenticatedException` at `cipher.init`
 * *before* the prompt ever ran, so on legacy we defer the cipher acquisition to
 * `onBiometricSuccess` — by then the prompt has refreshed the device-credential
 * timer and `init` succeeds inside the validity window. See cwage/whoarewe#6.
 */
data class BiometricRequest(
    val cipher: Cipher?,
    val purpose: BiometricPurpose,
    val subtitle: String
)

data class ContactWithCode(
    val contact: TrustedContact,
    val code: String
)

sealed class UiState {
    data object Loading : UiState()

    data class Setup(
        val displayName: String = "",
        val isGenerating: Boolean = false,
        val error: String? = null
    ) : UiState()

    data class Main(
        val identity: Identity,
        val contacts: List<ContactWithCode> = emptyList(),
        val fingerprint: String = "",
        val secondsRemaining: Int = TotpGenerator.PERIOD_SECONDS.toInt(),
        val pairStep: PairStep? = null,
        val error: String? = null
    ) : UiState()
}

class WhoAreWeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val dao = db.contactDao()
    val keyManager = KeyManager(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _biometricRequest = MutableStateFlow<BiometricRequest?>(null)
    val biometricRequest: StateFlow<BiometricRequest?> = _biometricRequest.asStateFlow()

    private val _pairStep = MutableStateFlow<PairStep?>(null)

    private var pendingDisplayName: String? = null

    init {
        viewModelScope.launch {
            val identity = dao.getIdentityOnce()
            if (identity != null && keyManager.hasKey()) {
                enterMainState(identity)
            } else {
                _uiState.value = UiState.Setup()
            }
        }
    }

    private fun enterMainState(identity: Identity) {
        viewModelScope.launch {
            val fingerprint = keyManager.getFingerprint() ?: ""

            // Start the TOTP tick loop
            launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    val remaining = TotpGenerator.secondsRemaining(now)

                    val state = _uiState.value
                    if (state is UiState.Main) {
                        // Regenerate codes every tick
                        val contacts = state.contacts.map { cwc ->
                            val secret = HexCodec.hexToBytes(cwc.contact.totpSecret)
                            cwc.copy(code = TotpGenerator.generateCode(secret, now))
                        }
                        _uiState.value = state.copy(
                            contacts = contacts,
                            secondsRemaining = remaining,
                            pairStep = _pairStep.value
                        )
                    }

                    delay(1000)
                }
            }

            // Observe contacts and pair step together
            dao.getAllContacts().combine(_pairStep) { contacts, pairStep ->
                val now = System.currentTimeMillis()
                val withCodes = contacts.map { contact ->
                    val secret = HexCodec.hexToBytes(contact.totpSecret)
                    ContactWithCode(
                        contact = contact,
                        code = TotpGenerator.generateCode(secret, now)
                    )
                }
                val remaining = TotpGenerator.secondsRemaining(now)
                UiState.Main(
                    identity = identity,
                    contacts = withCodes,
                    fingerprint = fingerprint,
                    secondsRemaining = remaining,
                    pairStep = pairStep
                )
            }.collect { mainState ->
                _uiState.value = mainState
            }
        }
    }

    fun updateDisplayName(name: String) {
        val state = _uiState.value
        if (state is UiState.Setup) {
            _uiState.value = state.copy(displayName = name)
        }
    }

    fun requestGenerateIdentity() {
        val state = _uiState.value
        if (state !is UiState.Setup) return
        if (state.displayName.isBlank()) {
            _uiState.value = state.copy(error = "Please enter a display name")
            return
        }

        _uiState.value = state.copy(isGenerating = true, error = null)
        pendingDisplayName = state.displayName.trim()

        try {
            // On API ≥ R the cipher is acquired up front and unlocked by the
            // prompt. On legacy we leave it null and acquire it post-auth in
            // onBiometricSuccess. See KeyManager.usesLegacyAuth() for the why.
            val cipher = if (keyManager.usesLegacyAuth()) null else keyManager.getEncryptionCipher()
            _biometricRequest.value = BiometricRequest(
                cipher = cipher,
                purpose = BiometricPurpose.Generate,
                subtitle = "Enter your device PIN or use biometrics to secure your identity key"
            )
        } catch (e: IllegalStateException) {
            Log.e("WhoAreWe", "No lock screen configured", e)
            _uiState.value = state.copy(
                isGenerating = false,
                error = "Please set up a screen lock (PIN, pattern, or fingerprint) in your device Settings to protect your identity."
            )
        } catch (e: Exception) {
            Log.e("WhoAreWe", "Failed to get cipher", e)
            _uiState.value = state.copy(
                isGenerating = false,
                error = e.message ?: "Failed to initialize"
            )
        }
    }

    fun onQrScanned(rawData: String) {
        // Deliberately do NOT log `rawData` — it's attacker-controlled and
        // carries the pre-validation display name, which could contain
        // embedded newlines + forged log-line prefixes (cwage/whoarewe#26).
        // The post-decode log below uses the sanitized name instead.
        Log.d("WhoAreWe", "onQrScanned")
        val payload = QrCodeUtils.decode(rawData)
        if (payload == null) {
            Log.d("WhoAreWe", "onQrScanned: invalid QR payload")
            val state = _uiState.value
            if (state is UiState.Main) {
                _uiState.value = state.copy(error = "Invalid QR code")
            }
            return
        }
        // Reject non-canonical / off-curve / identity Ed25519 public keys
        // *before* requesting a biometric unlock — otherwise a hostile QR
        // would cost the user an auth prompt just to fail inside
        // EcdhExchange.deriveSharedSecret. See cwage/whoarewe#21.
        if (!EcdhExchange.isValidEd25519PublicKey(payload.publicKey)) {
            Log.d("WhoAreWe", "onQrScanned: public key failed Ed25519 validation")
            val state = _uiState.value
            if (state is UiState.Main) {
                _uiState.value = state.copy(error = "Invalid QR code")
            }
            return
        }
        Log.d("WhoAreWe", "onQrScanned: decoded ${payload.displayName}")

        // Check if we already have this contact
        viewModelScope.launch {
            val existing = dao.getContactByPublicKey(HexCodec.bytesToHex(payload.publicKey))
            if (existing != null) {
                Log.d("WhoAreWe", "onQrScanned: contact already exists")
                val state = _uiState.value
                if (state is UiState.Main) {
                    _uiState.value = state.copy(
                        error = "${payload.displayName} is already in your contacts"
                    )
                }
                return@launch
            }

            Log.d("WhoAreWe", "onQrScanned: requesting biometric for ECDH")
            // Need biometric to decrypt our private key for ECDH. On API ≥ R
            // we init the cipher up front; on legacy we defer to post-auth.
            try {
                val cipher = if (keyManager.usesLegacyAuth()) null else keyManager.getDecryptionCipher()
                _biometricRequest.value = BiometricRequest(
                    cipher = cipher,
                    purpose = BiometricPurpose.AddContact(payload),
                    subtitle = "Authenticate to add ${payload.displayName}"
                )
            } catch (e: Exception) {
                Log.e("WhoAreWe", "Failed to get cipher for ECDH", e)
                val state = _uiState.value
                if (state is UiState.Main) {
                    _uiState.value = state.copy(error = e.message ?: "Authentication failed")
                }
            }
        }
    }

    fun onBiometricSuccess(cipher: Cipher?) {
        Log.d("WhoAreWe", "onBiometricSuccess")
        val request = _biometricRequest.value ?: return
        _biometricRequest.value = null

        viewModelScope.launch {
            try {
                // Legacy path: cipher is null because we deferred init until
                // after the prompt refreshed the auth window. Acquire it now,
                // inside that window — and on a background dispatcher, since
                // both getEncryptionCipher() and getDecryptionCipher() touch
                // AndroidKeyStore (and the latter reads the IV file from disk).
                val activeCipher: Cipher = cipher ?: try {
                    withContext(Dispatchers.IO) {
                        when (request.purpose) {
                            is BiometricPurpose.Generate -> keyManager.getEncryptionCipher()
                            is BiometricPurpose.AddContact -> keyManager.getDecryptionCipher()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WhoAreWe", "Post-auth cipher init failed", e)
                    onBiometricError(e.message ?: "Authentication succeeded but cipher init failed")
                    return@launch
                }

                when (val purpose = request.purpose) {
                    is BiometricPurpose.Generate -> {
                        val result = keyManager.generateKey(activeCipher)
                        result.fold(
                            onSuccess = {
                                val name = pendingDisplayName ?: "Me"
                                val pubKeyHex = keyManager.getPublicKeyHex() ?: ""
                                val identity = Identity(
                                    displayName = name,
                                    publicKey = pubKeyHex
                                )
                                dao.saveIdentity(identity)
                                pendingDisplayName = null
                                enterMainState(identity)
                            },
                            onFailure = { e ->
                                Log.e("WhoAreWe", "Key generation failed", e)
                                _uiState.value = UiState.Setup(
                                    displayName = pendingDisplayName ?: "",
                                    error = e.message ?: "Key generation failed"
                                )
                            }
                        )
                    }

                    is BiometricPurpose.AddContact -> {
                        val payload = purpose.payload
                        withContext(Dispatchers.IO) {
                            val ourPrivateKey = keyManager.decryptPrivateKey(activeCipher)
                            try {
                                val sharedSecret = EcdhExchange.deriveSharedSecret(
                                    ourPrivateKey,
                                    payload.publicKey
                                )
                                val pubKeyHex = HexCodec.bytesToHex(payload.publicKey)
                                val secretHex = HexCodec.bytesToHex(sharedSecret)
                                val contact = TrustedContact(
                                    displayName = payload.displayName,
                                    publicKey = pubKeyHex,
                                    totpSecret = secretHex
                                )
                                dao.insertContact(contact)
                                sharedSecret.fill(0)
                            } finally {
                                ourPrivateKey.fill(0)
                            }
                        }
                        // Advance wizard: show our QR so they can scan us
                        Log.d("WhoAreWe", "AddContact complete, advancing to ShowAfterScan")
                        _pairStep.value = PairStep.ShowAfterScan(payload.displayName)
                        Log.d("WhoAreWe", "pairStep is now: ${_pairStep.value}")
                    }
                }
            } catch (e: EcdhExchange.InvalidPublicKeyException) {
                // Defence in depth: onQrScanned already rejects invalid public
                // keys, so this should be unreachable. Surface as the same
                // snackbar string rather than a raw JCA message if it ever
                // fires. See cwage/whoarewe#21.
                Log.e("WhoAreWe", "ECDH rejected peer public key post-auth", e)
                onBiometricError("Invalid QR code")
            } catch (e: Exception) {
                Log.e("WhoAreWe", "Biometric operation failed", e)
                onBiometricError(e.message ?: "Operation failed")
            }
        }
    }

    fun startPairing() {
        _pairStep.value = PairStep.Choose
    }

    fun showMyCodeFirst() {
        _pairStep.value = PairStep.ShowFirst
    }

    fun readyToScan() {
        _pairStep.value = PairStep.ScanAfterShow
    }

    fun finishPairing() {
        _pairStep.value = null
    }

    fun clearError() {
        val state = _uiState.value
        if (state is UiState.Main) {
            _uiState.value = state.copy(error = null)
        }
    }

    fun onBiometricError(error: String) {
        _biometricRequest.value = null
        when (val state = _uiState.value) {
            is UiState.Setup -> _uiState.value = state.copy(isGenerating = false, error = error)
            is UiState.Main -> _uiState.value = state.copy(error = error)
            else -> {}
        }
    }

    fun onBiometricCancelled() {
        _biometricRequest.value = null
        when (val state = _uiState.value) {
            is UiState.Setup -> _uiState.value = state.copy(isGenerating = false)
            else -> {}
        }
    }

    /**
     * Debug-only: returns `(displayName, totpSecretHex)` pairs for every stored
     * contact. Used by `MainActivity.handleE2eIntent` for the e2e_dump_secrets
     * seam, which is how `scripts/e2e-pairing.sh` asserts cross-device shared
     * secret agreement. Goes through the existing dao so the DB is never
     * touched on the main thread for the first time from a debug intent.
     */
    suspend fun e2eDumpContactSecrets(): List<Pair<String, String>> {
        return dao.getAllContacts().first().map { it.displayName to it.totpSecret }
    }
}
