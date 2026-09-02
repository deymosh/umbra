package com.umbra.app.ui.relay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.relay.Relay
import com.umbra.app.ui.components.InfoIcon
import com.umbra.app.ui.components.privateKeyboardOptions
/**
 * Dialog for adding or editing a relay
 */
@Composable
internal fun RelayEditDialog(
    relay: Relay?,
    addRole: RelayRole,
    onSave: (Relay) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember(relay?.id) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var url by remember { mutableStateOf(relay?.url ?: "") }
    var canRead by remember { mutableStateOf(relay?.isReadEnabled ?: (addRole == RelayRole.INBOX)) }
    var canWrite by remember { mutableStateOf(relay?.isWriteEnabled ?: (addRole == RelayRole.OUTBOX)) }
    var canDm by remember { mutableStateOf(relay?.isDmEnabled ?: (addRole == RelayRole.DM)) }
    var canSearch by remember { mutableStateOf(relay?.isSearchEnabled ?: (addRole == RelayRole.SEARCH)) }
    var canIndex by remember { mutableStateOf(relay?.isIndexEnabled ?: (addRole == RelayRole.INDEX)) }
    var dmAuthRequired by remember { mutableStateOf(relay?.dmRequiresAuth ?: false) }
    var urlError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(relay?.id) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Auto-detect if relay is .onion based on URL
    val isOnion = url.contains(".onion")
    val dmAllowedTransport = isDmTransportAllowed(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (relay == null) {
                    stringResource(R.string.add_relay_title)
                } else {
                    stringResource(R.string.edit_relay_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = url,
                    onValueChange = {
                        url = it
                        urlError = if (it.isBlank() || isValidRelayUrl(it)) {
                            null
                        } else {
                            context.getString(R.string.relay_invalid_url)
                        }
                    },
                    label = { Text(stringResource(R.string.relay_url_label)) },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .fillMaxWidth(),
                    keyboardOptions = privateKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Uri)),
                    isError = urlError != null,
                    supportingText = {
                        if (urlError != null) {
                            Text(
                                text = urlError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else if (isOnion) {
                            Text(
                                stringResource(R.string.relay_onion_help),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.relay_read), style = MaterialTheme.typography.bodySmall)
                        InfoIcon(title = stringResource(R.string.relay_read), message = stringResource(R.string.relay_help_inbox_body))
                    }
                    Switch(checked = canRead, onCheckedChange = { canRead = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.relay_write), style = MaterialTheme.typography.bodySmall)
                        InfoIcon(title = stringResource(R.string.relay_write), message = stringResource(R.string.relay_help_outbox_body))
                    }
                    Switch(checked = canWrite, onCheckedChange = { canWrite = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.relay_dm), style = MaterialTheme.typography.bodySmall)
                        InfoIcon(title = stringResource(R.string.relay_dm), message = stringResource(R.string.relay_help_dm_body))
                    }
                    Switch(
                        checked = canDm,
                        enabled = dmAllowedTransport,
                        onCheckedChange = {
                            canDm = it
                            if (it) dmAuthRequired = true
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.relay_auth_nip42), style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = canDm,
                        enabled = false,
                        onCheckedChange = {}
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.relay_search), style = MaterialTheme.typography.bodySmall)
                        InfoIcon(title = stringResource(R.string.relay_search), message = stringResource(R.string.relay_help_search_body))
                    }
                    Switch(checked = canSearch, onCheckedChange = { canSearch = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.relay_index), style = MaterialTheme.typography.bodySmall)
                        InfoIcon(title = stringResource(R.string.relay_index), message = stringResource(R.string.relay_help_index_body))
                    }
                    Switch(checked = canIndex, onCheckedChange = { canIndex = it })
                }

                if (canDm && !dmAllowedTransport) {
                    Text(
                        text = stringResource(R.string.relay_dm_wss_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val isUrlValid = isValidRelayUrl(url)
                    if (!isUrlValid) {
                        urlError = context.getString(R.string.relay_invalid_url)
                        return@Button
                    }
                    if (url.isNotEmpty() && (!canDm || dmAllowedTransport)) {
                        dmAuthRequired = canDm
                        val newRelay = if (relay != null) {
                            val readActive = relay.isReadActive && canRead
                            val writeActive = relay.isWriteActive && canWrite
                            val dmActive = relay.isDmActive && canDm
                            val searchActive = relay.isSearchActive && canSearch
                            val indexActive = relay.isIndexActive && canIndex
                            relay.copy(
                                url = url,
                                isOnion = isOnion,
                                isReadEnabled = canRead,
                                isReadActive = readActive,
                                isWriteEnabled = canWrite,
                                isWriteActive = writeActive,
                                isDmEnabled = canDm,
                                isDmActive = dmActive,
                                dmRequiresAuth = if (canDm) dmAuthRequired else false,
                                isSearchEnabled = canSearch,
                                isSearchActive = searchActive,
                                isIndexEnabled = canIndex,
                                isIndexActive = indexActive,
                                isEnabled = readActive || writeActive || dmActive || searchActive || indexActive
                            )
                        } else {
                            // Empty id: ViewModel detects it is a new relay and calls addRelay
                            Relay(
                                id = "",
                                url = url,
                                isOnion = isOnion,
                                isReadEnabled = canRead,
                                isReadActive = canRead,
                                isWriteEnabled = canWrite,
                                isWriteActive = canWrite,
                                isDmEnabled = canDm,
                                isDmActive = canDm,
                                dmRequiresAuth = if (canDm) dmAuthRequired else false,
                                isSearchEnabled = canSearch,
                                isSearchActive = canSearch,
                                isIndexEnabled = canIndex,
                                isIndexActive = canIndex,
                                isEnabled = canRead || canWrite || canDm || canSearch || canIndex
                            )
                        }
                        onSave(newRelay)
                    }
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun isValidRelayUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    if (!u.startsWith("ws://") && !u.startsWith("wss://")) return false
    val host = runCatching { java.net.URI(u).host }.getOrNull() ?: return false
    return host.isNotBlank()
}

private fun isDmTransportAllowed(url: String): Boolean {
    val u = url.trim().lowercase()
    if (u.startsWith("wss://")) return true
    if (!u.startsWith("ws://")) return false

    val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull()
    return host?.endsWith(".onion") == true || u.contains(".onion")
}

/**
 * Format relay URL for display (show first + last chars, ideal for .onion).
 * For onion relays, preserve path suffixes (/outbox, /inbox, /chat) to distinguish roles.
 */
internal fun formatRelayUrl(url: String): String {
    return when {
        url.contains(".onion") -> {
            val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull()
            val protocol = parsed?.scheme ?: url.substringBefore("://", "ws")
            val host = parsed?.host ?: url.substringAfter("://").substringBefore('/').substringBefore(':')
            val pathSuffix = parsed?.rawPath
                ?.takeIf { it.isNotBlank() && it != "/" }
                .orEmpty()

            val beforeOnion = host.removeSuffix(".onion")
            val start = beforeOnion.take(10)
            val end = beforeOnion.takeLast(3)

            "$protocol://$start...$end.onion$pathSuffix"
        }
        url.length > 50 -> url.take(25) + "..." + url.takeLast(20)
        else -> url
    }
}

internal fun relayDisplayName(relay: Relay, relayInfo: RelayInfo?): String {
    return relayInfo?.name?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.URI(relay.url).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: formatRelayUrl(relay.url)
}
