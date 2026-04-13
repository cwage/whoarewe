package com.whoarewe.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Sub-navigation state within [UiState.Main]. When non-null the app renders
 * one of the sub-screens below instead of the contact list.
 *
 * The old wizard had five steps (Choose / ShowFirst / ScanAfterShow /
 * ShowAfterScan / Done) that implied a coordinated two-party handshake.
 * In reality each side independently scans the other's QR and derives the
 * same shared secret — no sequencing required. See cwage/whoarewe#49.
 */
sealed class PairStep {
    /** "Add a contact" — scan a QR with the camera or import an image. */
    data object Scan : PairStep()

    /** Standalone "Show my QR" screen, not part of the add-contact flow. */
    data object ShowQr : PairStep()

    /** Contact saved successfully. */
    data class Done(val addedName: String) : PairStep()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    step: PairStep,
    error: String?,
    onScanCamera: () -> Unit,
    onScanImage: () -> Unit,
    onShowQr: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onClearError: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            onClearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            is PairStep.Scan -> "Add a contact"
                            is PairStep.Done -> "Paired!"
                            is PairStep.ShowQr ->
                                error("ShowQr is routed to QrDisplayScreen, not AddContactScreen")
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate up")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (step) {
                is PairStep.Scan -> ScanStep(
                    onScanCamera = onScanCamera,
                    onScanImage = onScanImage,
                    onShowQr = onShowQr
                )
                is PairStep.Done -> DoneStep(
                    addedName = step.addedName,
                    onDone = onDone
                )
                is PairStep.ShowQr ->
                    error("ShowQr is routed to QrDisplayScreen, not AddContactScreen")
            }
        }
    }
}

@Composable
private fun ScanStep(
    onScanCamera: () -> Unit,
    onScanImage: () -> Unit,
    onShowQr: () -> Unit
) {
    Icon(
        imageVector = Icons.Default.QrCodeScanner,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Scan a contact's QR code",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Ask them to show their code in the WhoAreWe app, or have them send you a screenshot.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onScanCamera,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Scan with camera")
    }

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedButton(
        onClick = onScanImage,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Image, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Import from image")
    }

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedButton(
        onClick = onShowQr,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.QrCode, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Show my QR code")
    }
}

@Composable
private fun DoneStep(
    addedName: String,
    onDone: () -> Unit
) {
    Text(
        text = "You and $addedName are paired!",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Your matching verification codes are now active. " +
            "If someone ever contacts you claiming to be $addedName, " +
            "ask them to read their code.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Back to contacts")
    }
}
