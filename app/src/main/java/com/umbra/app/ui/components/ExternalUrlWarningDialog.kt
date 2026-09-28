package com.umbra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme


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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Icon(
                imageVector = UmbraIcons.Onion,
                contentDescription = null,
                tint = UmbraTheme.colors.caution,
                modifier = Modifier.size(28.dp)
            )
        },
        title = { Text(stringResource(R.string.warning), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = message ?: stringResource(R.string.external_url_tor_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The destination, shown in full in mono so a lookalike domain is easy to spot.
                Text(
                    text = url,
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
                        .padding(12.dp)
                )
            }
        },
        // The safe choice is the prominent one; leaving Tor is the quiet, deliberate one.
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = UmbraTheme.colors.corona)
            ) {
                Text(stringResource(R.string.stay_in_umbra))
            }
        },
        dismissButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.open_anyway), color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
