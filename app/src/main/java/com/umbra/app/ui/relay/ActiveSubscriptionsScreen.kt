package com.umbra.app.ui.relay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.relay.groupByPurpose
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import kotlin.OptIn
import androidx.compose.material3.ExperimentalMaterial3Api
import com.umbra.app.ui.Screen
/**
 * Cross-relay view of every currently-open subscription, grouped by purpose (outbox/inbox/feed/
 * other — see [groupByPurpose]). Unlike the per-relay Relay Details screen, this doesn't require
 * picking a relay first to see what's actually subscribed right now — the "Subscriptions" count
 * in [RelayTelemetryCard] used to be a dead end with no way to inspect what it was counting.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ActiveSubscriptionsScreen(
    navController: NavController,
    viewModel: RelayConfigViewModel
) {
    val state by viewModel.state.collectAsState()
    val allRequests = state.relayRequests
    val grouped = remember(allRequests) { allRequests.groupByPurpose() }
    val relayCount = remember(allRequests) { allRequests.mapTo(mutableSetOf()) { normalizeRelayUrl(it.relayUrl) }.size }
    val totalEventCount = remember(allRequests) { allRequests.sumOf { it.receivedEventCount } }
    // Keyed by purpose group ("outbox"/"inbox"/"feed"/"other"), missing = expanded. Expanded by
    // default so nothing is hidden on first open; a large inbox section (see groupByPurpose's doc
    // comment on how big that can get) can be collapsed to reach feed without scrolling past it.
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.active_subscriptions_title)) },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(onClick = { navController.popBackStack() })
                }
            )
        }
    ) { innerPadding ->
        if (allRequests.isEmpty()) {
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
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(
                        R.string.active_subscriptions_total,
                        allRequests.size,
                        relayCount,
                        totalEventCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            listOf(
                "outbox" to (R.string.relay_subscriptions_outbox to grouped.outbox),
                "inbox" to (R.string.relay_subscriptions_inbox to grouped.inbox),
                "feed" to (R.string.relay_subscriptions_feed to grouped.feed),
                "other" to (R.string.relay_subscriptions_other to grouped.other)
            ).forEach { (groupKey, titleAndRequests) ->
                val (titleRes, requests) = titleAndRequests
                if (requests.isEmpty()) return@forEach
                val isExpanded = expandedGroups[groupKey] ?: true

                // Real LazyColumn items (not a plain Column nested inside one `item {}`) so a
                // purpose group with hundreds of subscriptions across hundreds of relays — e.g.
                // "feed" — stays virtualized instead of composing every card up front. Collapsing
                // a group (below) skips its items()/spacer entirely rather than hiding them, for
                // the same reason — a collapsed "inbox" with hundreds of discovered-relay cards
                // shouldn't still pay to compose them off-screen.
                item(key = "header-$groupKey") {
                    val groupRelayCount = remember(requests) {
                        requests.mapTo(mutableSetOf()) { normalizeRelayUrl(it.relayUrl) }.size
                    }
                    val groupEventCount = remember(requests) { requests.sumOf { it.receivedEventCount } }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedGroups[groupKey] = !isExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(
                                    if (isExpanded) {
                                        R.string.active_subscriptions_collapse_group
                                    } else {
                                        R.string.active_subscriptions_expand_group
                                    }
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(titleRes),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.active_subscriptions_group_summary,
                                groupRelayCount,
                                groupEventCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isExpanded) {
                    // Highest event count first — surfaces the busiest/most active subscriptions
                    // instead of whatever arrival order they were opened in. Not wrapped in
                    // remember{} — this forEach body isn't itself a @Composable context (only the
                    // item{}/items{} content lambdas it calls are), and sorting is cheap enough not
                    // to need memoizing here anyway.
                    val sortedRequests = requests.sortedByDescending { it.receivedEventCount }
                    items(
                        items = sortedRequests,
                        key = { "$groupKey|${it.relayUrl}|${it.subscriptionId}" },
                        contentType = { "subscription_card" }
                    ) { req ->
                        SubscriptionCard(req, showRelayUrl = true, currentUserPubkey = state.currentUserPubkey)
                    }
                    item(key = "spacer-$groupKey") { Spacer(modifier = Modifier.height(4.dp)) }
                }
            }
        }
    }
}
