package com.umbra.app.ui.relay

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.groupByPurpose
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.formatCount
import com.umbra.app.ui.theme.MonoStyle

/**
 * Cross-relay view of every currently-open subscription, grouped by purpose (outbox/inbox/feed/
 * other — see [groupByPurpose]). Unlike the per-relay Relay Details screen, this doesn't require
 * picking a relay first to see what's actually subscribed right now — the "Subscriptions" count
 * in [RelayTelemetryCard] used to be a dead end with no way to inspect what it was counting.
 */
@Composable
fun ActiveSubscriptionsScreen(
    navController: NavController,
    viewModel: RelayConfigViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ActiveSubscriptionsContent(
        requests = state.relayRequests,
        currentUserPubkey = state.currentUserPubkey,
        onNavigateBack = { navController.popBackStack() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActiveSubscriptionsContent(
    requests: List<RelayRequestInfo>,
    currentUserPubkey: String?,
    onNavigateBack: () -> Unit
) {
    val grouped = remember(requests) { requests.groupByPurpose() }
    val relayCount = remember(requests) { requests.mapTo(mutableSetOf()) { normalizeRelayUrl(it.relayUrl) }.size }
    val totalEventCount = remember(requests) { requests.sumOf { it.receivedEventCount } }
    // Keyed by purpose group ("outbox"/"inbox"/"feed"/"other"), missing = expanded. Expanded by
    // default so nothing is hidden on first open; a large inbox section (see groupByPurpose's doc
    // comment on how big that can get) can be collapsed to reach feed without scrolling past it.
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.active_subscriptions_title)) },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack)
                }
            )
        }
    ) { innerPadding ->
        if (requests.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.active_subscriptions_empty),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "stats", contentType = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(requests.size, stringResource(R.string.active_subscriptions_stat_subscriptions), Modifier.weight(1f))
                    StatTile(relayCount, stringResource(R.string.active_subscriptions_stat_relays), Modifier.weight(1f))
                    StatTile(totalEventCount, stringResource(R.string.active_subscriptions_stat_events), Modifier.weight(1f))
                }
            }

            listOf(
                "outbox" to (R.string.relay_subscriptions_outbox to grouped.outbox),
                "inbox" to (R.string.relay_subscriptions_inbox to grouped.inbox),
                "feed" to (R.string.relay_subscriptions_feed to grouped.feed),
                "other" to (R.string.relay_subscriptions_other to grouped.other)
            ).forEach { (groupKey, titleAndRequests) ->
                val (titleRes, groupRequests) = titleAndRequests
                if (groupRequests.isEmpty()) return@forEach
                val isExpanded = expandedGroups[groupKey] ?: true

                // Real LazyColumn items (not a plain Column nested inside one `item {}`) so a
                // purpose group with hundreds of subscriptions across hundreds of relays — e.g.
                // "feed" — stays virtualized instead of composing every card up front. Collapsing
                // a group (below) skips its items() entirely rather than hiding them, for the same
                // reason — a collapsed "inbox" with hundreds of discovered-relay cards shouldn't
                // still pay to compose them off-screen.
                item(key = "header-$groupKey", contentType = "group_header") {
                    val groupRelayCount = remember(groupRequests) {
                        groupRequests.mapTo(mutableSetOf()) { normalizeRelayUrl(it.relayUrl) }.size
                    }
                    val groupEventCount = remember(groupRequests) { groupRequests.sumOf { it.receivedEventCount } }
                    GroupHeader(
                        title = stringResource(titleRes),
                        count = groupRequests.size,
                        summary = stringResource(
                            R.string.active_subscriptions_group_summary,
                            groupRelayCount,
                            groupEventCount
                        ),
                        expanded = isExpanded,
                        onToggle = { expandedGroups[groupKey] = !isExpanded }
                    )
                }
                if (isExpanded) {
                    // Highest event count first — surfaces the busiest subscriptions instead of
                    // whatever arrival order they were opened in.
                    val sortedRequests = groupRequests.sortedByDescending { it.receivedEventCount }
                    items(
                        items = sortedRequests,
                        key = { "$groupKey|${it.relayUrl}|${it.subscriptionId}" },
                        contentType = { "subscription_card" }
                    ) { req ->
                        SubscriptionCard(req, showRelayUrl = true, currentUserPubkey = currentUserPubkey)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = formatCount(value),
            style = MonoStyle.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.active_subscriptions_collapse_group else R.string.active_subscriptions_expand_group
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).rotate(rotation)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 7.dp, vertical = 1.dp)
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = summary,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
