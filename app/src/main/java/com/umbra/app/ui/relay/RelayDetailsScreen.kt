package com.umbra.app.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.ui.components.launchExternalUrl
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.groupByPurpose
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.SectionHeader
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import kotlin.OptIn
import androidx.compose.material3.ExperimentalMaterial3Api
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.umbra.app.ui.Screen
import coil3.compose.AsyncImage
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RelayDetailsScreen(
    navController: NavController,
    relayId: String,
    viewModel: RelayConfigViewModel
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }
    val relay = state.relays.firstOrNull { it.id == relayId }

    pendingExternalUrl?.let { url ->
        ExternalUrlWarningDialog(
            url = url,
            onConfirm = {
                launchExternalUrl(context, url)
                pendingExternalUrl = null
            },
            onDismiss = { pendingExternalUrl = null }
        )
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(relay?.let { relayDisplayName(it, it.relayInfo) } ?: stringResource(R.string.relay_detail_title)) },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(onClick = { navController.popBackStack() })
                },
                actions = {
                    val isLoadingNip11 = relay?.url?.let { it in state.relayInfoLoading } ?: false
                    if (isLoadingNip11) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingSpinner(size = 18.dp, strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(
                            onClick = { relay?.let { viewModel.loadRelayInfo(it.url, forceRefresh = true) } },
                            enabled = relay != null
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.relay_diag_refresh_nip11)
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            relay?.let { relayItem ->
                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startEditingRelay(relayItem) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.edit))
                        }
                        Button(
                            onClick = {
                                viewModel.deleteRelay(relayItem.id)
                                navController.popBackStack()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LaunchedEffect(relay?.url) {
            relay?.let { viewModel.loadRelayInfo(it.url) }
        }

        if (relay == null) {
            // Distinguishes "the relay list just hasn't loaded its first snapshot yet" (show a
            // spinner, self-corrects the moment it lands) from "this id genuinely isn't in the
            // list" (show not-found) — matters now that the feed's error banner can navigate
            // straight here as the very first screen in this graph this session, landing before
            // RelayConfigViewModel.observeRelays() has delivered anything.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                if (!state.relaysLoaded) {
                    LoadingSpinner(size = 36.dp)
                } else {
                    Text(
                        text = stringResource(R.string.relay_detail_not_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        val relayRequests = remember(state.relayRequests, relay.url) {
            // Normalized comparison — see the identical fix on relayIssues below: an exact-string
            // filter here silently hid this relay's active subscriptions (making it look like "no
            // active subs") whenever the connect-time REQ url and the currently-stored relay.url
            // differed in case/trailing-slash/whitespace, even though the subscription was fine.
            val normalizedRelayUrl = normalizeRelayUrl(relay.url)
            state.relayRequests
                .asSequence()
                .filter { normalizeRelayUrl(it.relayUrl) == normalizedRelayUrl }
                .sortedWith(
                    compareByDescending<com.umbra.app.domain.relay.RelayRequestInfo> { it.receivedEventCount }
                        .thenByDescending { it.lastEventAtMillis ?: it.updatedAtMillis }
                )
                .take(40)
                .toList()
        }
        val groupedRelaySubs = remember(relayRequests) { relayRequests.groupByPurpose() }
        val relayIssues = remember(state.relayIssues, relay.url) {
            // Normalized comparison, not exact-string: the URL a relay actually connected with
            // (captured verbatim in RelayIssue.relayUrl at connect time) and this relay's
            // currently-stored url can differ in case/trailing-slash/whitespace (e.g. after an
            // edit, or when the same relay was reached via a slightly different string) — an
            // exact-string filter would then silently show zero messages for this relay,
            // including the CONNECTED one that should always be there once it's up.
            val normalizedRelayUrl = normalizeRelayUrl(relay.url)
            state.relayIssues.filter { normalizeRelayUrl(it.relayUrl) == normalizedRelayUrl }.takeLast(50).reversed()
        }
        val relayInfo = relay.relayInfo
        val isRelayInfoLoading = relay.url in state.relayInfoLoading
        val refreshResult = state.relayInfoRefreshResult[relay.url]

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RelayIcon(iconUrl = relayInfo?.icon)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(relayDisplayName(relay, relayInfo), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = relay.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RelayCapabilityChip(label = stringResource(R.string.relay_write), enabled = relay.isEnabled && relay.isWriteActive)
                            RelayCapabilityChip(label = stringResource(R.string.relay_read), enabled = relay.isEnabled && relay.isReadActive)
                            RelayCapabilityChip(label = stringResource(R.string.relay_dm), enabled = relay.isEnabled && relay.isDmActive)
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = stringResource(R.string.relay_diag_nip11_title))

                    if (refreshResult != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (refreshResult) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (refreshResult) Icons.Default.Check else Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (refreshResult) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    }
                                )
                                Text(
                                    text = if (refreshResult) {
                                        stringResource(R.string.relay_info_refresh_ok)
                                    } else {
                                        stringResource(R.string.relay_info_refresh_error)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (refreshResult) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    }
                                )
                            }
                        }
                    }

                    when {
                        relayInfo != null -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (!relayInfo.banner.isNullOrBlank()) {
                                        AsyncImage(
                                            model = relayInfo.banner,
                                            contentDescription = stringResource(R.string.relay_diag_banner_cd),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(110.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    }

                                    // Name
                                    Text(
                                        text = relayInfo.name ?: relayDisplayName(relay, relayInfo),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    // Description
                                    val relayDescription = relayInfo.description.orEmpty().takeIf { it.isNotBlank() }
                                    if (relayDescription != null) {
                                        Text(
                                            text = relayDescription,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    relayInfo.pubkey?.takeIf { it.isNotBlank() }?.let {
                                        Nip11InfoBadgeRow(
                                            label = stringResource(R.string.relay_diag_owner_pubkey),
                                            value = it,
                                            mono = true
                                        )
                                    }
                                    relayInfo.icon?.takeIf { it.isNotBlank() }?.let { iconUrl ->
                                        Nip11InfoBadgeRow(
                                            label = stringResource(R.string.relay_diag_icon),
                                            value = iconUrl,
                                            onClick = {
                                                pendingExternalUrl = iconUrl
                                            }
                                        )
                                    }
                                    relayInfo.self?.takeIf { it.isNotBlank() }?.let {
                                        Nip11InfoBadgeRow(
                                            label = stringResource(R.string.relay_diag_self_pubkey),
                                            value = it,
                                            mono = true
                                        )
                                    }
                                    relayInfo.contact?.takeIf { it.isNotBlank() }?.let {
                                        Nip11InfoBadgeRow(
                                            label = stringResource(R.string.relay_diag_contact),
                                            value = it
                                        )
                                    }

                                    relayInfo.software?.takeIf { it.isNotBlank() }?.let { softwareUrl ->
                                        val softwareName = softwareUrl.substringAfterLast('/').ifBlank { softwareUrl }
                                        Nip11InfoBadgeRow(
                                            label = stringResource(R.string.relay_diag_software),
                                            value = softwareName,
                                            onClick = {
                                                pendingExternalUrl = softwareUrl
                                            }
                                        )
                                    }
                                    relayInfo.version?.takeIf { it.isNotBlank() }?.let {
                                        Nip11InfoBadgeRow(
                                            label = stringResource(R.string.relay_diag_version),
                                            value = it
                                        )
                                    }
                                    relayInfo.termsOfService?.takeIf { it.isNotBlank() }?.let { termsUrl ->
                                        Nip11InfoBadgeRow(
                                            label = stringResource(R.string.relay_diag_terms),
                                            value = stringResource(R.string.relay_diag_open_terms),
                                            onClick = {
                                                pendingExternalUrl = termsUrl
                                            }
                                        )
                                    }

                                    // Auth / Payment / PoW requirement badges
                                    val requirements = buildList {
                                        if (relayInfo.requiresAuth) add(stringResource(R.string.relay_requirement_auth))
                                        if (relayInfo.requiresPayment) add(stringResource(R.string.relay_requirement_payment))
                                        relayInfo.minPoW?.let {
                                            if (it > 0) add(stringResource(R.string.relay_requirement_pow, it))
                                        }
                                    }
                                    if (requirements.isNotEmpty()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            requirements.forEach { req ->
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.errorContainer
                                                ) {
                                                    Text(
                                                        text = req,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onErrorContainer
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // Limits
                                    val limitParts = buildList {
                                        relayInfo.maxSubscriptions?.let { add(stringResource(R.string.relay_limit_max_subscriptions, it)) }
                                        relayInfo.maxLimitEventCount?.let { add(stringResource(R.string.relay_limit_max_events_per_request, it)) }
                                        relayInfo.maxEventComplexity?.let { add(stringResource(R.string.relay_limit_max_tags_per_event, it)) }
                                    }
                                    if (limitParts.isNotEmpty()) {
                                        Text(
                                            text = limitParts.joinToString("  ·  "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // NIP badges
                                    if (relayInfo.supportedNips.isNotEmpty()) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(7.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.relay_diag_nips_count, relayInfo.supportedNips.size),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                relayInfo.supportedNips.sorted().forEach { nip ->
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = MaterialTheme.colorScheme.surface,
                                                        border = androidx.compose.foundation.BorderStroke(
                                                            width = 1.dp,
                                                            color = MaterialTheme.colorScheme.outlineVariant
                                                        )
                                                    ) {
                                                        Text(
                                                            text = "$nip",
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        isRelayInfoLoading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LoadingSpinner(size = 16.dp, strokeWidth = 2.dp)
                                Text(stringResource(R.string.relay_diag_loading_nip11), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        else -> {
                            SectionEmpty(text = stringResource(R.string.relay_diag_loading_nip11))
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.relay_active_subscriptions))
                if (relayRequests.isEmpty()) {
                    SectionEmpty(text = stringResource(R.string.relay_no_active_subscriptions))
                }
            }
            if (relayRequests.isNotEmpty()) {
                // Real LazyColumn items per subscription (not a plain Column nested inside one
                // `item {}`) — same fix as ActiveSubscriptionsScreen's group rendering below, for
                // the same reason: composing every card for every group up front (up to 40 here)
                // is what made opening this screen feel sluggish while the relay list is actively
                // streaming events in.
                listOf(
                    "outbox" to (R.string.relay_subscriptions_outbox to groupedRelaySubs.outbox),
                    "inbox" to (R.string.relay_subscriptions_inbox to groupedRelaySubs.inbox),
                    "feed" to (R.string.relay_subscriptions_feed to groupedRelaySubs.feed),
                    "other" to (R.string.relay_subscriptions_other to groupedRelaySubs.other)
                ).forEach { (groupKey, titleAndRequests) ->
                    val (titleRes, requests) = titleAndRequests
                    // Unlike outbox/inbox/feed, "other" is only shown when it actually has
                    // subscriptions — matches the original SubscriptionGroup behavior.
                    if (groupKey == "other" && requests.isEmpty()) return@forEach

                    item(key = "sub-group-$groupKey") {
                        Text(
                            text = stringResource(titleRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (requests.isEmpty()) {
                            Text(
                                text = stringResource(R.string.relay_no_active_subscriptions),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (requests.isNotEmpty()) {
                        items(
                            items = requests,
                            key = { "sub-$groupKey|${it.relayUrl}|${it.subscriptionId}" },
                            contentType = { "subscription_card" }
                        ) { req ->
                            SubscriptionCard(req, currentUserPubkey = state.currentUserPubkey)
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.relay_diag_issues_title))
                if (relayIssues.isEmpty()) {
                    SectionEmpty(text = stringResource(R.string.relay_diag_no_issues))
                } else {
                    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        relayIssues.forEach { issue ->
                            val bgColor = when (issue.kind) {
                                RelayIssueKind.CONNECTING -> RELAY_MESSAGE_CONNECTING_BG
                                RelayIssueKind.CONNECTED -> RELAY_MESSAGE_CONNECTED_BG
                                RelayIssueKind.AUTH -> RELAY_MESSAGE_AUTH_BG
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            val rawTextColor = when (issue.kind) {
                                RelayIssueKind.CONNECTING -> RELAY_MESSAGE_CONNECTING_FG
                                RelayIssueKind.CONNECTED -> RELAY_MESSAGE_CONNECTED_FG
                                RelayIssueKind.AUTH -> RELAY_MESSAGE_AUTH_FG
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = bgColor
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        text = stringResource(R.string.relay_diag_issue_header, issue.kind.name, timeFormatter.format(Date(issue.timestampMs))),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = relayIssueColor(issue.kind)
                                    )
                                    Text(issue.rawMessage, style = MaterialTheme.typography.labelSmall, color = rawTextColor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showAddDialog) {
        RelayEditDialog(
            relay = state.editingRelay,
            addRole = state.addRole,
            onSave = { relayToSave -> viewModel.saveRelay(relayToSave) },
            onDismiss = { viewModel.closeAddDialog() }
        )
    }
}

@Composable
private fun RelayCapabilityChip(label: String, enabled: Boolean) {
    ChipBadge(
        text = if (enabled) "$label ON" else "$label OFF",
        backgroundColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        textColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun relayIssueColor(kind: RelayIssueKind) = when (kind) {
    RelayIssueKind.RATE_LIMIT,
    RelayIssueKind.SUBSCRIPTION_LIMIT,
    RelayIssueKind.DUPLICATE_SUBSCRIPTION -> MaterialTheme.colorScheme.tertiary
    RelayIssueKind.BLOCKED,
    RelayIssueKind.NETWORK,
    RelayIssueKind.TLS,
    RelayIssueKind.CLEARTEXT_BLOCKED,
    RelayIssueKind.REQ_UNSUPPORTED,
    RelayIssueKind.SEARCH_REQUIRED,
    RelayIssueKind.NEGENTROPY_UNSUPPORTED,
    RelayIssueKind.TOR_CIRCUITS_LIKELY_DEAD,
    RelayIssueKind.AUTO_DISABLED -> MaterialTheme.colorScheme.error
    // AUTH is "asking for auth," not a failure — same amber semantic PENDING_AMBER already
    // represents elsewhere in the app (NIP-05-pending, Tor-starting), not the error role.
    RelayIssueKind.AUTH -> RELAY_MESSAGE_AUTH_FG
    RelayIssueKind.CONNECTING -> RELAY_MESSAGE_CONNECTING_FG
    RelayIssueKind.CONNECTED,
    RelayIssueKind.TOR_CIRCUITS_RECOVERED -> RELAY_MESSAGE_CONNECTED_FG
    RelayIssueKind.NOTICE,
    RelayIssueKind.UNKNOWN -> MaterialTheme.colorScheme.primary
}

// Fixed background/foreground pairs for the "connecting"/"connected"/"auth" relay-message
// semantics — deliberately not reused Material tertiary/error roles, which vary per user-selected
// theme palette (see AppTheme.kt) and don't reliably read as blue/green/amber in every palette.
// Same "fixed regardless of the active palette" precedent as PENDING_AMBER
// (UserIdentityBadge.kt/FeedScreen.kt) and the fixed chip colors in RelaySubscriptionComponents.kt.
private val RELAY_MESSAGE_CONNECTING_BG = Color(0xFF1A3A5C)
private val RELAY_MESSAGE_CONNECTING_FG = Color(0xFF64B5F6)
private val RELAY_MESSAGE_CONNECTED_BG = Color(0xFF1B3B22)
private val RELAY_MESSAGE_CONNECTED_FG = Color(0xFF66BB6A)
// Matches PENDING_AMBER's exact hex (UserIdentityBadge.kt/NostrImageComponents.kt) so this reads
// as the same "pending/in-progress, not an error" visual language used elsewhere in the app.
private val RELAY_MESSAGE_AUTH_BG = Color(0xFF4A3B12)
private val RELAY_MESSAGE_AUTH_FG = Color(0xFFF9A825)

@Composable
private fun SectionEmpty(text: String) {
    EmptyState(
        title = text,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Nip11InfoBadgeRow(
    label: String,
    value: String,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier
                .width(94.dp)
                .defaultMinSize(minHeight = 30.dp),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        val valueModifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            modifier = valueModifier
                .weight(1f)
                .defaultMinSize(minHeight = 30.dp)
        ) {
            Text(
                text = if (mono) value.take(16) + if (value.length > 16) "..." else "" else value,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                style = if (mono) {
                    MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.labelSmall
                },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = if (mono) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
