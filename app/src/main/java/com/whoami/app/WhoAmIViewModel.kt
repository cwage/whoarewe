package com.whoami.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whoami.app.crypto.KeyManager
import com.whoami.app.crypto.TotpGenerator
import com.whoami.app.data.AppDatabase
import com.whoami.app.data.Identity
import com.whoami.app.data.TrustedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

sealed class BiometricPurpose {
    data object Generate : BiometricPurpose()
}

data class BiometricRequest(
    val cipher: Cipher,
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
        val secondsRemaining: Int = 30
    ) : UiState()
}

class WhoAmIViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val dao = db.contactDao()
    val keyManager = KeyManager(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _biometricRequest = MutableStateFlow<BiometricRequest?>(null)
    val biometricRequest: StateFlow<BiometricRequest?> = _biometricRequest.asStateFlow()

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
                        _uiState.value = state.copy(secondsRemaining = remaining)
                    }

                    // Sleep until next second
                    delay(1000)
                }
            }

            // Observe contacts and generate codes
            dao.getAllContacts().collect { contacts ->
                val now = System.currentTimeMillis()
                val withCodes = contacts.map { contact ->
                    // TODO: derive TOTP secret from ECDH shared secret
                    // For now, use a placeholder — codes will be wired up
                    // when QR exchange + ECDH is implemented
                    ContactWithCode(
                        contact = contact,
                        code = "------"
                    )
                }
                val remaining = TotpGenerator.secondsRemaining(now)
                _uiState.value = UiState.Main(
                    identity = identity,
                    contacts = withCodes,
                    fingerprint = fingerprint,
                    secondsRemaining = remaining
                )
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
            val cipher = keyManager.getEncryptionCipher()
            _biometricRequest.value = BiometricRequest(
                cipher = cipher,
                purpose = BiometricPurpose.Generate,
                subtitle = "Enter your device PIN or use biometrics to secure your identity key"
            )
        } catch (e: IllegalStateException) {
            Log.e("WhoAmI", "No lock screen configured", e)
            _uiState.value = state.copy(
                isGenerating = false,
                error = "Please set up a screen lock (PIN, pattern, or fingerprint) in your device Settings to protect your identity."
            )
        } catch (e: Exception) {
            Log.e("WhoAmI", "Failed to get cipher", e)
            _uiState.value = state.copy(
                isGenerating = false,
                error = e.message ?: "Failed to initialize"
            )
        }
    }

    fun onBiometricSuccess(cipher: Cipher) {
        val request = _biometricRequest.value ?: return
        _biometricRequest.value = null

        viewModelScope.launch {
            try {
                when (request.purpose) {
                    is BiometricPurpose.Generate -> {
                        val result = keyManager.generateKey(cipher)
                        result.fold(
                            onSuccess = { fingerprint ->
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
                                Log.e("WhoAmI", "Key generation failed", e)
                                _uiState.value = UiState.Setup(
                                    displayName = pendingDisplayName ?: "",
                                    error = e.message ?: "Key generation failed"
                                )
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("WhoAmI", "Biometric operation failed", e)
                onBiometricError(e.message ?: "Operation failed")
            }
        }
    }

    fun onBiometricError(error: String) {
        _biometricRequest.value = null
        val state = _uiState.value
        if (state is UiState.Setup) {
            _uiState.value = state.copy(isGenerating = false, error = error)
        }
    }

    fun onBiometricCancelled() {
        _biometricRequest.value = null
        val state = _uiState.value
        if (state is UiState.Setup) {
            _uiState.value = state.copy(isGenerating = false)
        }
    }
}
