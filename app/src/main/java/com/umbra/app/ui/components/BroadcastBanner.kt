package com.umbra.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.broadcast.BroadcastEvent
import com.umbra.app.domain.broadcast.BroadcastStatus
import com.umbra.app.domain.broadcast.RelayBroadcastResult
import com.umbra.app.domain.broadcast.RelayBroadcastStatus
import com.umbra.app.domain.nip01.KindNames
import java.net.URI

/**
 * Number of tracked broadcasts whose relay list is shown expanded by default the moment the
 * banner is opened — the rest start collapsed so several concurrent publishes don't bury each
 * other; each stays independently togglable from there. Deliberately small — showing more risks
 * burying the compact row in expanded relay lists during a busy multi-publish moment.
 */
private const val DEFAULT_EXPANDED_SECTIONS = 2

/**
 * Always-on publish status banner surfacing per-relay delivery tracking for every publish, with
 * no settings toggle (this is always on). The banner itself always renders
 * collapsed to a single compact row; tapping it reveals every in-flight/recent publish as its
 * own section — kind label, status, per-relay breakdown, and a per-broadcast retry-failed/
 * dismiss — with each section independently collapsible so a busy multi-publish moment doesn't
 * force scrolling past relay lists you don't currently care about.
 */
@Composable
fun BroadcastBanner(
    broadcasts: List<BroadcastEvent>,
    onRetryFailed: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = broadcasts.isNotEmpty(),
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 },
        modifier = modifier
    ) {
        if (broadcasts.isNotEmpty()) {
            BroadcastBannerCard(broadcasts = broadcasts, onRetryFailed = onRetryFailed, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun BroadcastBannerCard(
    broadcasts: List<BroadcastEvent>,
    onRetryFailed: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Re-seeded from the current broadcasts each time the banner is (re)opened, so the default
    // stays the oldest-first N while still letting the user's toggles persist for as long as the
    // banner remains open.
    var expandedBroadcastIds by remember(expanded) {
        mutableStateOf(
            broadcasts.sortedBy { it.startedAtMs }.take(DEFAULT_EXPANDED_SECTIONS).map { it.id }.toSet()
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            BroadcastHeaderRow(broadcasts = broadcasts, onDismissAll = { broadcasts.forEach { onDismiss(it.id) } })
            val anyInProgress = broadcasts.any { it.overallStatus == BroadcastStatus.IN_PROGRESS }
            if (anyInProgress) {
                Spacer(Modifier.height(8.dp))
                val aggregateProgress = broadcasts.map { it.progress }.average().toFloat()
                LinearProgressIndicator(progress = { aggregateProgress }, modifier = Modifier.fillMaxWidth())
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    broadcasts.forEachIndexed { index, broadcast ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        BroadcastSection(
                            broadcast = broadcast,
                            isExpanded = broadcast.id in expandedBroadcastIds,
                            onToggleExpand = {
                                expandedBroadcastIds = if (broadcast.id in expandedBroadcastIds) {
                                    expandedBroadcastIds - broadcast.id
                                } else {
                                    expandedBroadcastIds + broadcast.id
                                }
                            },
                            onRetryFailed = { onRetryFailed(broadcast.id) },
                            onDismiss = { onDismiss(broadcast.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BroadcastHeaderRow(broadcasts: List<BroadcastEvent>, onDismissAll: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val aggregateStatus = aggregateStatus(broadcasts)
        Icon(Icons.Filled.CellTower, contentDescription = null, tint = statusTint(aggregateStatus))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val title = if (broadcasts.size == 1) {
                "${KindNames.labelFor(broadcasts.first().event.kind)}: ${statusLabel(aggregateStatus)}"
            } else {
                pluralStringResource(R.plurals.broadcast_results_title_count, broadcasts.size, broadcasts.size)
            }
            Text(title, style = MaterialTheme.typography.bodyMedium)

            val totalSuccess = broadcasts.sumOf { it.successCount }
            val totalRelays = broadcasts.sumOf { it.totalRelays }
            val subtitle = stringResource(R.string.broadcast_relay_count, totalSuccess, totalRelays) +
                " · " + stringResource(R.string.broadcast_tap_for_details)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDismissAll) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.broadcast_dismiss))
        }
    }
}

/**
 * One tracked publish's kind label, status, and dismiss action, with its per-relay breakdown and
 * retry-failed action collapsible independently of every other section in the banner.
 */
@Composable
private fun BroadcastSection(
    broadcast: BroadcastEvent,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand)
        ) {
            val (icon, tint) = statusIconAndTint(broadcast.overallStatus)
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${KindNames.labelFor(broadcast.event.kind)} · " +
                    stringResource(R.string.broadcast_relay_count, broadcast.successCount, broadcast.totalRelays),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (isExpanded) R.string.broadcast_collapse else R.string.broadcast_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.broadcast_dismiss),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(Modifier.padding(top = 4.dp)) {
                broadcast.targetRelays.forEach { relayUrl ->
                    RelayResultRow(relayUrl = relayUrl, result = broadcast.results[relayUrl])
                }
                if (broadcast.failureCount > 0) {
                    TextButton(onClick = onRetryFailed) {
                        Text(stringResource(R.string.broadcast_retry_failed_count, broadcast.failureCount))
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayResultRow(relayUrl: String, result: RelayBroadcastResult?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val status = result?.status ?: RelayBroadcastStatus.PENDING
        val (icon, tint) = relayStatusIconAndTint(status)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(relayHost(relayUrl), style = MaterialTheme.typography.bodySmall)
            val detail = relayStatusDetail(status, result?.message)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun aggregateStatus(broadcasts: List<BroadcastEvent>): BroadcastStatus = when {
    broadcasts.any { it.overallStatus == BroadcastStatus.IN_PROGRESS } -> BroadcastStatus.IN_PROGRESS
    broadcasts.all { it.overallStatus == BroadcastStatus.SUCCESS } -> BroadcastStatus.SUCCESS
    broadcasts.all { it.overallStatus == BroadcastStatus.FAILED } -> BroadcastStatus.FAILED
    else -> BroadcastStatus.PARTIAL
}

@Composable
private fun statusTint(status: BroadcastStatus): Color = when (status) {
    BroadcastStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
    BroadcastStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
    BroadcastStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
    BroadcastStatus.FAILED -> MaterialTheme.colorScheme.error
}

@Composable
private fun statusIconAndTint(status: BroadcastStatus): Pair<ImageVector, Color> = when (status) {
    BroadcastStatus.IN_PROGRESS -> Icons.Filled.Sync to MaterialTheme.colorScheme.primary
    BroadcastStatus.SUCCESS -> Icons.Filled.Check to MaterialTheme.colorScheme.tertiary
    BroadcastStatus.PARTIAL -> Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
    BroadcastStatus.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
}

@Composable
private fun relayStatusIconAndTint(status: RelayBroadcastStatus): Pair<ImageVector, Color> = when (status) {
    RelayBroadcastStatus.SUCCESS -> Icons.Filled.Check to MaterialTheme.colorScheme.tertiary
    RelayBroadcastStatus.FAILED, RelayBroadcastStatus.TIMEOUT -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    RelayBroadcastStatus.PENDING, RelayBroadcastStatus.RETRYING -> Icons.Filled.Sync to MaterialTheme.colorScheme.primary
}

@Composable
private fun statusLabel(status: BroadcastStatus): String = when (status) {
    BroadcastStatus.IN_PROGRESS -> stringResource(R.string.broadcast_sending)
    BroadcastStatus.SUCCESS -> stringResource(R.string.broadcast_sent)
    BroadcastStatus.PARTIAL -> stringResource(R.string.broadcast_sent_partial)
    BroadcastStatus.FAILED -> stringResource(R.string.broadcast_sent_failed)
}

@Composable
private fun relayStatusDetail(status: RelayBroadcastStatus, message: String?): String? = when (status) {
    RelayBroadcastStatus.TIMEOUT -> stringResource(R.string.broadcast_status_timeout)
    RelayBroadcastStatus.PENDING -> stringResource(R.string.broadcast_status_pending)
    RelayBroadcastStatus.RETRYING -> stringResource(R.string.broadcast_status_retrying)
    RelayBroadcastStatus.FAILED -> message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.broadcast_status_no_reason)
    RelayBroadcastStatus.SUCCESS -> null
}

private fun relayHost(relayUrl: String): String =
    runCatching { URI(relayUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: relayUrl
