package com.umbra.app.ui.networkusage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbra.app.R
import com.umbra.app.domain.model.NetworkUsageSnapshot
import com.umbra.app.domain.model.RelayTraffic
import com.umbra.app.ui.components.ConfirmDialog
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.SettingsGroup
import com.umbra.app.ui.components.TimeFormatter
import com.umbra.app.ui.components.UmbraIcons
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme
import java.util.Locale

@Composable
fun NetworkUsageScreen(viewModel: NetworkUsageViewModel, onNavigateBack: () -> Unit) {
    val usage by viewModel.usage.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    NetworkUsageContent(usage = usage, onNavigateBack = onNavigateBack, onReset = { confirmReset = true })
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.network_usage_reset_title),
            message = stringResource(R.string.network_usage_reset_message),
            confirmLabel = stringResource(R.string.network_usage_reset),
            isDestructive = true,
            onConfirm = {
                viewModel.reset()
                confirmReset = false
            },
            onDismiss = { confirmReset = false }
        )
    }
}

@Composable
internal fun NetworkUsageContent(usage: NetworkUsageSnapshot?, onNavigateBack: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        UmbraTopAppBar(
            title = { Text(stringResource(R.string.network_usage_title)) },
            navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) }
        )
        if (usage == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingSpinner() }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "hero", contentType = "hero") { UsageHero(usage) }
            item(key = "split", contentType = "group") {
                SettingsGroup(title = stringResource(R.string.network_usage_breakdown)) {
                    val total = usage.sessionBytes.coerceAtLeast(1)
                    BreakdownRow(stringResource(R.string.network_usage_relays), usage.relayBytes, total, UmbraTheme.colors.corona, true)
                    BreakdownRow(stringResource(R.string.network_usage_media), usage.mediaBytesReceived, total, UmbraTheme.colors.zap, true)
                    BreakdownRow(stringResource(R.string.network_usage_other_http), usage.otherHttpBytesReceived, total, UmbraTheme.colors.secure, true)
                    BreakdownRow(stringResource(R.string.network_usage_uploads), usage.httpBytesSent, total, MaterialTheme.colorScheme.outline, false)
                }
            }
            item(key = "relays-title", contentType = "title") {
                Text(
                    text = stringResource(R.string.network_usage_per_relay),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
                )
            }
            val maxRelay = usage.relays.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1) ?: 1
            items(usage.relays, key = { it.relayUrl }, contentType = { "relay" }) { relay ->
                RelayTrafficRow(relay, maxRelay)
            }
            item(key = "privacy", contentType = "note") {
                Text(
                    text = stringResource(R.string.network_usage_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
            item(key = "reset", contentType = "action") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(onClick = onReset)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.network_usage_reset), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun UsageHero(usage: NetworkUsageSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(UmbraIcons.Onion, contentDescription = null, tint = UmbraTheme.colors.secure, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.network_usage_this_session, TimeFormatter.formatCompactRelativeTime(usage.sessionStartedAtMillis / 1000)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatBytesShort(usage.sessionBytes),
            style = MonoStyle.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = stringResource(R.string.network_usage_lifetime, formatBytesShort(usage.lifetimeBytes)),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun BreakdownRow(label: String, bytes: Long, total: Long, color: Color, showDivider: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(formatBytesShort(bytes), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Meter(fraction = bytes.toFloat() / total, color = color, modifier = Modifier.padding(top = 8.dp))
    }
    if (showDivider) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RelayTrafficRow(relay: RelayTraffic, max: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = relay.relayUrl.removePrefix("wss://").removePrefix("ws://"),
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f)
            )
            Text(formatBytesShort(relay.totalBytes), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
        }
        Meter(relay.totalBytes.toFloat() / max, UmbraTheme.colors.corona, Modifier.padding(vertical = 6.dp))
        Text(
            text = stringResource(
                R.string.network_usage_relay_detail,
                formatBytesShort(relay.bytesReceived),
                relay.messagesReceived,
                formatBytesShort(relay.bytesSent),
                relay.messagesSent
            ),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
    }
}

/** "812 KB", "4.2 MB", "1.07 GB": short, binary units. */
internal fun formatBytesShort(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = when {
        unit == 0 || value >= 100 -> "%.0f %s"
        value >= 10 -> "%.1f %s"
        else -> "%.2f %s"
    }
    return String.format(Locale.US, pattern, value, units[unit])
}
