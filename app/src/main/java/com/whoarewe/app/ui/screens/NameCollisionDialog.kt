package com.whoarewe.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/**
 * The key-change warning dialog (cwage/whoarewe#33). Surfaced when a scanned
 * QR carries a display name that matches an existing contact but a *different*
 * public key — i.e. the exact footprint of an impersonation attempt ("Hey,
 * this is Alice, I got a new phone, please re-pair with me").
 *
 * The three-option layout is deliberate:
 *  - **Cancel** is the primary (filled) button. It is the safest choice and
 *    matches the acceptance criteria in the issue.
 *  - **Replace existing** is a text button. It is the legitimate re-pair
 *    path — only correct after the user has verified the new key with
 *    [existingName] over a trusted channel.
 *  - **Add as a second** is a text button. Kept as a long-tail escape
 *    hatch ("two friends named Chris"), intentionally discouraged in the
 *    copy because it leaves two visually-identical rows.
 *
 * The description text is plain-language and names the impersonation risk
 * explicitly — we do not want to hide what the dialog is actually warning
 * about behind euphemism.
 */
@Composable
fun NameCollisionDialog(
    existingName: String,
    onReplace: () -> Unit,
    onAddAsSecond: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.semantics { testTag = "name_collision_dialog" },
        onDismissRequest = onCancel,
        title = {
            Text("\"$existingName\" already exists")
        },
        text = {
            Column {
                Text(
                    "The QR you just scanned has a different cryptographic " +
                        "identity than your existing \"$existingName\" contact.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "This can happen if $existingName regenerated their " +
                        "identity (for example, a new phone). Before accepting " +
                        "the new key, verify this change with $existingName " +
                        "directly over a trusted channel.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "If you did not expect this, choose Cancel — the QR may " +
                        "be an impersonation attempt.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "name_collision_cancel" },
                    onClick = onCancel
                ) {
                    Text("Cancel")
                }
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "name_collision_replace" },
                    onClick = onReplace
                ) {
                    Text("Replace existing $existingName")
                }
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "name_collision_add_second" },
                    onClick = onAddAsSecond
                ) {
                    Text("Add as a second $existingName")
                }
            }
        }
    )
}
