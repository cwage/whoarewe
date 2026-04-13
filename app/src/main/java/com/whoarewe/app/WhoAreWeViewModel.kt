package com.whoarewe.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.whoarewe.app.crypto.EcdhExchange
import com.whoarewe.app.crypto.HexCodec
import com.whoarewe.app.crypto.KeyManager
import com.whoarewe.app.crypto.QrCodeUtils
import com.whoarewe.app.crypto.TotpGenerator
import com.whoarewe.app.crypto.TotpSecretCodec
import com.whoarewe.app.ui.screens.PairStep
import com.whoarewe.app.data.AppDatabase
import com.whoarewe.app.data.Identity
import com.whoarewe.app.data.NameMatcher
import com.whoarewe.app.data.TrustedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Unlock the app at cold-start or after a lifecycle-driven re-lock.
     * The authenticated cipher is used to decrypt the biometric-wrapped
     * identity blob, whose DEK half is stuffed into the VM's in-memory
     * cache so the TOTP tick loop can decrypt every per-contact shared
     * secret without further Keystore round-trips. See cwage/whoarewe#32.
     */
    data object Unlock : BiometricPurpose()
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

    /**
     * Cold-start (or post-background) state where the app knows an identity
     * exists on disk but the TOTP DEK has not yet been unwrapped. The
     * biometric prompt auto-fires on entry; the user can re-tap Unlock if
     * they cancel it. See cwage/whoarewe#32.
     */
    data class Locked(val error: String? = null) : UiState()

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

    /**
     * The per-identity DEK, held in JVM heap for the lifetime of the unlock
     * session. Populated either at identity generation time (returned from
     * [KeyManager.generateKey]) or at unlock time (extracted from the
     * biometric-authenticated identity blob). Zeroed on [onCleared]. See
     * cwage/whoarewe#32.
     */
    private var totpDek: ByteArray? = null

    /**
     * In-memory plaintext cache of decrypted TOTP secrets, keyed by contact
     * row id. Populated on entry to Main state and grown on each successful
     * pair. The TOTP tick loop reads from this map instead of hitting the
     * DAO-backed ciphertext every tick — the hot path never touches the
     * Keystore or the on-disk encrypted column after unlock.
     *
     * Backed by [ConcurrentHashMap] because writes happen from several
     * dispatchers: the Main tick loop only reads, but [ensureCacheContains]
     * writes from [Dispatchers.Default] (AES-GCM off the UI thread — see
     * Copilot round 1 on PR #38) and [persistPairedContact] writes from
     * [Dispatchers.IO] (during the DAO-backed pair transaction). A plain
     * [HashMap] would race in that setup.
     */
    private val totpSecretCache = ConcurrentHashMap<Long, ByteArray>()

    /**
     * Ids whose ciphertext has failed to decrypt under the current DEK.
     * Tracked separately from the success cache so [ensureCacheContains]
     * can skip them on subsequent DAO emissions instead of re-running
     * AES-GCM and re-logging the warning every tick. Cleared by [lock]
     * (along with the cache itself) and pruned to the live contact set
     * on every call so deleted rows don't leak ids forever. See
     * Copilot round 2 on PR #38.
     */
    private val failedDecryptIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Monotonically incremented on every [lock] call. [ensureCacheContains]
     * captures the value at entry and re-checks it before writing decrypted
     * plaintexts back into [totpSecretCache], so an in-flight Default-
     * dispatched decrypt loop that races a [lock] call cannot reintroduce
     * plaintext into a cache that was meant to be cleared. Together with
     * the per-iteration [ensureActive] check, this gives the re-lock path
     * a hard guarantee against stale work resurrecting cleared secrets.
     * See Copilot round 2 on PR #38.
     */
    private var unlockEpoch: Long = 0L

    /**
     * Handle for the coroutine that runs [enterMainState]'s contact-flow
     * collector + tick loop. Cancelled on re-lock (e.g. on background) so
     * the background observer and the ticker don't keep running against a
     * cleared cache. See cwage/whoarewe#32.
     */
    private var mainStateJob: Job? = null

    /**
     * Process-level lifecycle observer that re-locks the app whenever the
     * user backgrounds it. Registered in [init] against the app-wide
     * [ProcessLifecycleOwner] and removed in [onCleared]. `onStop` fires
     * when the last Activity goes non-visible — i.e. user hit Home,
     * switched apps, or locked the device. We lock eagerly there rather
     * than waiting for `onDestroy`, so a backgrounded-but-alive VM still
     * drops its secrets. `onStart` re-fires the unlock prompt if we left
     * Main for Locked during the stop.
     *
     * See cwage/whoarewe#32.
     */
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // Only re-lock if we actually had a session to lose. Setup /
            // Loading / already-Locked states have nothing to clear, and
            // locking from them would incorrectly re-fire the unlock
            // biometric on return from a brief background.
            //
            // Crucially, skip re-lock while a biometric prompt is
            // in-flight (`_biometricRequest.value != null`): on API 28
            // legacy auth, the BiometricPrompt + DEVICE_CREDENTIAL path
            // launches the system credential bouncer as a *separate*
            // activity, which stops MainActivity and fires this callback
            // mid-prompt. Without this guard, lock() would then clear
            // the pending biometric request, and when the user enters
            // their PIN and BiometricPrompt finally fires our
            // onBiometricSuccess callback, `request = _biometricRequest.value
            // ?: return` early-returns and the pair flow dies silently.
            // On API 33 the prompt is an in-process dialog that does not
            // stop the activity, so the race is invisible there — but
            // the guard is the correct fix across all APIs.
            if (_uiState.value is UiState.Main && _biometricRequest.value == null) {
                Log.d("WhoAreWe", "ProcessLifecycle onStop: re-locking")
                lock()
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            // If we got re-locked during the background, auto-fire the
            // unlock prompt the same way the cold-start path does.
            if (_uiState.value is UiState.Locked && _biometricRequest.value == null) {
                Log.d("WhoAreWe", "ProcessLifecycle onStart: requesting unlock")
                requestUnlock()
            }
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        viewModelScope.launch {
            val identity = dao.getIdentityOnce()
            if (identity == null || !keyManager.hasKey()) {
                _uiState.value = UiState.Setup()
                return@launch
            }
            if (BuildConfig.DEBUG && keyManager.hasSentinelKey()) {
                // Maestro e2e bootstrap — identity files are sentinel values
                // that would fail any real decryption. Skip the unlock gate
                // and go straight to Main with an empty cache; any test that
                // tries to actually pair through this seam already uses the
                // two-emulator harness that runs the real biometric path.
                // See KeyManager.hasSentinelKey().
                //
                // Double-gated: BuildConfig.DEBUG first, so a release build
                // cannot possibly reach this path even with a crafted
                // on-disk blob that happens to match the sentinel shape.
                // See Copilot round 1 on PR #38.
                Log.d("WhoAreWe", "init: sentinel identity detected, skipping unlock")
                enterMainState(identity)
                return@launch
            }
            // Normal path: requestUnlock() sets biometricRequest and then
            // transitions state to Locked, in that order, so collectors
            // watching `uiState.first { it is Locked }` are guaranteed to
            // see a non-null biometricRequest at wake-up time. See
            // requestUnlock()'s KDoc for the race explanation.
            requestUnlock()
        }
    }

    /**
     * Build and emit the biometric request for the unlock gate. On API ≥ R
     * the decryption cipher is pre-initialized so the prompt unlocks it
     * via CryptoObject; on legacy we defer to post-auth. Identical shape
     * to [requestBiometricForContact] but uses [BiometricPurpose.Unlock].
     *
     * Ordering is load-bearing: the biometric request is emitted *before*
     * the state transitions to [UiState.Locked], so any observer watching
     * `uiState.first { it is Locked }` on a separate dispatcher is
     * guaranteed to see a non-null [biometricRequest] at the moment it
     * wakes up. Doing it the other way round (set Locked first, then set
     * biometric request) races: the collector can wake up on the state
     * change and read `biometricRequest.value` *before* the second
     * assignment lands, observing a spurious "Locked with nothing
     * pending" state. That race is how [WhoAreWeViewModelLockTest]'s
     * `init_withRealIdentityFiles_landsOnLockedAndRequestsUnlock` flake
     * manifested on API 28 in CI while passing on API 33 (timing
     * difference between dispatchers, not a semantic difference).
     */
    private fun requestUnlock() {
        val request = try {
            val cipher = if (keyManager.usesLegacyAuth()) null else keyManager.getDecryptionCipher()
            BiometricRequest(
                cipher = cipher,
                purpose = BiometricPurpose.Unlock,
                subtitle = "Unlock WhoAreWe"
            )
        } catch (e: Exception) {
            Log.e("WhoAreWe", "Failed to get cipher for unlock", e)
            _uiState.value = UiState.Locked(error = e.message ?: "Unlock failed")
            return
        }
        _biometricRequest.value = request
        // Clears any prior Locked(error) on retry — `Locked() != Locked(error)`
        // so StateFlow emits. On first call from init the current state is
        // Loading, so this is the Loading→Locked transition the UI is
        // waiting for. In all cases, by the time this line runs the
        // biometric request is already set above, so observers see a
        // coherent (Locked, biometricRequest) pair.
        _uiState.value = UiState.Locked()
    }

    /**
     * Re-emits the unlock biometric request. The Locked screen's retry
     * button calls this after the user cancels the auto-fired prompt.
     * [requestUnlock] itself re-sets the Locked state (clearing any
     * previous error), so no extra state assignment needed here.
     */
    fun retryUnlock() {
        if (_uiState.value is UiState.Locked) {
            requestUnlock()
        }
    }

    private fun enterMainState(identity: Identity) {
        // Cancel any prior collector/ticker — re-entering Main from the
        // Locked flow (either a cold start or a post-background re-lock)
        // must not leave the previous job reading a stale cache.
        mainStateJob?.cancel()
        mainStateJob = viewModelScope.launch {
            val fingerprint = keyManager.getFingerprint() ?: ""

            // Start the TOTP tick loop
            launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    val remaining = TotpGenerator.secondsRemaining(now)

                    val state = _uiState.value
                    if (state is UiState.Main) {
                        // Regenerate codes every tick. The tick loop reads
                        // the plaintext secret from the in-memory cache
                        // instead of the Room ciphertext — the encrypted
                        // column is only touched at unlock/pair time.
                        val contacts = state.contacts.map { cwc ->
                            val secret = totpSecretCache[cwc.contact.id]
                            val code = if (secret != null) {
                                TotpGenerator.generateCode(secret, now)
                            } else {
                                // Row showed up in the contact list before
                                // its decrypted secret landed in the cache
                                // — can happen in a pair's tight window
                                // where the DAO insert lands before the
                                // cache populate. Render an empty code and
                                // let the next tick pick it up.
                                ""
                            }
                            cwc.copy(code = code)
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

            // Observe contacts and pair step together. For every row we
            // see, populate the plaintext cache from the encrypted column
            // on demand — this means the unlock path doesn't need a
            // separate "decrypt all rows" pass, and post-upgrade rows get
            // folded into the cache on the first emission after unlock.
            dao.getAllContacts().combine(_pairStep) { contacts, pairStep ->
                val now = System.currentTimeMillis()
                ensureCacheContains(contacts)
                val withCodes = contacts.map { contact ->
                    val secret = totpSecretCache[contact.id]
                    val code = if (secret != null) {
                        TotpGenerator.generateCode(secret, now)
                    } else {
                        ""
                    }
                    ContactWithCode(contact = contact, code = code)
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

    /**
     * Decrypt any rows from [contacts] whose plaintext we don't already
     * hold in [totpSecretCache]. Silently skips rows if the DEK hasn't
     * been unwrapped yet (sentinel e2e path) — those rows just render
     * with an empty code until the cache catches up.
     *
     * The AES-GCM decryption loop runs on [Dispatchers.Default] rather
     * than the caller's (typically Main-immediate) dispatcher so a
     * contact list with a few dozen rows doesn't jank the UI thread on
     * every unlock or DAO emission. [totpSecretCache] is a
     * [ConcurrentHashMap] (see its field KDoc), so concurrent reads /
     * writes across dispatchers are safe.
     *
     * Cancellation + re-lock race protection (Copilot round 2 on PR #38):
     *   - [unlockEpoch] is captured at entry and re-checked on every loop
     *     iteration AND once more before writing the collected plaintexts
     *     back into the cache, so a [lock] call that races this loop
     *     cannot resurrect plaintext into a cache that was meant to be
     *     cleared. The plaintexts are computed into a *local* map first
     *     and only merged into the live cache after the post-loop epoch
     *     check, which keeps the put-then-clear-but-too-late race
     *     impossible by construction.
     *   - [ensureActive] is called before each iteration so the loop
     *     becomes a coroutine cancellation point — `mainStateJob.cancel()`
     *     in [lock] propagates here and the loop bails out promptly
     *     without doing more crypto work.
     *
     * Failure handling (Copilot round 2 on PR #38): rows whose ciphertext
     * fails to decrypt are recorded in [failedDecryptIds] and skipped on
     * future calls until [lock] clears the set, so a single corrupted /
     * tampered row can't generate repeated AES-GCM work + log spam on
     * every DAO emission. The set is also pruned to the current contact
     * id list at the top of each call so deleted rows don't leak.
     */
    private suspend fun ensureCacheContains(contacts: List<TrustedContact>) {
        val dek = totpDek ?: return
        val epochAtEntry = unlockEpoch
        // Drop failure markers for rows that no longer exist in the
        // contact list (deleted contacts) so the set doesn't grow forever.
        failedDecryptIds.retainAll(contacts.map { it.id }.toSet())

        val newEntries = withContext(Dispatchers.Default) {
            val result = mutableMapOf<Long, ByteArray>()
            for (contact in contacts) {
                ensureActive()
                if (epochAtEntry != unlockEpoch) {
                    // Re-lock raced us mid-loop. Zero anything we already
                    // decrypted in this batch and bail without writing.
                    for (s in result.values) s.fill(0)
                    return@withContext null
                }
                if (totpSecretCache.containsKey(contact.id)) continue
                if (failedDecryptIds.contains(contact.id)) continue
                try {
                    val plaintext = TotpSecretCodec.decrypt(
                        TotpSecretCodec.EncryptedSecret(
                            ciphertext = contact.encryptedTotpSecret,
                            iv = contact.totpSecretIv
                        ),
                        dek
                    )
                    result[contact.id] = plaintext
                } catch (e: Exception) {
                    // Row is unreadable with our DEK — log once and
                    // record the failure so we don't re-attempt on
                    // every subsequent DAO emission.
                    Log.w("WhoAreWe", "Failed to decrypt totpSecret for id=${contact.id}", e)
                    failedDecryptIds.add(contact.id)
                }
            }
            result
        } ?: return
        // Back on the caller's dispatcher (typically Main). One more
        // epoch check before publishing — if `lock()` ran while the
        // Default-dispatched block was returning, drop the work rather
        // than reintroducing plaintext into a now-cleared cache.
        if (epochAtEntry != unlockEpoch) {
            for (s in newEntries.values) s.fill(0)
            return
        }
        totpSecretCache.putAll(newEntries)
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
                            // Unlock also wants a decryption cipher — same
                            // keystore key, same blob, just used to extract
                            // the DEK half instead of the private-key half.
                            is BiometricPurpose.Unlock -> keyManager.getDecryptionCipher()
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
                            onSuccess = { generated ->
                                // Seed the TOTP DEK cache from the freshly
                                // generated identity so the post-setup
                                // transition to Main doesn't need a second
                                // biometric unlock.
                                totpDek = generated.dek
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

                    is BiometricPurpose.Unlock -> {
                        val decrypted = withContext(Dispatchers.IO) {
                            keyManager.decryptIdentityBlob(activeCipher)
                        }
                        // At unlock, we only care about the DEK half. The
                        // private key copy is zeroed immediately — it'll
                        // be re-fetched via its own biometric at pair time.
                        decrypted.privateKey.fill(0)
                        totpDek = decrypted.dek
                        val identity = dao.getIdentityOnce()
                        if (identity == null) {
                            // Shouldn't happen — init only enters Locked if
                            // the identity row was present. Treat as a bug
                            // signal rather than silently dropping to Setup.
                            Log.e("WhoAreWe", "Unlock succeeded but identity row vanished")
                            _uiState.value = UiState.Setup()
                        } else {
                            enterMainState(identity)
                        }
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
        // The wizard-advance step below uses the same effective display name
        // that actually got written to the DAO, not the raw payload name.
        // On Replace we deliberately preserve the existing row's name so
        // that a hostile QR cannot silently reformat the label the user
        // sees — and the pair-wizard confirmation screen is part of that
        // "label the user sees", so it has to be sourced from the same
        // variable as the stored row. Captured as the return value of
        // `withContext` so it's visible at the wizard-advance site below.
        // See Copilot round 2 on PR #37.
        val dek = totpDek
        if (dek == null) {
            // Shouldn't happen — persistPairedContact is only reachable via
            // the Add/Replace biometric flows, which are only reachable from
            // Main, which requires a populated DEK. Bail with a user-visible
            // error rather than NPEing so a future refactor that breaks the
            // invariant surfaces loudly.
            Log.e("WhoAreWe", "persistPairedContact: no DEK cached")
            onBiometricError("Session expired — please unlock again")
            return
        }
        // Snapshot the unlock epoch so we can detect a `lock()` racing
        // against this pair flow (e.g. user backgrounds the app between
        // biometric success and the IO block completing). Same shape as
        // ensureCacheContains' epoch check — see Copilot round 2 on
        // PR #38.
        val epochAtEntry = unlockEpoch
        val pairResult = withContext(Dispatchers.IO) {
            // Fetch the existing row up front (if any) so we can preserve
            // its user-owned fields on the replacement. The private key
            // decryption and ECDH derivation still happen *after* this
            // lookup — if the row is gone, we just proceed as an insert-
            // shaped write, matching the stale-id fallback policy.
            val existingRow = replaceId?.let { dao.getContactById(it) }

            val decrypted = keyManager.decryptIdentityBlob(activeCipher)
            // At pair time we already have the DEK cached from unlock —
            // zero the fresh copy from the blob immediately rather than
            // holding two copies in heap.
            decrypted.dek.fill(0)
            val ourPrivateKey = decrypted.privateKey
            try {
                val sharedSecret = EcdhExchange.deriveSharedSecret(
                    ourPrivateKey,
                    payload.publicKey
                )
                try {
                    val encryptedSecret = TotpSecretCodec.encrypt(sharedSecret, dek)
                    val pubKeyHex = HexCodec.bytesToHex(payload.publicKey)
                    val storedName = existingRow?.displayName ?: payload.displayName
                    val contact = TrustedContact(
                        displayName = storedName,
                        publicKey = pubKeyHex,
                        encryptedTotpSecret = encryptedSecret.ciphertext,
                        totpSecretIv = encryptedSecret.iv,
                        notes = existingRow?.notes
                    )
                    val newRowId = if (replaceId != null) {
                        // replaceContact's @Transaction deletes the old
                        // row by id and inserts the new one — we need the
                        // new id to populate the cache below. Query by
                        // public key immediately after the swap, since
                        // the incoming pubkey is guaranteed-unique by the
                        // earlier dedup step in onQrScanned.
                        dao.replaceContact(replaceId, contact)
                        dao.getContactByPublicKey(pubKeyHex)?.id
                            ?: error("Replaced row vanished between write and lookup")
                    } else {
                        dao.insertContact(contact)
                    }
                    // Hand the plaintext back to the caller (on Main) for
                    // the cache write — see the post-withContext block
                    // for the epoch check. `copyOf` lets the finally
                    // below zero the transient `sharedSecret` buffer
                    // without disturbing the returned copy.
                    Triple(storedName, newRowId, sharedSecret.copyOf())
                } finally {
                    sharedSecret.fill(0)
                }
            } finally {
                ourPrivateKey.fill(0)
            }
        }
        val (effectiveDisplayName, newRowId, plaintextCopy) = pairResult

        // Cache mutations happen on the caller's dispatcher (typically
        // Main) AFTER the IO block completes. The epoch check prevents
        // a `lock()` that raced this pair flow from being undone — if
        // the epoch advanced, drop the plaintext on the floor and skip
        // the wizard advance. The DAO row was already written, but the
        // ciphertext on disk is encrypted under the DEK that was in
        // effect at pair time, so it's safe to leave there.
        if (epochAtEntry != unlockEpoch) {
            plaintextCopy.fill(0)
            Log.w("WhoAreWe", "persistPairedContact: lock() raced the pair flow, dropping cache update")
            return
        }
        if (replaceId != null) {
            totpSecretCache.remove(replaceId)
        }
        totpSecretCache[newRowId] = plaintextCopy

        val tag = if (replaceId != null) "ReplaceContact" else "AddContact"
        Log.d("WhoAreWe", "$tag complete, advancing to Done")
        _pairStep.value = PairStep.Done(effectiveDisplayName)
        Log.d("WhoAreWe", "pairStep is now: ${_pairStep.value}")
    }

    fun startPairing() {
        _pairStep.value = PairStep.Scan
    }

    fun showQr() {
        _pairStep.value = PairStep.ShowQr
    }

    fun finishPairing() {
        _pairStep.value = null
    }

    fun deleteContact(contactId: Long) {
        viewModelScope.launch {
            dao.deleteContactById(contactId)
            totpSecretCache.remove(contactId)?.fill(0)
            failedDecryptIds.remove(contactId)
        }
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
            is UiState.Locked -> _uiState.value = state.copy(error = error)
            else -> {}
        }
    }

    fun onBiometricCancelled() {
        _biometricRequest.value = null
        when (val state = _uiState.value) {
            is UiState.Setup -> _uiState.value = state.copy(isGenerating = false)
            // Locked stays Locked on cancel — the user can hit the retry
            // button on the Locked screen to re-fire the unlock prompt.
            else -> {}
        }
    }

    /**
     * Drop the app back to [UiState.Locked] and zero all in-memory secret
     * material. Called from [onCleared] and from the ProcessLifecycleOwner
     * background observer (cwage/whoarewe#32). Cancels the main-state
     * collector job so its next emission doesn't race the cache clear.
     *
     * Safe to call when already Locked (no-op for state) or in Setup
     * (no-op, the DEK hasn't been unwrapped yet).
     *
     * Increments [unlockEpoch] before clearing the cache so any in-flight
     * [ensureCacheContains] decrypt loop running on [Dispatchers.Default]
     * sees the epoch advance on its next iteration and bails out without
     * writing back. See Copilot round 2 on PR #38.
     */
    fun lock() {
        mainStateJob?.cancel()
        mainStateJob = null
        // Advance the epoch *before* zeroing/clearing so any in-flight
        // ensureCacheContains loop racing this call sees the bumped
        // value on its next iteration check (or its post-loop check)
        // and discards its work.
        unlockEpoch++
        totpDek?.fill(0)
        totpDek = null
        for (secret in totpSecretCache.values) {
            secret.fill(0)
        }
        totpSecretCache.clear()
        failedDecryptIds.clear()
        _biometricRequest.value = null
        _pairStep.value = null
        _pendingNameCollision.value = null
        // Only transition to Locked from the in-session states. If we're
        // already in Setup (no identity yet) we stay there.
        when (_uiState.value) {
            is UiState.Main, is UiState.Locked, UiState.Loading -> {
                _uiState.value = UiState.Locked()
            }
            else -> {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        lock()
    }

    /**
     * Debug-only: returns `(displayName, totpSecretHex)` pairs for every
     * contact currently in the plaintext cache. Used by
     * `MainActivity.handleE2eIntent` for the e2e_dump_secrets seam, which
     * is how `scripts/e2e-pairing.sh` asserts cross-device shared secret
     * agreement. Reads from the in-memory cache rather than the DAO
     * because the `totpSecret` column on disk is now ciphertext
     * (cwage/whoarewe#32) — the e2e harness compares plaintext hex, so
     * the seam has to surface plaintext.
     *
     * Calls [ensureCacheContains] directly rather than relying on the
     * separate `enterMainState` collector having already populated the
     * cache. Those are two independent flow collectors and racing one
     * against the other would make this seam non-deterministic — e.g.
     * return fewer rows than the DB has during a pair flow. See
     * Copilot round 1 on PR #38.
     */
    suspend fun e2eDumpContactSecrets(): List<Pair<String, String>> {
        val contacts = dao.getAllContacts().first()
        ensureCacheContains(contacts)
        return contacts.mapNotNull { contact ->
            val plaintext = totpSecretCache[contact.id] ?: return@mapNotNull null
            contact.displayName to HexCodec.bytesToHex(plaintext)
        }
    }
}
