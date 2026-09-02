package com.umbra.app.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.nip77.SyncDirection
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.ui.components.launchExternalUrl
import com.umbra.app.ui.components.ErrorBanner
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.InfoIcon
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.relayRoleSection
import kotlin.OptIn
import androidx.compose.material3.ExperimentalMaterial3Api
import com.umbra.app.ui.Screen
import com.umbra.app.ui.common.resolve
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage

/**
 * Relay configuration screen
 * Allows users to add, edit, and manage Nostr relays
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RelayConfigScreen(
    navController: NavController,
    viewModel: RelayConfigViewModel
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }

    // publishRelayLists()'s sign_event/nip44_encrypt round trips go through the single app-wide
    // Amber launcher (see AppSessionEffects) now — no per-screen launcher needed here anymore.

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        val hasUnpublishedChanges = state.relayListDirty || state.dmRelayListDirty ||
            state.searchListDirty || state.indexListDirty
        UmbraTopAppBar(
            title = { Text(stringResource(R.string.relay_config_title)) },
            navigationIcon = {
                UmbraTopAppBarDefaults.BackNavigationIcon(onClick = { navController.popBackStack() })
            },
            actions = {
                if (hasUnpublishedChanges || state.isPublishing) {
                    IconButton(
                        onClick = { viewModel.publishRelayLists() },
                        enabled = hasUnpublishedChanges && !state.isPublishing
                    ) {
                        if (state.isPublishing) {
                            LoadingSpinner(size = 20.dp, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.relay_publish_lists_action)
                            )
                        }
                    }
                }
            }
        )

        // Error message
        if (state.errorMessage != null) {
            ErrorBanner(
                message = state.errorMessage!!.resolve(context),
                onDismiss = { viewModel.clearError() }
            )
        }

        // Relays list — relayBuckets/relayConnectionStates/telemetrySnapshot are computed in
        // RelayConfigViewModel on Dispatchers.Default (see observeDerivedRelayState()), not here.
        // They used to be remember{} blocks recomputed synchronously on the main thread during
        // this screen's first composition after every navigate() into it — an O(relays × issues)
        // scan (relayConnectionStates) that's exactly the kind of first-frame cost that made
        // opening this screen feel laggy even after the screen-scoped-ViewModel fix (nested nav
        // graph) removed the *redundant* per-navigation recomputation; this removes the
        // remaining per-open cost itself.
        val relayBuckets = state.relayBuckets
        val relayConnectionStates = state.relayConnectionStates
        val telemetrySnapshot = state.telemetrySnapshot
        val outboxTitle = stringResource(R.string.relay_section_outbox)
        val inboxTitle = stringResource(R.string.relay_section_inbox)
        val dmTitle = stringResource(R.string.relay_section_dm)
        val searchTitle = stringResource(R.string.relay_search_relays_title)
        val indexTitle = stringResource(R.string.relay_index_relays_title)
        val discoveredTitle = stringResource(R.string.relay_section_discovered, relayBuckets.discoveredConnected.size)
        val discoveredDisconnectedTitle = stringResource(R.string.relay_section_discovered_disconnected, relayBuckets.discoveredOther.size)
        val discoveredDisabledTitle = stringResource(R.string.relay_section_discovered_disabled, relayBuckets.discoveredDisabled.size)
        val addRelayLabel = stringResource(R.string.add_relay)
        val outboxEmpty = stringResource(R.string.relay_section_outbox_empty)
        val inboxEmpty = stringResource(R.string.relay_section_inbox_empty)
        val dmEmpty = stringResource(R.string.relay_section_dm_empty)
        val searchEmpty = stringResource(R.string.relay_section_search_relays_empty)
        val indexEmpty = stringResource(R.string.relay_section_index_relays_empty)
        val discoveredEmpty = stringResource(R.string.relay_section_discovered_empty)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                NegentropySyncCard(
                    direction = state.negentropySyncDirection,
                    onDirectionChange = viewModel::setNegentropySyncDirection
                )
            }

            if (state.showRelayTelemetry) {
                item {
                    RelayTelemetryCard(
                        telemetry = telemetrySnapshot,
                        onSubscriptionsClick = { navController.navigate(Screen.ActiveSubscriptions.route) }
                    )
                }
            }

            relayRoleSection(
                title = outboxTitle,
                actionLabel = addRelayLabel,
                emptyTitle = outboxEmpty,
                items = relayBuckets.outbox,
                keyFactory = { "out-${it.id}" },
                onAction = { viewModel.openAddDialog(RelayRole.OUTBOX) },
                infoContent = { InfoIcon(title = outboxTitle, message = stringResource(R.string.relay_help_outbox_body)) }
            ) { relay ->
                // Remembered per relay.id so an unrelated relay's status/issue update (which
                // changes relayConnectionStates/state and recomposes this whole section) doesn't
                // hand every row a fresh callback identity — that would defeat RelayCard's own
                // recomposition-skip for rows whose own data hasn't actually changed.
                val onToggle = remember(relay.id) { { enabled: Boolean -> viewModel.setOutboxEnabled(relay.id, enabled) } }
                val onDelete = remember(relay.id) { { viewModel.removeRelayRole(relay.id, RelayRole.OUTBOX) } }
                val onOpenDetails = remember(relay.id) { { navController.navigate(Screen.RelayDetails.forRelay(relay.id)) } }
                RelayCard(
                    relay = relay,
                    relayInfo = relay.relayInfo,
                    relayConnectionState = relayConnectionStates[normalizeRelayUrl(relay.url)] ?: RelayConnectionIndicatorState.CONNECTING,
                    switchChecked = relay.isEnabled && relay.isWriteActive,
                    switchEnabled = true,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onOpenDetails = onOpenDetails
                )
            }

            relayRoleSection(
                title = inboxTitle,
                actionLabel = addRelayLabel,
                emptyTitle = inboxEmpty,
                items = relayBuckets.inbox,
                keyFactory = { "in-${it.id}" },
                onAction = { viewModel.openAddDialog(RelayRole.INBOX) },
                infoContent = { InfoIcon(title = inboxTitle, message = stringResource(R.string.relay_help_inbox_body)) }
            ) { relay ->
                val onToggle = remember(relay.id) { { enabled: Boolean -> viewModel.setInboxEnabled(relay.id, enabled) } }
                val onDelete = remember(relay.id) { { viewModel.removeRelayRole(relay.id, RelayRole.INBOX) } }
                val onOpenDetails = remember(relay.id) { { navController.navigate(Screen.RelayDetails.forRelay(relay.id)) } }
                RelayCard(
                    relay = relay,
                    relayInfo = relay.relayInfo,
                    relayConnectionState = relayConnectionStates[normalizeRelayUrl(relay.url)] ?: RelayConnectionIndicatorState.CONNECTING,
                    switchChecked = relay.isEnabled && relay.isReadActive,
                    switchEnabled = !state.isAnonymousSession,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onOpenDetails = onOpenDetails
                )
            }

            relayRoleSection(
                title = dmTitle,
                actionLabel = addRelayLabel,
                emptyTitle = dmEmpty,
                items = relayBuckets.dm,
                keyFactory = { "dm-${it.id}" },
                onAction = { viewModel.openAddDialog(RelayRole.DM) },
                infoContent = { InfoIcon(title = dmTitle, message = stringResource(R.string.relay_help_dm_body)) }
            ) { relay ->
                val onToggle = remember(relay.id) { { enabled: Boolean -> viewModel.setDmEnabled(relay.id, enabled) } }
                val onDelete = remember(relay.id) { { viewModel.removeRelayRole(relay.id, RelayRole.DM) } }
                val onOpenDetails = remember(relay.id) { { navController.navigate(Screen.RelayDetails.forRelay(relay.id)) } }
                RelayCard(
                    relay = relay,
                    relayInfo = relay.relayInfo,
                    relayConnectionState = relayConnectionStates[normalizeRelayUrl(relay.url)] ?: RelayConnectionIndicatorState.CONNECTING,
                    switchChecked = relay.isEnabled && relay.isDmActive,
                    switchEnabled = !state.isAnonymousSession,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onOpenDetails = onOpenDetails
                )
            }

            // Search/index are first-class roles (isSearchEnabled/isIndexEnabled) the same way
            // Outbox/Inbox/DM are — sourced from relayBuckets, which is itself derived from the
            // relay table, so an incoming kind:10007/10086 declaration for the current user
            // (applied via UserRepositoryImpl.applySearchRelayListToLocalConfig/
            // applyIndexRelayListToLocalConfig) shows up here the same way a NIP-65/kind:10050
            // change already does — not as a separate read-only list that could silently disagree
            // with what actually got persisted.
            relayRoleSection(
                title = searchTitle,
                actionLabel = addRelayLabel,
                emptyTitle = searchEmpty,
                items = relayBuckets.search,
                keyFactory = { "search-${it.id}" },
                onAction = { viewModel.openAddDialog(RelayRole.SEARCH) },
                infoContent = { InfoIcon(title = searchTitle, message = stringResource(R.string.relay_help_search_body)) }
            ) { relay ->
                val onToggle = remember(relay.id) { { enabled: Boolean -> viewModel.setSearchEnabled(relay.id, enabled) } }
                val onDelete = remember(relay.id) { { viewModel.removeRelayRole(relay.id, RelayRole.SEARCH) } }
                val onOpenDetails = remember(relay.id) { { navController.navigate(Screen.RelayDetails.forRelay(relay.id)) } }
                RelayCard(
                    relay = relay,
                    relayInfo = relay.relayInfo,
                    relayConnectionState = relayConnectionStates[normalizeRelayUrl(relay.url)] ?: RelayConnectionIndicatorState.CONNECTING,
                    switchChecked = relay.isEnabled && relay.isSearchActive,
                    switchEnabled = true,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onOpenDetails = onOpenDetails
                )
            }

            relayRoleSection(
                title = indexTitle,
                actionLabel = addRelayLabel,
                emptyTitle = indexEmpty,
                items = relayBuckets.index,
                keyFactory = { "index-${it.id}" },
                onAction = { viewModel.openAddDialog(RelayRole.INDEX) },
                infoContent = { InfoIcon(title = indexTitle, message = stringResource(R.string.relay_help_index_body)) }
            ) { relay ->
                val onToggle = remember(relay.id) { { enabled: Boolean -> viewModel.setIndexEnabled(relay.id, enabled) } }
                val onDelete = remember(relay.id) { { viewModel.removeRelayRole(relay.id, RelayRole.INDEX) } }
                val onOpenDetails = remember(relay.id) { { navController.navigate(Screen.RelayDetails.forRelay(relay.id)) } }
                RelayCard(
                    relay = relay,
                    relayInfo = relay.relayInfo,
                    relayConnectionState = relayConnectionStates[normalizeRelayUrl(relay.url)] ?: RelayConnectionIndicatorState.CONNECTING,
                    switchChecked = relay.isEnabled && relay.isIndexActive,
                    switchEnabled = true,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onOpenDetails = onOpenDetails
                )
            }

            if (relayBuckets.discoveredConnected.isNotEmpty()) {
                relayRoleSection(
                    title = discoveredTitle,
                    emptyTitle = discoveredEmpty,
                    items = relayBuckets.discoveredConnected,
                    keyFactory = { "disc-${it.id}" },
                    infoContent = { InfoIcon(title = discoveredTitle, message = stringResource(R.string.relay_help_discovered_body)) }
                ) { relay ->
                    // Not setInboxEnabled — a discovered relay never carries a real read/inbox
                    // declaration of its own; see RelayConfigViewModel.setDiscoveredRelayEnabled.
                    val onToggle = remember(relay.id) { { enabled: Boolean -> viewModel.setDiscoveredRelayEnabled(relay.id, enabled) } }
                    val onOpenDetails = remember(relay.id) { { navController.navigate(Screen.RelayDetails.forRelay(relay.id)) } }
                    RelayCard(
                        relay = relay,
                        relayInfo = relay.relayInfo,
                        relayConnectionState = relayConnectionStates[normalizeRelayUrl(relay.url)] ?: RelayConnectionIndicatorState.CONNECTING,
                        switchChecked = relay.isEnabled,
                        switchEnabled = !state.isAnonymousSession,
                        onToggle = onToggle,
                        onDelete = null,
                        onOpenDetails = onOpenDetails
                    )
                }
            }

            if (relayBuckets.discoveredOther.isNotEmpty()) {
                relayRoleSection(
                    title = discoveredDisconnectedTitle,
                    emptyTitle = discoveredEmpty,
                    items = relayBuckets.discoveredOther,
                    keyFactory = { "disc-other-${it.id}" },
                    infoContent = { InfoIcon(title = discoveredDisconnectedTitle, message = stringResource(R.string.relay_help_discovered_body)) }
                ) { relay ->
                    // Not setInboxEnabled — a discovered relay never carries a real read/inbox
                    // declaration of its own; see RelayConfigViewModel.setDiscoveredRelayEnabled.
                    val onToggle = remember(relay.id) { { enabled: Boolean -> viewModel.setDiscoveredRelayEnabled(relay.id, enabled) } }
                    val onOpenDetails = remember(relay.id) { { navController.navigate(Screen.RelayDetails.forRelay(relay.id)) } }
                    RelayCard(
                        relay = relay,
                        relayInfo = relay.relayInfo,
                        relayConnectionState = relayConnectionStates[normalizeRelayUrl(relay.url)] ?: RelayConnectionIndicatorState.CONNECTING,
                        switchChecked = relay.isEnabled,
                        switchEnabled = !state.isAnonymousSession,
                        onToggle = onToggle,
                        onDelete = null,
                        onOpenDetails = onOpenDetails
                    )
                }
            }

            if (relayBuckets.discoveredDisabled.isNotEmpty()) {
                relayRoleSection(
                    title = discoveredDisabledTitle,
                    emptyTitle = discoveredEmpty,
                    items = relayBuckets.discoveredDisabled,
                    keyFactory = { "disc-disabled-${it.id}" },
                    infoContent = { InfoIcon(title = discoveredDisabledTitle, message = stringResource(R.string.relay_help_discovered_body)) }
                ) { relay ->
                    // Not setInboxEnabled — a discovered relay never carries a real read/inbox
                    // declaration of its own; see RelayConfigViewModel.setDiscoveredRelayEnabled.
                    val onToggle = remember(relay.id) { { enabled: Boolean -> viewModel.setDiscoveredRelayEnabled(relay.id, enabled) } }
                    val onOpenDetails = remember(relay.id) { { navController.navigate(Screen.RelayDetails.forRelay(relay.id)) } }
                    RelayCard(
                        relay = relay,
                        relayInfo = relay.relayInfo,
                        relayConnectionState = relayConnectionStates[normalizeRelayUrl(relay.url)] ?: RelayConnectionIndicatorState.CONNECTING,
                        switchChecked = relay.isEnabled,
                        switchEnabled = !state.isAnonymousSession,
                        onToggle = onToggle,
                        onDelete = null,
                        onOpenDetails = onOpenDetails
                    )
                }
            }
        }
    }

    if (state.isAnonymousSession) {
        Surface(
            tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.relay_anonymous_inbox_dm_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }

    // Add/Edit relay dialog
    if (state.showAddDialog) {
        RelayEditDialog(
            relay = state.editingRelay,
            addRole = state.addRole,
            onSave = { relay -> viewModel.saveRelay(relay) },
            onDismiss = { viewModel.closeAddDialog() }
        )
    }
}

/**
 * User-owned NIP-77 sync direction control — unlike [RelayTelemetryCard], NOT dev-flag gated,
 * since this is a real setting the user can act on, not diagnostics. Persisted via
 * [com.umbra.app.domain.preferences.SyncPreferences] (see [RelayConfigViewModel.setNegentropySyncDirection]).
 * [SingleChoiceSegmentedButtonRow]/[SegmentedButton] is the first use of this Material3 primitive
 * in the codebase — there's no existing 3-way exclusive-choice pattern to reuse; `FilterChip`
 * elsewhere in this screen is a read-only display chip, not a selection control.
 */
@Composable
private fun NegentropySyncCard(direction: SyncDirection, onDirectionChange: (SyncDirection) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.relay_sync_direction_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.relay_sync_direction_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val options = listOf(
            SyncDirection.DOWNLOAD_ONLY to stringResource(R.string.relay_sync_direction_download_only),
            SyncDirection.UPLOAD_ONLY to stringResource(R.string.relay_sync_direction_upload_only),
            SyncDirection.BOTH to stringResource(R.string.relay_sync_direction_both)
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = direction == value,
                    onClick = { onDirectionChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {}
                ) {
                    Text(label)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelayTelemetryCard(telemetry: RelayTelemetrySnapshot, onSubscriptionsClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.relay_telemetry_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(
                R.string.relay_telemetry_summary,
                telemetry.connectedNow,
                telemetry.active,
                telemetry.configured
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ChipBadge(
                text = stringResource(R.string.relay_telemetry_subscriptions, telemetry.liveSubscriptions),
                onClick = onSubscriptionsClick
            )
            ChipBadge(text = stringResource(R.string.relay_telemetry_events, telemetry.totalReceivedEvents))
            ChipBadge(text = stringResource(R.string.relay_telemetry_issues, telemetry.nonConnectedIssues))
        }
    }
}

/**
 * Relay card component
 * Displays relay information and controls
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayCard(
    relay: Relay,
    relayInfo: RelayInfo?,
    relayConnectionState: RelayConnectionIndicatorState,
    switchChecked: Boolean,
    switchEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    // null hides the delete button entirely — used for discovered relays, which would just be
    // auto-re-added the next time the author whose outbox they cover is re-synced, so a trash
    // icon there implies a permanence deleting doesn't actually have.
    onDelete: (() -> Unit)?,
    onOpenDetails: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onOpenDetails)
            .padding(11.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RelayIcon(
                    iconUrl = relayInfo?.icon,
                    relayConnectionState = relayConnectionState
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = relayDisplayName(relay, relayInfo),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatRelayUrl(relay.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Row(
                modifier = Modifier.offset(x = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // switchChecked only reflects the toggle once the Room write it triggers has
                // round-tripped back through observeDerivedRelayState's recompute — a real gap
                // (DB write + Flow re-emit + O(relays) bucket/connection-state recompute) that
                // otherwise makes every tap visually do nothing for a beat before snapping to the
                // new state. Optimistic local state flips the instant the user taps; the
                // LaunchedEffect resyncs it if the confirmed value ever disagrees (write failure,
                // or an external change to this relay).
                var optimisticChecked by remember(relay.id) { mutableStateOf(switchChecked) }
                LaunchedEffect(switchChecked) { optimisticChecked = switchChecked }
                Switch(
                    checked = optimisticChecked,
                    enabled = switchEnabled,
                    onCheckedChange = { newValue ->
                        optimisticChecked = newValue
                        onToggle(newValue)
                    },
                    modifier = Modifier.scale(0.70f)
                )
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun RelayIconFallback() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun RelayIcon(
    iconUrl: String?,
    relayConnectionState: RelayConnectionIndicatorState? = null
) {
    Box(modifier = Modifier.size(34.dp)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (!iconUrl.isNullOrBlank()) {
                // A relay-declared icon URL can still fail to load (slow/unreachable over Tor,
                // 404, etc) — plain AsyncImage renders nothing in that case, leaving a blank
                // box. SubcomposeAsyncImage lets loading/error states fall back to the same
                // globe placeholder used when there's no icon URL at all.
                SubcomposeAsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                    loading = { RelayIconFallback() },
                    error = { RelayIconFallback() }
                )
            } else {
                RelayIconFallback()
            }
        }

        if (relayConnectionState != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when (relayConnectionState) {
                            RelayConnectionIndicatorState.CONNECTED -> MaterialTheme.colorScheme.tertiary
                            RelayConnectionIndicatorState.CONNECTING -> MaterialTheme.colorScheme.secondary
                            RelayConnectionIndicatorState.FAILED -> MaterialTheme.colorScheme.error
                            RelayConnectionIndicatorState.DISABLED -> MaterialTheme.colorScheme.outline
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape
                    )
            )
        }
    }
}
