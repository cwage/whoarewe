package com.whoami.app

import android.os.Bundle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.whoami.app.crypto.QrDecoder
import com.whoami.app.ui.screens.ContactListScreen
import com.whoami.app.ui.screens.PairWizardScreen
import com.whoami.app.ui.screens.SetupScreen
import com.whoami.app.ui.theme.WhoAmITheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhoAmITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: WhoAmIViewModel = viewModel()
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
                                                .setPrompt("Scan contact's WhoAmI QR code")
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
            .setTitle("WhoAmI")
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
