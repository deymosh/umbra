package com.umbra.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.umbra.app.R

@Composable
fun ExternalUrlWarningDialog(
    url: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    // Overrides the default Tor/IP-leak-specific body copy — needed for a non-http(s) external
    // open (e.g. a Lightning wallet intent via launchLightningInvoice) where that wording would
    // be misleading, while still satisfying AUDIT.md's "every externally-opened URL" gate.
    message: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.warning)) },
        text = {
            Text(
                text = buildString {
                    append(message ?: stringResource(R.string.external_url_tor_warning))
                    append("\n\n")
                    append(url)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.open_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
