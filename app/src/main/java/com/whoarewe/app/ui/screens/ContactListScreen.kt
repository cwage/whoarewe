package com.whoarewe.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whoarewe.app.ContactWithCode
import com.whoarewe.app.UiState
import com.whoarewe.app.crypto.TotpGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    state: UiState.Main,
    onPair: () -> Unit,
    onDeleteContact: (Long) -> Unit,
    onClearError: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var contactToDelete by remember { mutableStateOf<ContactWithCode?>(null) }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            snackbarHostState.showSnackbar(state.error)
            onClearError()
        }
    }

    contactToDelete?.let { item ->
        DeleteContactDialog(
            contactName = item.contact.displayName,
            onConfirm = {
                onDeleteContact(item.contact.id)
                contactToDelete = null
            },
            onDismiss = { contactToDelete = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WhoAreWe") },
                actions = {
                    IconButton(onClick = onPair) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Pair with someone")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Contact list (scrollable, takes remaining space)
            Box(modifier = Modifier.weight(1f)) {
                if (state.contacts.isEmpty()) {
                    EmptyState()
                } else {
                    ContactList(
                        contacts = state.contacts,
                        secondsRemaining = state.secondsRemaining,
                        onLongPress = { contactToDelete = it }
                    )
                }
            }

            // My identity bar at bottom
            HorizontalDivider()
            IdentityBar(
                displayName = state.identity.displayName,
                fingerprint = state.fingerprint
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No trusted contacts yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Scan a contact's QR code with the camera, or import a QR screenshot from your gallery.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ContactList(
    contacts: List<ContactWithCode>,
    secondsRemaining: Int,
    onLongPress: (ContactWithCode) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        items(contacts, key = { it.contact.id }) { item ->
            ContactRow(
                item = item,
                secondsRemaining = secondsRemaining,
                onLongPress = { onLongPress(item) }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    item: ContactWithCode,
    secondsRemaining: Int,
    onLongPress: () -> Unit
) {
    val progress = animateFloatAsState(
        targetValue = secondsRemaining / TotpGenerator.PERIOD_SECONDS.toFloat(),
        label = "timer"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timer indicator
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Name + notes
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.contact.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (!item.contact.notes.isNullOrBlank()) {
                Text(
                    text = item.contact.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // TOTP code
        Text(
            text = item.code.chunked(3).joinToString(" "),
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun DeleteContactDialog(
    contactName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $contactName?") },
        text = {
            Text(
                "You will no longer be able to verify this person's identity. " +
                    "To restore verification, you'll need to pair again in person " +
                    "or over a trusted channel."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "Remove",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun IdentityBar(
    displayName: String,
    fingerprint: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Your Identity",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
