package com.whoarewe.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whoarewe.app.crypto.QrCodeUtils

sealed class PairStep {
    data object Choose : PairStep()
    data object ShowFirst : PairStep()
    data object ScanAfterShow : PairStep()
    data class ShowAfterScan(val addedName: String) : PairStep()
    data class Done(val addedName: String) : PairStep()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairWizardScreen(
    step: PairStep,
    displayName: String,
    publicKey: ByteArray,
    fingerprint: String,
    error: String?,
    onScanCamera: () -> Unit,
    onScanImage: () -> Unit,
    onShowFirst: () -> Unit,
    onReadyToScan: () -> Unit,
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
                            is PairStep.Choose -> "Pair with someone"
                            is PairStep.ShowFirst -> "Show your code"
                            is PairStep.ScanAfterShow -> "Scan their code"
                            is PairStep.ShowAfterScan -> "Almost done"
                            is PairStep.Done -> "Paired!"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                is PairStep.Choose -> ChooseStep(
                    onScanFirst = onScanCamera,
                    onScanImage = onScanImage,
                    onShowFirst = onShowFirst
                )
                is PairStep.ShowFirst -> ShowQrStep(
                    displayName = displayName,
                    publicKey = publicKey,
                    fingerprint = fingerprint,
                    instruction = "Have them scan this code, then tap 'Next' to scan theirs.",
                    buttonText = "Next: Scan their code",
                    onNext = onReadyToScan
                )
                is PairStep.ScanAfterShow -> ScanStep(
                    onScanCamera = onScanCamera,
                    onScanImage = onScanImage
                )
                is PairStep.ShowAfterScan -> ShowQrStep(
                    displayName = displayName,
                    publicKey = publicKey,
                    fingerprint = fingerprint,
                    instruction = "You've added ${step.addedName}. Now have them scan this code to complete the connection.",
                    buttonText = "Done",
                    onNext = onDone
                )
                is PairStep.Done -> DoneStep(
                    addedName = step.addedName,
                    onDone = onDone
                )
            }
        }
    }
}

@Composable
private fun ChooseStep(
    onScanFirst: () -> Unit,
    onScanImage: () -> Unit,
    onShowFirst: () -> Unit
) {
    Icon(
        imageVector = Icons.Default.QrCodeScanner,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Both of you need to scan each other's code. Who goes first?",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onScanFirst,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Scan their code first")
    }

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedButton(
        onClick = onScanImage,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Image, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Import QR from image")
    }

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedButton(
        onClick = onShowFirst,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.QrCode, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Show my code first")
    }
}

@Composable
private fun ShowQrStep(
    displayName: String,
    publicKey: ByteArray,
    fingerprint: String,
    instruction: String,
    buttonText: String,
    onNext: () -> Unit
) {
    val qrContent = remember(publicKey, displayName) {
        QrCodeUtils.encode(displayName, publicKey)
    }
    val qrBitmap: Bitmap = remember(qrContent) {
        QrCodeUtils.generateBitmap(qrContent, 768)
    }

    Text(
        text = displayName,
        style = MaterialTheme.typography.headlineMedium
    )

    Spacer(modifier = Modifier.height(16.dp))

    Image(
        bitmap = qrBitmap.asImageBitmap(),
        contentDescription = "QR code for $displayName",
        modifier = Modifier.size(240.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = fingerprint,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = instruction,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(buttonText)
    }
}

@Composable
private fun ScanStep(
    onScanCamera: () -> Unit,
    onScanImage: () -> Unit
) {
    Icon(
        imageVector = Icons.Default.QrCodeScanner,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Now scan their QR code",
        style = MaterialTheme.typography.headlineSmall
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
        text = "Your matching verification codes are now active. If someone ever contacts you claiming to be $addedName, ask them to read their code.",
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
