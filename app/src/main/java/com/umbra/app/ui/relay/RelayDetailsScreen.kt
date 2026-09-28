package com.umbra.app.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.umbra.app.ui.Screen
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.umbra.app.R
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.groupByPurpose
import com.umbra.app.domain.relay.normalizeRelayUrl
import java.text.NumberFormat
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.ConfirmDialog
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.InlineEmptyText
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.SettingsGroup
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.launchExternalUrl
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** NIPs Umbra actively uses when a relay supports them — highlighted in the NIP list. */
private val HEX_PUBKEY = Regex("^[0-9a-fA-F]{64}$")

private val NIPS_UMBRA_USES = setOf(1, 9, 11, 42, 45, 50, 65, 77)

@Composable
fun RelayDetailsScreen(
    navController: NavController,
    relayId: String,
    viewModel: RelayConfigViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val relay = state.relays.firstOrNull { it.id == relayId }

    if (confirmDelete && relay != null) {
        ConfirmDialog(
            title = stringResource(R.string.relay_delete_confirm_title),
            message = stringResource(R.string.relay_delete_confirm_message),
            confirmLabel = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteRelay(relay.id)
                navController.popBackStack()
            },
            onDismiss = { confirmDelete = false }
        )
    }

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

    LaunchedEffect(relay?.url) {
        relay?.let { viewModel.loadRelayInfo(it.url) }
    }

    // Normalized comparisons below, not exact-string: the URL a relay connected with (captured in
    // requests/issues at connect time) and the stored relay.url can differ in case, trailing slash
    // or whitespace, and an exact match would silently show nothing for this relay.
    val normalizedUrl = relay?.url?.let(::normalizeRelayUrl)
    val relayRequests = remember(state.relayRequests, normalizedUrl) {
        state.relayRequests
            .asSequence()
            .filter { normalizeRelayUrl(it.relayUrl) == normalizedUrl }
            .sortedWith(
                compareByDescending<RelayRequestInfo> { it.receivedEventCount }
                    .thenByDescending { it.lastEventAtMillis ?: it.updatedAtMillis }
            )
            .take(40)
            .toList()
    }
    val connectionState = normalizedUrl?.let { state.relayConnectionStates[it] }
    val supportsCount = relay?.relayInfo?.supportedNips?.contains(45) == true
    val isConnected = connectionState == RelayConnectionIndicatorState.CONNECTED
    LaunchedEffect(normalizedUrl, supportsCount, isConnected) {
        if (normalizedUrl != null && supportsCount && isConnected) viewModel.loadRelayCounts(normalizedUrl)
    }
    val relayIssues = remember(state.relayIssues, normalizedUrl) {
        state.relayIssues.filter { normalizeRelayUrl(it.relayUrl) == normalizedUrl }.takeLast(50).reversed()
    }

    val ownerPubkey = relay?.relayInfo?.pubkey?.takeIf { HEX_PUBKEY.matches(it) }?.lowercase()
    val ownerProfile by remember(ownerPubkey) {
        ownerPubkey?.let(viewModel::observeOwnerProfile) ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)

    RelayDetailsContent(
        relay = relay,
        ownerName = ownerProfile?.getUserDisplayName()?.takeIf { it.isNotBlank() },
        onOpenOwner = ownerPubkey?.let { pk -> { navController.navigate(Screen.Profile.forPubkey(pk)) } },
        relaysLoaded = state.relaysLoaded,
        connectionState = connectionState,
        ownCounts = normalizedUrl?.let { state.relayCounts[it] },
        isInfoLoading = relay?.url?.let { it in state.relayInfoLoading } ?: false,
        refreshResult = relay?.url?.let { state.relayInfoRefreshResult[it] },
        requests = relayRequests,
        issues = relayIssues,
        currentUserPubkey = state.currentUserPubkey,
        onNavigateBack = { navController.popBackStack() },
        onRefreshInfo = { relay?.let { viewModel.loadRelayInfo(it.url, forceRefresh = true) } },
        onEdit = { relay?.let(viewModel::startEditingRelay) },
        onDelete = { confirmDelete = true },
        onOpenUrl = { pendingExternalUrl = it }
    )

    if (state.showAddDialog) {
        RelayEditDialog(
            relay = state.editingRelay,
            addRole = state.addRole,
            onSave = { relayToSave -> viewModel.saveRelay(relayToSave) },
            onDismiss = { viewModel.closeAddDialog() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelayDetailsContent(
    relay: Relay?,
    ownerName: String?,
    onOpenOwner: (() -> Unit)?,
    relaysLoaded: Boolean,
    connectionState: RelayConnectionIndicatorState?,
    ownCounts: RelayOwnCounts?,
    isInfoLoading: Boolean,
    refreshResult: Boolean?,
    requests: List<RelayRequestInfo>,
    issues: List<RelayIssue>,
    currentUserPubkey: String?,
    onNavigateBack: () -> Unit,
    onRefreshInfo: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.relay_detail_title)) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) },
                actions = {
                    if (isInfoLoading) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            LoadingSpinner(size = 18.dp, strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = onRefreshInfo, enabled = relay != null) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.relay_diag_refresh_nip11))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (relay != null) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            // Deleting is deliberate: quiet, behind a confirmation, never an
                            // equal-weight filled twin of Edit.
                            OutlinedButton(
                                onClick = onDelete,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.delete))
                            }
                            Button(onClick = onEdit, modifier = Modifier.weight(1f).height(48.dp)) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.edit))
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (relay == null) {
            // "The relay list hasn't delivered its first snapshot yet" (spinner, self-corrects)
            // vs "this id isn't in the list" (not found) — the feed's error banner can land here
            // as the first screen of the relay graph, before relays have loaded.
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                if (!relaysLoaded) {
                    LoadingSpinner(size = 36.dp)
                } else {
                    Text(stringResource(R.string.relay_detail_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }

        val info = relay.relayInfo
        val grouped = remember(requests) { requests.groupByPurpose() }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "hero", contentType = "hero") {
                RelayHero(relay = relay, info = info, connectionState = connectionState)
            }

            if (refreshResult == false) {
                item(key = "refresh", contentType = "notice") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.relay_info_refresh_error),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            item(key = "about", contentType = "group") {
                SettingsGroup(title = stringResource(R.string.relay_diag_nip11_title)) {
                    if (info == null) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isInfoLoading) LoadingSpinner(size = 16.dp, strokeWidth = 2.dp)
                            InlineEmptyText(stringResource(R.string.relay_diag_loading_nip11))
                        }
                    } else {
                        val rows = buildList<Triple<String, String, Pair<Boolean, String?>>> {
                            info.pubkey?.takeIf { it.isNotBlank() }?.let { add(Triple("owner", ownerName ?: it, (ownerName == null) to null)) }
                            info.self?.takeIf { it.isNotBlank() }?.let { add(Triple("self", it, true to null)) }
                            info.contact?.takeIf { it.isNotBlank() }?.let { add(Triple("contact", it, false to null)) }
                            info.software?.takeIf { it.isNotBlank() }?.let {
                                add(Triple("software", it.substringAfterLast('/').ifBlank { it }, false to it))
                            }
                            info.version?.takeIf { it.isNotBlank() }?.let { add(Triple("version", it, true to null)) }
                            info.termsOfService?.takeIf { it.isNotBlank() }?.let { add(Triple("terms", "", false to it)) }
                        }
                        rows.forEachIndexed { index, (key, value, extra) ->
                            val (mono, link) = extra
                            InfoRow(
                                label = stringResource(
                                    when (key) {
                                        "owner" -> R.string.relay_diag_owner_pubkey
                                        "self" -> R.string.relay_diag_self_pubkey
                                        "contact" -> R.string.relay_diag_contact
                                        "software" -> R.string.relay_diag_software
                                        "version" -> R.string.relay_diag_version
                                        else -> R.string.relay_diag_terms
                                    }
                                ),
                                value = if (key == "terms") stringResource(R.string.relay_diag_open_terms) else value,
                                mono = mono,
                                onClick = if (key == "owner") onOpenOwner else link?.let { url -> { onOpenUrl(url) } },
                                showDivider = index < rows.lastIndex
                            )
                        }
                        if (rows.isEmpty()) {
                            InlineEmptyText(stringResource(R.string.relay_diag_no_details), Modifier.padding(16.dp))
                        }
                    }
                }
            }

            if (ownCounts != null) {
                item(key = "counts", contentType = "group") {
                    RelayOwnCountsGroup(ownCounts)
                }
            }

            if (info != null) {
                val requirements = buildList {
                    if (info.requiresAuth) add(R.string.relay_requirement_auth to null)
                    if (info.requiresPayment) add(R.string.relay_requirement_payment to null)
                    info.minPoW?.takeIf { it > 0 }?.let { add(R.string.relay_requirement_pow to it) }
                }
                val limits = buildList {
                    info.maxSubscriptions?.let { add(R.string.relay_limit_max_subscriptions to it) }
                    info.maxLimitEventCount?.let { add(R.string.relay_limit_max_events_per_request to it) }
                    info.maxEventComplexity?.let { add(R.string.relay_limit_max_tags_per_event to it) }
                }
                if (requirements.isNotEmpty() || limits.isNotEmpty()) {
                    item(key = "limits", contentType = "group") {
                        SettingsGroup(title = stringResource(R.string.relay_diag_limits_title)) {
                            if (requirements.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    requirements.forEach { (res, arg) ->
                                        // A requirement limits what Umbra can do there — cautionary, not an error.
                                        ChipBadge(
                                            text = if (arg != null) stringResource(res, arg) else stringResource(res),
                                            backgroundColor = UmbraTheme.colors.caution.copy(alpha = 0.14f),
                                            textColor = UmbraTheme.colors.caution
                                        )
                                    }
                                }
                            }
                            limits.forEach { (res, value) ->
                                Text(
                                    text = stringResource(res, value),
                                    style = MonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                if (info.supportedNips.isNotEmpty()) {
                    item(key = "nips", contentType = "group") {
                        SettingsGroup(title = stringResource(R.string.relay_diag_nips_count, info.supportedNips.size)) {
                            NipGrid(info.supportedNips)
                        }
                    }
                }
            }

            item(key = "subs-header", contentType = "header") {
                SectionTitle(stringResource(R.string.relay_active_subscriptions))
                if (requests.isEmpty()) {
                    InlineEmptyText(
                        stringResource(R.string.relay_no_active_subscriptions),
                        Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
            // Real LazyColumn items per subscription so up to 40 cards aren't composed up front
            // while the relay is streaming.
            listOf(
                "outbox" to (R.string.relay_subscriptions_outbox to grouped.outbox),
                "inbox" to (R.string.relay_subscriptions_inbox to grouped.inbox),
                "feed" to (R.string.relay_subscriptions_feed to grouped.feed),
                "other" to (R.string.relay_subscriptions_other to grouped.other)
            ).forEach { (groupKey, titleAndRequests) ->
                val (titleRes, groupRequests) = titleAndRequests
                if (groupRequests.isEmpty()) return@forEach
                item(key = "sub-group-$groupKey", contentType = "group_label") {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp)
                    )
                }
                items(
                    items = groupRequests,
                    key = { "sub-$groupKey|${it.relayUrl}|${it.subscriptionId}" },
                    contentType = { "subscription_card" }
                ) { req ->
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SubscriptionCard(req, currentUserPubkey = currentUserPubkey)
                    }
                }
            }

            item(key = "log", contentType = "log") {
                SectionTitle(stringResource(R.string.relay_diag_issues_title))
                if (issues.isEmpty()) {
                    InlineEmptyText(stringResource(R.string.relay_diag_no_issues), Modifier.padding(horizontal = 20.dp))
                } else {
                    RelayLog(issues)
                }
            }
        }
    }
}

/** NIP-45 answers about the signed-in user; a row the relay hasn't answered is left out. */
@Composable
private fun RelayOwnCountsGroup(counts: RelayOwnCounts) {
    val rows = listOfNotNull(
        counts.yourEvents?.let { R.string.relay_counts_your_events to it },
        counts.mentions?.let { R.string.relay_counts_mentions to it }
    )
    SettingsGroup(title = stringResource(R.string.relay_counts_title)) {
        rows.forEachIndexed { index, (label, value) ->
            val formatted = NumberFormat.getIntegerInstance().format(value)
            InfoRow(
                label = stringResource(label),
                value = if (counts.approximate) stringResource(R.string.relay_counts_approximate, formatted) else formatted,
                mono = true,
                onClick = null,
                showDivider = index < rows.lastIndex
            )
        }
        Text(
            text = stringResource(R.string.relay_counts_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelayHero(relay: Relay, info: RelayInfo?, connectionState: RelayConnectionIndicatorState?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                )
        ) {
            val banner = info?.banner
            if (!banner.isNullOrBlank()) {
                AsyncImage(
                    model = banner,
                    contentDescription = stringResource(R.string.relay_diag_banner_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)))
            )
            ConnectionPill(connectionState, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp)) {
                    RelayIcon(iconUrl = info?.icon, isOnion = relay.isOnion || relay.url.contains(".onion"))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = relayDisplayName(relay, info),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = relay.url,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                }
            }
            info?.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RelayCapabilityChip(label = stringResource(R.string.relay_read), enabled = relay.isEnabled && relay.isReadActive)
                RelayCapabilityChip(label = stringResource(R.string.relay_write), enabled = relay.isEnabled && relay.isWriteActive)
                RelayCapabilityChip(label = stringResource(R.string.relay_dm), enabled = relay.isEnabled && relay.isDmActive)
            }
        }
    }
}

@Composable
private fun ConnectionPill(state: RelayConnectionIndicatorState?, modifier: Modifier = Modifier) {
    val (color, label) = when (state) {
        RelayConnectionIndicatorState.CONNECTED -> UmbraTheme.colors.secure to R.string.relay_state_connected
        RelayConnectionIndicatorState.FAILED -> MaterialTheme.colorScheme.error to R.string.relay_state_failed
        RelayConnectionIndicatorState.DISABLED -> MaterialTheme.colorScheme.outline to R.string.relay_state_disabled
        RelayConnectionIndicatorState.CONNECTING, null -> UmbraTheme.colors.caution to R.string.relay_state_connecting
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NipGrid(nips: List<Int>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        nips.sorted().forEach { nip ->
            val used = nip in NIPS_UMBRA_USES
            Text(
                text = nip.toString().padStart(2, '0'),
                style = MonoStyle,
                color = if (used) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (used) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
    Text(
        text = stringResource(R.string.relay_diag_nips_used_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
    )
}

@Composable
private fun RelayLog(issues: List<RelayIssue>) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    // A log, so it reads like one: time in mono, a coloured status dot, the message.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 6.dp)
    ) {
        issues.forEach { issue ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(relayIssueColor(issue.kind))
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(timeFormatter.format(Date(issue.timestampMs)), style = MonoStyle, color = MaterialTheme.colorScheme.outline)
                        Text(issue.kind.displayName(), style = MaterialTheme.typography.labelMedium, color = relayIssueColor(issue.kind))
                    }
                    Text(issue.rawMessage, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean, onClick: (() -> Unit)?, showDivider: Boolean) {
    // Values that open something are link-coloured and always go through ExternalUrlWarningDialog.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = if (mono) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
    if (showDivider) {
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun RelayCapabilityChip(label: String, enabled: Boolean) {
    val tint = if (enabled) UmbraTheme.colors.secure else MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(
        if (enabled) R.string.relay_capability_on_cd else R.string.relay_capability_off_cd,
        label
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (enabled) Icons.Default.Check else Icons.Default.Remove,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun relayIssueColor(kind: RelayIssueKind) = when (kind) {
    RelayIssueKind.BLOCKED,
    RelayIssueKind.NETWORK,
    RelayIssueKind.TLS,
    RelayIssueKind.CLEARTEXT_BLOCKED,
    RelayIssueKind.REQ_UNSUPPORTED,
    RelayIssueKind.SEARCH_REQUIRED,
    RelayIssueKind.NEGENTROPY_UNSUPPORTED,
    RelayIssueKind.TOR_CIRCUITS_LIKELY_DEAD,
    RelayIssueKind.AUTO_DISABLED -> MaterialTheme.colorScheme.error
    // Limits and auth are "the relay wants something", not failures.
    RelayIssueKind.RATE_LIMIT,
    RelayIssueKind.SUBSCRIPTION_LIMIT,
    RelayIssueKind.DUPLICATE_SUBSCRIPTION,
    RelayIssueKind.AUTH,
    RelayIssueKind.CONNECTING -> UmbraTheme.colors.caution
    RelayIssueKind.CONNECTED,
    RelayIssueKind.TOR_CIRCUITS_RECOVERED -> UmbraTheme.colors.secure
    RelayIssueKind.NOTICE,
    RelayIssueKind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "TOR_CIRCUITS_LIKELY_DEAD" → "Tor circuits likely dead": readable, still greppable. */
private fun RelayIssueKind.displayName(): String =
    name.lowercase().replace('_', ' ').replace("tor ", "Tor ").replaceFirstChar { it.uppercase() }

