package com.whoarewe.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.whoarewe.app.crypto.QrCodeUtils
import com.whoarewe.app.crypto.QrDecoder
import com.whoarewe.app.ui.screens.ContactListScreen
import com.whoarewe.app.ui.screens.PairWizardScreen
import com.whoarewe.app.ui.screens.SetupScreen
import com.whoarewe.app.ui.theme.WhoAreWeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleE2eIntent(intent)
    }

    /**
     * Debug-only test seam used by `scripts/e2e-pairing.sh`. Three extras are recognised:
     *   --ez e2e_dump_qr true       → log this device's QR payload to logcat tag "WhoAreWe-E2E"
     *   --es e2e_inject_qr <s>      → feed the string into the normal onQrScanned() pipeline,
     *                                 skipping the photo picker / camera scanner UI.
     *   --ez e2e_dump_secrets true  → log every stored TOTP shared secret as
     *                                 "SECRET_DUMP: <hex>=<displayName>". The hex is
     *                                 a fixed [0-9a-f]+ alphabet so it cannot contain
     *                                 the `=` delimiter, which makes the format safe
     *                                 even when display names contain `=`. The e2e
     *                                 test compares the hex directly across devices,
     *                                 which asserts the cryptographic invariant
     *                                 without dragging the displayed code through
     *                                 wall-clock TOTP window timing.
     * Bypassed entirely on release builds.
     */
    private fun handleE2eIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        is UiState.Main -> {
                            val pairStep = state.pairStep
                            if (pairStep != null) {
                                val publicKey = viewModel.keyManager.getPublicKeyBytes()
                                if (publicKey != null) {
                                    PairWizardScreen(
                                        step = pairStep,
                                        displayName = state.identity.displayName,
                                        publicKey = publicKey,
                                        fingerprint = state.fingerprint,
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
                                        onShowFirst = { viewModel.showMyCodeFirst() },
                                        onReadyToScan = { viewModel.readyToScan() },
                                        onDone = { viewModel.finishPairing() },
                                        onBack = { viewModel.finishPairing() },
                                        onClearError = { viewModel.clearError() }
                                    )
                                }
                            } else {
                                ContactListScreen(
                                    state = state,
                                    onPair = { viewModel.startPairing() },
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

@Composable
fun BiometricGate(
    request: BiometricRequest?,
    onSuccess: (javax.crypto.Cipher) -> Unit,
    onError: (String) -> Unit,
    onCancelled: () -> Unit
) {
    if (request == null) return

    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return

    DisposableEffect(request) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
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

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("WhoAreWe")
            .setSubtitle(request.subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(request.cipher))

        onDispose {
            prompt.cancelAuthentication()
        }
    }
}
