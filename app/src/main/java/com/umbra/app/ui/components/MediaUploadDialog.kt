package com.umbra.app.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.umbra.app.R

/**
 * Shared upload-configuration surface, shown before any Blossom upload actually starts —
 * profile picture/banner and composer attachments alike; see this dialog's call sites for what
 * was deliberately left out: no "strip metadata" toggle, since Umbra always does it — see
 * [metadataNoticeVisible] below — and no "convert GIF to MP4" toggle at all.
 *
 * Rendered inline (a bordered [Card], not a system `Dialog()`/`AlertDialog`) so it composes
 * naturally into a scrolling screen below whatever picker triggered it.
 */
@Composable
fun MediaUploadDialog(
    previewUri: Uri,
    mimeType: String,
    availableServers: List<String>,
    selectedServer: String,
    onServerSelected: (String) -> Unit,
    isUploading: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // Both null for a context with nothing meaningful to attach them to (profile picture/banner
    // — kind:0 has no imeta, no per-upload content-warning). Composer passes real callbacks.
    altText: String? = null,
    onAltTextChange: ((String) -> Unit)? = null,
    sensitiveContent: Boolean = false,
    onSensitiveContentChange: ((Boolean) -> Unit)? = null
) {
    var serverMenuExpanded by remember { mutableStateOf(false) }
    val isGif = mimeType.equals("image/gif", ignoreCase = true)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(if (isGif) R.string.media_upload_dialog_title_gif else R.string.media_upload_dialog_title_image),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCancel, enabled = !isUploading) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.media_upload_dialog_cancel_cd))
                }
            }

            HorizontalDivider()

            AsyncImage(
                model = previewUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isUploading && availableServers.size > 1) { serverMenuExpanded = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.media_upload_dialog_server_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = selectedServer, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (availableServers.size > 1) {
                        Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    }
                }
                DropdownMenu(expanded = serverMenuExpanded, onDismissRequest = { serverMenuExpanded = false }) {
                    availableServers.forEach { server ->
                        DropdownMenuItem(
                            text = { Text(server) },
                            onClick = {
                                onServerSelected(server)
                                serverMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.media_upload_dialog_metadata_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (altText != null && onAltTextChange != null) {
                OutlinedTextField(
                    value = altText,
                    onValueChange = onAltTextChange,
                    label = { Text(stringResource(R.string.media_upload_dialog_alt_text_label)) },
                    placeholder = { Text(stringResource(R.string.media_upload_dialog_alt_text_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (onSensitiveContentChange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.media_upload_dialog_sensitive_label), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.media_upload_dialog_sensitive_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = sensitiveContent, onCheckedChange = onSensitiveContentChange)
                }
            }

            TextButton(
                onClick = onConfirm,
                enabled = !isUploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isUploading) {
                    LoadingSpinner(size = 18.dp, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.media_upload_dialog_upload_action))
                }
            }
        }
    }
}
