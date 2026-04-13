package com.whoarewe.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.whoarewe.app.crypto.HexCodec
import com.whoarewe.app.crypto.KeyManager
import com.whoarewe.app.crypto.QrCodeUtils
import com.whoarewe.app.crypto.QrDecoder
import com.whoarewe.app.data.AppDatabase
import com.whoarewe.app.data.Identity
import com.whoarewe.app.ui.screens.ContactListScreen
import com.whoarewe.app.ui.screens.LockedScreen
import com.whoarewe.app.ui.screens.NameCollisionDialog
import com.whoarewe.app.ui.screens.PairStep
import com.whoarewe.app.ui.screens.AddContactScreen
import com.whoarewe.app.ui.screens.QrDisplayScreen
import com.whoarewe.app.ui.screens.SetupScreen
import com.whoarewe.app.ui.theme.WhoAreWeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom

class MainActivity : FragmentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleE2eIntent(intent)
    }

    /**
     * Debug-only test seam used by `scripts/e2e-pairing.sh` and the Maestro
     * flows. Four extras are recognised:
     *   --ez e2e_dump_qr true            → log this device's QR payload to logcat tag "WhoAreWe-E2E"
     *   --es e2e_inject_qr <s>           → feed the string into the normal onQrScanned() pipeline,
     *                                      skipping the photo picker / camera scanner UI.
     *   --ez e2e_dump_secrets true       → log every stored TOTP shared secret as
     *                                      "SECRET_DUMP: <hex>=<displayName>". The hex is
     *                                      a fixed [0-9a-f]+ alphabet so it cannot contain
     *                                      the `=` delimiter, which makes the format safe
     *                                      even when display names contain `=`. The e2e
     *                                      test compares the hex directly across devices,
     *                                      which asserts the cryptographic invariant
     *                                      without dragging the displayed code through
     *                                      wall-clock TOTP window timing.
     *   --es e2e_create_identity <name>  → bootstrap an identity for the given display
     *                                      name without ever invoking BiometricPrompt.
     *                                      Used by the Maestro pair-wizard flow, which
     *                                      cannot drive the system credential bouncer.
     *                                      See cwage/whoarewe#11. Runs synchronously
     *                                      (runBlocking) so the vm sees the bootstrapped
     *                                      identity in its own init, no race.
     * Bypassed entirely on release builds.
     */
    private fun handleE2eIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return

        val createIdentityName = intent.getStringExtra("e2e_create_identity")
        if (createIdentityName != null) {
            intent.removeExtra("e2e_create_identity")
            // Run synchronously so the vm picks up the bootstrapped state when
            // its own init reads `dao.getIdentityOnce()` a moment later. The
            // alternative — lifecycleScope.launch — races the vm constructor
            // and is non-deterministic.
            runBlocking { bootstrapIdentityForE2e(createIdentityName) }
        }

        val inject = intent.getStringExtra("e2e_inject_qr")
        if (inject != null) {
            intent.removeExtra("e2e_inject_qr")
            val vm = ViewModelProvider(this)[WhoAreWeViewModel::class.java]
            Log.i("WhoAreWe-E2E", "Injecting QR via intent")
            vm.onQrScanned(inject)
        }

        if (intent.getBooleanExtra("e2e_dump_qr", false)) {
            intent.removeExtra("e2e_dump_qr")
            val vm = ViewModelProvider(this)[WhoAreWeViewModel::class.java]
            lifecycleScope.launch {
                val state = vm.uiState.first { it is UiState.Main } as UiState.Main
                val pubKey = vm.keyManager.getPublicKeyBytes()
                if (pubKey == null) {
                    Log.i("WhoAreWe-E2E", "QR_DUMP_FAIL: public key unavailable")
                } else {
                    val qr = QrCodeUtils.encode(state.identity.displayName, pubKey)
                    Log.i("WhoAreWe-E2E", "QR_DUMP: $qr")
                }
            }
        }

        if (intent.getBooleanExtra("e2e_dump_secrets", false)) {
            intent.removeExtra("e2e_dump_secrets")
            val vm = ViewModelProvider(this)[WhoAreWeViewModel::class.java]
            lifecycleScope.launch {
                val secrets = vm.e2eDumpContactSecrets()
                if (secrets.isEmpty()) {
                    Log.i("WhoAreWe-E2E", "SECRET_DUMP_FAIL: no contacts")
                } else {
                    for ((name, hex) in secrets) {
                        Log.i("WhoAreWe-E2E", "SECRET_DUMP: $hex=$name")
                    }
                }
            }
        }
    }

    /**
     * Bootstrap an identity for Maestro tests without going through the real
     * `BiometricPrompt` + Keystore flow. Generates a real Ed25519 keypair via
     * BouncyCastle, hands the public key to `KeyManager.e2eWriteIdentityFilesForTest`
     * (which writes a sentinel encrypted-key blob alongside the real public key
     * so `hasKey()` returns true), and inserts the Identity row into Room.
     *
     * The encrypted-key blob is *intentionally* unusable. The vm will transition
     * straight into the contact list state because both `dao.getIdentityOnce()`
     * and `keyManager.hasKey()` return non-null/true, but any code path that
     * tries to actually decrypt the private key will fail. Maestro tests that
     * use this seam (currently just `pair-wizard-navigation.yaml`) only ever
     * read the public key, so they're fine.
     *
     * Refuses to overwrite an existing identity, so an accidental invocation
     * on a real installation can't brick the user's keys.
     */
    private suspend fun bootstrapIdentityForE2e(displayName: String) {
        // Preserve the same display-name invariant the production Setup flow
        // enforces: trim whitespace, refuse blanks. The seam should not let
        // a Maestro test create an identity that the real UI couldn't.
        val trimmedName = displayName.trim()
        if (trimmedName.isBlank()) {
            Log.w("WhoAreWe-E2E", "e2e_create_identity refused: display name is blank")
            return
        }

        val keyManager = KeyManager(applicationContext)
        val dao = AppDatabase.getInstance(applicationContext).contactDao()

        if (keyManager.hasKey() || dao.getIdentityOnce() != null) {
            Log.w("WhoAreWe-E2E", "e2e_create_identity refused: identity already exists")
            return
        }

        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded

        keyManager.e2eWriteIdentityFilesForTest(publicKey)

        val identity = Identity(
            displayName = trimmedName,
            publicKey = HexCodec.bytesToHex(publicKey)
        )
        dao.saveIdentity(identity)

        Log.i("WhoAreWe-E2E", "Bootstrapped identity for '$trimmedName'")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Secure by default: set FLAG_SECURE before `setContent` so the very
        // first frame the window composites is already blocked from screen
        // capture, MediaProjection recorders, recents thumbnails, and cast
        // displays. The LaunchedEffect below only *clears* the flag for the
        // standalone QR display screen (ShowQr). Previously the flag was
        // only added from the LaunchedEffect, which left a composition-to-
        // effect window where a MediaProjection recorder capturing
        // continuously could sample frames of UiState.Main before the flag
        // was applied. See cwage/whoarewe#22 (Copilot review, round 1).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        handleE2eIntent(intent)
        setContent {
            WhoAreWeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: WhoAreWeViewModel = viewModel()
                    val uiState by viewModel.uiState.collectAsState()
                    val biometricRequest by viewModel.biometricRequest.collectAsState()
                    val pendingNameCollision by viewModel.pendingNameCollision.collectAsState()

                    // The window is FLAG_SECURE by default (set in onCreate).
                    // We only *clear* the flag while the standalone QR display
                    // screen is active (ShowQr) — the user's own QR must
                    // remain capturable because manual pairing relies on
                    // `adb exec-out screencap -p` working on it (see
                    // scripts/manual-pair.sh's transfer_qr helper). Every
                    // other state — Loading, Setup, and the rest of
                    // UiState.Main — stays secure.
                    //
                    // FLAG_SECURE is enforced by SurfaceFlinger so it blocks
                    // the screenshot button, MediaProjection recorders, recents
                    // thumbnails, and cast/mirror displays — but *not*
                    // accessibility text reads (tracked separately in
                    // cwage/whoarewe#28) or a camera pointed at the screen.
                    // See cwage/whoarewe#22.
                    val protectFromCapture = when (val state = uiState) {
                        is UiState.Main ->
                            state.pairStep !is PairStep.ShowQr
                        else -> true
                    }
                    LaunchedEffect(protectFromCapture) {
                        if (protectFromCapture) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }

                    val context = LocalContext.current

                    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                        result.contents?.let { contents ->
                            viewModel.onQrScanned(contents)
                        }
                    }

                    val imagePickerLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) {
                            val data = QrDecoder.decodeFromUri(context, uri)
                            if (data != null) {
                                viewModel.onQrScanned(data)
                            } else {
                                viewModel.onBiometricError("No QR code found in image")
                            }
                        }
                    }

                    BiometricGate(
                        request = biometricRequest,
                        onSuccess = { cipher -> viewModel.onBiometricSuccess(cipher) },
                        onError = { error -> viewModel.onBiometricError(error) },
                        onCancelled = { viewModel.onBiometricCancelled() }
                    )

                    when (val state = uiState) {
                        is UiState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        is UiState.Setup -> {
                            SetupScreen(
                                state = state,
                                onDisplayNameChanged = { viewModel.updateDisplayName(it) },
                                onGenerateIdentity = { viewModel.requestGenerateIdentity() }
                            )
                        }
                        is UiState.Locked -> {
                            LockedScreen(
                                state = state,
                                onUnlock = { viewModel.retryUnlock() }
                            )
                        }
                        is UiState.Main -> {
                            // The key-change warning dialog (cwage/whoarewe#33).
                            // Rendered before the screen itself so it overlays
                            // whichever of the Main sub-screens is active —
                            // the collision check fires from onQrScanned,
                            // which can be driven from either the pair wizard
                            // (normal case) or a direct scan from the contact
                            // list, so the dialog cannot live inside either
                            // screen alone.
                            pendingNameCollision?.let { collision ->
                                NameCollisionDialog(
                                    existingName = collision.existing.displayName,
                                    onReplace = { viewModel.confirmReplaceContact() },
                                    onAddAsSecond = { viewModel.confirmAddAsSecondContact() },
                                    onCancel = { viewModel.cancelNameCollision() }
                                )
                            }

                            when (val pairStep = state.pairStep) {
                                is PairStep.ShowQr -> {
                                    val publicKey = viewModel.keyManager.getPublicKeyBytes()
                                    if (publicKey != null) {
                                        QrDisplayScreen(
                                            displayName = state.identity.displayName,
                                            publicKey = publicKey,
                                            fingerprint = state.fingerprint,
                                            onBack = { viewModel.finishPairing() }
                                        )
                                    }
                                }
                                is PairStep.Scan, is PairStep.Done -> {
                                    AddContactScreen(
                                        step = pairStep,
                                        error = state.error,
                                        onScanCamera = {
                                            val options = ScanOptions()
                                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                                .setPrompt("Scan contact's WhoAreWe QR code")
                                                .setBeepEnabled(false)
                                                .setOrientationLocked(false)
                                            scanLauncher.launch(options)
                                        },
                                        onScanImage = {
                                            imagePickerLauncher.launch("image/*")
                                        },
                                        onDone = { viewModel.finishPairing() },
                                        onBack = { viewModel.finishPairing() },
                                        onClearError = { viewModel.clearError() }
                                    )
                                }
                                null -> {
                                    ContactListScreen(
                                        state = state,
                                        onPair = { viewModel.startPairing() },
                                        onShowQr = { viewModel.showQr() },
                                        onDeleteContact = { viewModel.deleteContact(it) },
                                        onClearError = { viewModel.clearError() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BiometricGate(
    request: BiometricRequest?,
    onSuccess: (javax.crypto.Cipher?) -> Unit,
    onError: (String) -> Unit,
    onCancelled: () -> Unit
) {
    if (request == null) return

    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return

    DisposableEffect(request) {
        val executor = ContextCompat.getMainExecutor(activity)
        // Modern path (API ≥ R): the cipher is already initialized and the
        // prompt unlocks it via CryptoObject. Legacy path (API < R): the
        // cipher is null on the request, the prompt runs without a CryptoObject,
        // and the vm acquires the cipher in onBiometricSuccess once the
        // device-credential window has been refreshed by the prompt. See
        // KeyManager.usesLegacyAuth() / cwage/whoarewe#6.
        val deferredCipher = request.cipher == null
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (deferredCipher) {
                        // Legacy path — let the vm acquire the cipher post-auth.
                        onSuccess(null)
                        return
                    }
                    val cipher = result.cryptoObject?.cipher
                    if (cipher != null) {
                        onSuccess(cipher)
                    } else {
                        onError("Authentication succeeded but cipher unavailable")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED) {
                        onCancelled()
                    } else {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // Individual attempt failed; prompt stays open for retry
                }
            }
        )

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("WhoAreWe")
            .setSubtitle(request.subtitle)

        // setAllowedAuthenticators(BIOMETRIC_STRONG | DEVICE_CREDENTIAL) is
        // only valid on API ≥ R. On API 28-29 the androidx Biometric library
        // throws from PromptInfo.build() if you try to combine those two
        // authenticators, so we have to use the deprecated
        // setDeviceCredentialAllowed(true) instead. (That deprecated path
        // forbids passing a CryptoObject, which is fine — on legacy we always
        // run the prompt without one and acquire the cipher post-auth. See
        // KeyManager.usesLegacyAuth() / cwage/whoarewe#6.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            promptInfoBuilder.setDeviceCredentialAllowed(true)
        }

        val promptInfo = promptInfoBuilder.build()

        if (deferredCipher) {
            prompt.authenticate(promptInfo)
        } else {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(request.cipher!!))
        }

        onDispose {
            prompt.cancelAuthentication()
        }
    }
}
