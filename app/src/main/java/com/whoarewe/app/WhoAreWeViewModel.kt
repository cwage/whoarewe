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
import com.whoarewe.app.data.NameMatcher
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

    /**
     * Replace an existing [TrustedContact] with a freshly-paired one. Emitted
     * only after the user explicitly picks "Replace" in the name-collision
     * dialog (cwage/whoarewe#33). The biometric unlock is needed for the same
     * ECDH decryption step as `AddContact`; the difference is on write, where
     * the DAO's atomic `replaceContact(oldContactId, ...)` swaps rows in one
     * transaction.
     */
    data class ReplaceContact(
        val oldContactId: Long,
        val payload: QrCodeUtils.QrPayload
    ) : BiometricPurpose()
}

/**
 * Two display names collided: the incoming QR payload shares a name with an
 * [existing] contact but carries a different public key. Surfaced through
 * [WhoAreWeViewModel.pendingNameCollision] so the UI can show the three-way
 * choice dialog (Replace / Add as second / Cancel). See cwage/whoarewe#33.
 */
data class PendingNameCollision(
    val existing: TrustedContact,
    val incoming: QrCodeUtils.QrPayload
)

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

    // Kept as its own StateFlow (rather than a field on UiState.Main) because
    // the combine(dao.getAllContacts(), _pairStep) block below rebuilds the
    // Main state from scratch on every contact/pairstep change, so a field
    // there would be wiped the instant any other state changed. See #33.
    private val _pendingNameCollision = MutableStateFlow<PendingNameCollision?>(null)
    val pendingNameCollision: StateFlow<PendingNameCollision?> = _pendingNameCollision.asStateFlow()

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
            val incomingPubKeyHex = HexCodec.bytesToHex(payload.publicKey)
            val existing = dao.getContactByPublicKey(incomingPubKeyHex)
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

            // Same-name-different-key check (cwage/whoarewe#33). We already
            // know the public key is novel (step above), so any contact whose
            // name matches must be a *different* cryptographic identity. Do
            // not proceed silently — surface the collision so the user can
            // explicitly choose Replace / Add-as-second / Cancel.
            //
            // More than one existing contact may legitimately share a display
            // name — that's precisely the "Add as a second" flow below. When
            // we hit that case we must *not* rely on `getAllContacts()` row
            // order: the query is `ORDER BY displayName ASC`, and SQLite's
            // tiebreaker for equal keys is implementation-defined, so
            // `firstOrNull` against that list would hand Replace an
            // effectively arbitrary target and could swap the wrong Alice.
            // Instead, collect every match and pick deterministically by the
            // lowest row id — i.e. the *oldest* matching contact — and log
            // loudly when there's more than one so the behaviour is audit-
            // able after the fact. A richer UX (let the user pick which one
            // to replace) would be a follow-up; this PR closes the "silent
            // impersonation" gap and this suffices for the deterministic
            // part. See Copilot round 1 on PR #37.
            val matchingNameCollisions = dao.getAllContacts().first().filter { row ->
                NameMatcher.matches(row.displayName, payload.displayName)
            }
            val nameCollision = matchingNameCollisions.minByOrNull { it.id }
            if (nameCollision != null) {
                if (matchingNameCollisions.size > 1) {
                    Log.w(
                        "WhoAreWe",
                        "onQrScanned: ${matchingNameCollisions.size} contacts match " +
                            "\"${payload.displayName}\"; targeting oldest id=${nameCollision.id} " +
                            "for Replace (other rows untouched)"
                    )
                } else {
                    Log.d("WhoAreWe", "onQrScanned: name collides with existing contact id=${nameCollision.id}")
                }
                _pendingNameCollision.value = PendingNameCollision(
                    existing = nameCollision,
                    incoming = payload
                )
                return@launch
            }

            Log.d("WhoAreWe", "onQrScanned: requesting biometric for ECDH")
            requestBiometricForContact(
                BiometricPurpose.AddContact(payload),
                "Authenticate to add ${payload.displayName}"
            )
        }
    }

    /**
     * Build and emit the biometric request for adding or replacing a contact.
     * Shared by the normal-add path in [onQrScanned], the Replace branch of
     * [confirmReplaceContact], and the Add-as-second branch of
     * [confirmAddAsSecondContact]. Handles the legacy-auth cipher-deferral
     * (see `KeyManager.usesLegacyAuth()`) identically in all three call sites.
     *
     * [subtitle] is passed fully-formed by the caller rather than built from
     * the purpose here — that keeps the helper purpose-agnostic and makes it
     * impossible for a future branch to fall through with the wrong verb
     * (Replace showing "Authenticate to add …" was the exact bug Copilot
     * caught on PR #37 round 1).
     */
    private fun requestBiometricForContact(purpose: BiometricPurpose, subtitle: String) {
        // Need biometric to decrypt our private key for ECDH. On API ≥ R
        // we init the cipher up front; on legacy we defer to post-auth.
        try {
            val cipher = if (keyManager.usesLegacyAuth()) null else keyManager.getDecryptionCipher()
            _biometricRequest.value = BiometricRequest(
                cipher = cipher,
                purpose = purpose,
                subtitle = subtitle
            )
        } catch (e: Exception) {
            Log.e("WhoAreWe", "Failed to get cipher for ECDH", e)
            val state = _uiState.value
            if (state is UiState.Main) {
                _uiState.value = state.copy(error = e.message ?: "Authentication failed")
            }
        }
    }

    /**
     * User picked "Replace existing contact" in the name-collision dialog.
     * Clears the pending state and kicks off the same biometric flow as
     * a normal add, but with [BiometricPurpose.ReplaceContact] so that
     * [onBiometricSuccess] knows to swap rows atomically in the DAO.
     */
    fun confirmReplaceContact() {
        val pending = _pendingNameCollision.value ?: return
        _pendingNameCollision.value = null
        requestBiometricForContact(
            BiometricPurpose.ReplaceContact(
                oldContactId = pending.existing.id,
                payload = pending.incoming
            ),
            "Authenticate to replace ${pending.existing.displayName}"
        )
    }

    /**
     * User picked "Add as a second <name>" in the name-collision dialog.
     * Equivalent to the normal add path — intentionally preserved as a
     * long-tail option (two friends named Chris, etc.).
     */
    fun confirmAddAsSecondContact() {
        val pending = _pendingNameCollision.value ?: return
        _pendingNameCollision.value = null
        requestBiometricForContact(
            BiometricPurpose.AddContact(pending.incoming),
            "Authenticate to add a second ${pending.existing.displayName}"
        )
    }

    /** User dismissed the name-collision dialog; drop the pending payload. */
    fun cancelNameCollision() {
        _pendingNameCollision.value = null
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
                            // Replace uses the same private-key decryption
                            // cipher as AddContact — the only divergence
                            // is at the DB-write step inside
                            // `persistPairedContact`.
                            is BiometricPurpose.ReplaceContact -> keyManager.getDecryptionCipher()
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
                        persistPairedContact(purpose.payload, activeCipher, replaceId = null)
                    }

                    is BiometricPurpose.ReplaceContact -> {
                        persistPairedContact(
                            purpose.payload,
                            activeCipher,
                            replaceId = purpose.oldContactId
                        )
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

    /**
     * Shared tail of the Add and Replace paths in [onBiometricSuccess].
     * Derives the ECDH shared secret, writes the contact, and advances the
     * pair wizard. When [replaceId] is null this inserts a new row; when
     * non-null it calls the DAO's atomic `replaceContact` so the swap happens
     * inside a single Room transaction (see cwage/whoarewe#33).
     *
     * On the Replace path we carry the **existing row's `displayName` and
     * `notes`** onto the replacement row. The user's view of the contact
     * has continuity — "Alice is still Alice, she just has a new key" —
     * so any annotation they had ("my sister") must survive and the label
     * they're used to seeing must not flip casing just because the
     * incoming QR was typed differently. If the existing row disappeared
     * between the collision dialog and the user confirming Replace (stale
     * id), we fall back to the incoming payload's name and drop notes —
     * the same shape the ContactDao's stale-id defensive test pins.
     * See Copilot round 1 on PR #37.
     */
    private suspend fun persistPairedContact(
        payload: QrCodeUtils.QrPayload,
        activeCipher: Cipher,
        replaceId: Long?
    ) {
        withContext(Dispatchers.IO) {
            // Fetch the existing row up front (if any) so we can preserve
            // its user-owned fields on the replacement. The private key
            // decryption and ECDH derivation still happen *after* this
            // lookup — if the row is gone, we just proceed as an insert-
            // shaped write, matching the stale-id fallback policy.
            val existingRow = replaceId?.let { dao.getContactById(it) }

            val ourPrivateKey = keyManager.decryptPrivateKey(activeCipher)
            try {
                val sharedSecret = EcdhExchange.deriveSharedSecret(
                    ourPrivateKey,
                    payload.publicKey
                )
                val pubKeyHex = HexCodec.bytesToHex(payload.publicKey)
                val secretHex = HexCodec.bytesToHex(sharedSecret)
                val contact = TrustedContact(
                    displayName = existingRow?.displayName ?: payload.displayName,
                    publicKey = pubKeyHex,
                    totpSecret = secretHex,
                    notes = existingRow?.notes
                )
                if (replaceId != null) {
                    dao.replaceContact(replaceId, contact)
                } else {
                    dao.insertContact(contact)
                }
                sharedSecret.fill(0)
            } finally {
                ourPrivateKey.fill(0)
            }
        }
        // Advance wizard: show our QR so they can scan us
        val tag = if (replaceId != null) "ReplaceContact" else "AddContact"
        Log.d("WhoAreWe", "$tag complete, advancing to ShowAfterScan")
        _pairStep.value = PairStep.ShowAfterScan(payload.displayName)
        Log.d("WhoAreWe", "pairStep is now: ${_pairStep.value}")
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
