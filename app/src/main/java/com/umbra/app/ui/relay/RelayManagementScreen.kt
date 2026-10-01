package com.umbra.app.ui.relay

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.nip86.RelayManagementEntry
import com.umbra.app.ui.components.ConfirmDialog
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.ErrorBanner
import com.umbra.app.ui.components.InlineEmptyText
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme
import com.umbra.app.ui.common.resolve

/**
 * NIP-86 relay management: probes supportedmethods once, then shows only the sections the relay
 * answers to. Every mutation posts through [RelayManagementRepository] with a fresh NIP-98 event;
 * each list re-fetches after its own write.
 */
@Composable
fun RelayManagementScreen(
    navController: NavController,
    relayUrl: String,
    viewModel: RelayManagementViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(relayUrl) { viewModel.start(relayUrl) }

    RelayManagementContent(
        state = state,
        onNavigateBack = { navController.popBackStack() },
        onAddPubkey = viewModel::addPubkey,
        onRemovePubkey = viewModel::removePubkey,
        onAllowEvent = viewModel::allowEvent,
        onBanEvent = viewModel::banEvent,
        onUnbanEvent = viewModel::unbanEvent,
        onUnallowEvent = viewModel::unallowEvent,
        onAddKind = viewModel::addKind,
        onRemoveKind = viewModel::removeKind,
        onBlockIp = viewModel::blockIp,
        onUnblockIp = viewModel::unblockIp,
        onChangeName = viewModel::changeRelayName,
        onChangeDescription = viewModel::changeRelayDescription,
        onChangeIcon = viewModel::changeRelayIcon,
        onClearError = viewModel::clearError,
        onRetry = { viewModel.start(relayUrl) }
    )
}

/** Stateless rendering, so snapshots can drive it with sample state and no ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelayManagementContent(
    state: RelayManagementState,
    onNavigateBack: () -> Unit,
    onAddPubkey: (String, String?, Boolean) -> Unit,
    onRemovePubkey: (RelayManagementEntry, Boolean) -> Unit,
    onAllowEvent: (String) -> Unit,
    onBanEvent: (String) -> Unit,
    onUnbanEvent: (String) -> Unit,
    onUnallowEvent: (String) -> Unit,
    onAddKind: (Int, Boolean) -> Unit,
    onRemoveKind: (Int, Boolean) -> Unit,
    onBlockIp: (String, String?) -> Unit,
    onUnblockIp: (String) -> Unit,
    onChangeName: (String) -> Unit,
    onChangeDescription: (String) -> Unit,
    onChangeIcon: (String) -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.relay_management_title)) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            state.errorMessage?.let { message ->
                val context = LocalContext.current
                ErrorBanner(
                    message = message.resolve(context),
                    onDismiss = onClearError
                )
            }

            when {
                // The probe hasn't answered yet.
                state.supportedTabs == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingSpinner(size = 36.dp)
                    }

                state.notAuthorized ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = stringResource(R.string.relay_management_not_authorized)
                        )
                    }

                state.supportedTabs.isNotEmpty() -> RelayManagementTabs(
                    state = state,
                    onAddPubkey = onAddPubkey,
                    onRemovePubkey = onRemovePubkey,
                    onAllowEvent = onAllowEvent,
                    onBanEvent = onBanEvent,
                    onUnbanEvent = onUnbanEvent,
                    onUnallowEvent = onUnallowEvent,
                    onAddKind = onAddKind,
                    onRemoveKind = onRemoveKind,
                    onBlockIp = onBlockIp,
                    onUnblockIp = onUnblockIp,
                    onChangeName = onChangeName,
                    onChangeDescription = onChangeDescription,
                    onChangeIcon = onChangeIcon
                )

                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.relay_management_not_authorized)
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayManagementTabs(
    state: RelayManagementState,
    onAddPubkey: (String, String?, Boolean) -> Unit,
    onRemovePubkey: (RelayManagementEntry, Boolean) -> Unit,
    onAllowEvent: (String) -> Unit,
    onBanEvent: (String) -> Unit,
    onUnbanEvent: (String) -> Unit,
    onUnallowEvent: (String) -> Unit,
    onAddKind: (Int, Boolean) -> Unit,
    onRemoveKind: (Int, Boolean) -> Unit,
    onBlockIp: (String, String?) -> Unit,
    onUnblockIp: (String) -> Unit,
    onChangeName: (String) -> Unit,
    onChangeDescription: (String) -> Unit,
    onChangeIcon: (String) -> Unit
) {
    // Only reached when supportedTabs is non-empty; re-assert for the compiler.
    val tabs = state.supportedTabs ?: return
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val boundedIndex = selectedTab.coerceIn(0, tabs.lastIndex)
    val tab = tabs[boundedIndex]

    val sections = tabSections(tab = tab)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "tab-bar", contentType = "tabbar") {
            SimpleTabBar(
                tabs = tabs,
                selectedIndex = boundedIndex,
                onSelect = { selectedTab = it }
            )
        }
        items(
            items = sections,
            key = { it.key },
            contentType = { it.contentType }
        ) { section ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (section) {
                    is ManagementSection.PeopleList -> PeopleListSection(
                        section = section,
                        lists = state.lists,
                        onAddPubkey = onAddPubkey,
                        onRemovePubkey = onRemovePubkey
                    )
                    is ManagementSection.EventNeedingModeration, is ManagementSection.EventBanned -> EventListSection(
                        section = section,
                        lists = state.lists,
                        onAllowEvent = onAllowEvent,
                        onBanEvent = onBanEvent,
                        onUnbanEvent = onUnbanEvent,
                        onUnallowEvent = onUnallowEvent
                    )
                    is ManagementSection.KindList -> KindListSection(
                        section = section,
                        lists = state.lists,
                        onAddKind = onAddKind,
                        onRemoveKind = onRemoveKind
                    )
                    ManagementSection.IpBlocked -> IpListSection(
                        lists = state.lists,
                        onBlockIp = onBlockIp,
                        onUnblockIp = onUnblockIp
                    )
                    is ManagementSection.RelayFields -> RelayFieldsSection(
                        state = state,
                        onChangeName = onChangeName,
                        onChangeDescription = onChangeDescription,
                        onChangeIcon = onChangeIcon
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleTabBar(
    tabs: List<RelayManagementTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    // Quiet segmented row on a hairline — no corona indicator here; management tabs are
    // sub-navigation, not the identity-level pager ProfileScreen owns.
    Column {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(tabs.size) { index ->
                val selected = index == selectedIndex
                val tab = tabs[index]
                Text(
                    text = stringResource(tab.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One LazyColumn row-shaped section per screen segment, keyed for stable reordering. */
private sealed interface ManagementSection {
    val key: String
    val contentType: String

    data class PeopleList(val banned: Boolean) : ManagementSection {
        override val key = if (banned) "people-banned" else "people-allowed"
        override val contentType = "people"
    }

    data object EventNeedingModeration : ManagementSection {
        override val key = "events-moderation"
        override val contentType = "events"
    }

    data object EventBanned : ManagementSection {
        override val key = "events-banned"
        override val contentType = "events"
    }

    data class KindList(val allowed: Boolean) : ManagementSection {
        override val key = if (allowed) "kinds-allowed" else "kinds-disallowed"
        override val contentType = "kinds"
    }

    data object IpBlocked : ManagementSection {
        override val key = "ips-blocked"
        override val contentType = "ips"
    }

    data object RelayFields : ManagementSection {
        override val key = "relay-fields"
        override val contentType = "relay"
    }
}

@Composable
private fun tabSections(
    tab: RelayManagementTab
): List<ManagementSection> = when (tab) {
    RelayManagementTab.PEOPLE -> listOf(
        ManagementSection.PeopleList(banned = true),
        ManagementSection.PeopleList(banned = false)
    )
    RelayManagementTab.EVENTS -> listOf(
        ManagementSection.EventNeedingModeration,
        ManagementSection.EventBanned
    )
    RelayManagementTab.KINDS -> listOf(
        ManagementSection.KindList(allowed = true),
        ManagementSection.KindList(allowed = false)
    )
    RelayManagementTab.IPS -> listOf(ManagementSection.IpBlocked)
    RelayManagementTab.RELAY -> listOf(ManagementSection.RelayFields)
}

@Composable
private fun PeopleListSection(
    section: ManagementSection.PeopleList,
    lists: RelayManagementLists,
    onAddPubkey: (String, String?, Boolean) -> Unit,
    onRemovePubkey: (RelayManagementEntry, Boolean) -> Unit
) {
    val title = stringResource(if (section.banned) R.string.relay_management_banned else R.string.relay_management_allowed)
    val entries = if (section.banned) lists.bannedPubkeys else lists.allowedPubkeys
    ManagementGroup(title = title) {
        IdentifierInput(
            placeholderRes = R.string.relay_management_add_placeholder,
            onAdd = { value, reason -> onAddPubkey(value, reason, !section.banned) }
        )
        if (entries.isEmpty()) {
            InlineEmptyText(stringResource(R.string.relay_management_empty_list), Modifier.padding(horizontal = 16.dp))
        } else {
            entries.forEachIndexed { index, entry ->
                EntryRow(
                    entry = entry,
                    onRemove = { onRemovePubkey(entry, !section.banned) },
                    showDivider = index < entries.lastIndex
                )
            }
        }
    }
}

@Composable
private fun EventListSection(
    section: ManagementSection,
    lists: RelayManagementLists,
    onAllowEvent: (String) -> Unit,
    onBanEvent: (String) -> Unit,
    onUnbanEvent: (String) -> Unit,
    onUnallowEvent: (String) -> Unit
) {
    when (section) {
        ManagementSection.EventNeedingModeration -> ManagementGroup(title = stringResource(R.string.relay_management_needing_moderation)) {
            val entries = lists.needingModeration
            if (entries.isEmpty()) {
                InlineEmptyText(stringResource(R.string.relay_management_empty_list), Modifier.padding(horizontal = 16.dp))
            } else {
                entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.identifier,
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.weight(1f)
                        )
                        ManagementTextButton(stringResource(R.string.relay_management_allow)) { onAllowEvent(entry.identifier) }
                        ManagementTextButton(stringResource(R.string.relay_management_ban), destructive = true) { onBanEvent(entry.identifier) }
                    }
                    if (index < entries.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        else -> ManagementGroup(title = stringResource(R.string.relay_management_banned)) {
            val entries = lists.bannedEvents
            if (entries.isEmpty()) {
                InlineEmptyText(stringResource(R.string.relay_management_empty_list), Modifier.padding(horizontal = 16.dp))
            } else {
                entries.forEachIndexed { index, entry ->
                    EntryRow(
                        entry = entry,
                        onRemove = { onUnbanEvent(entry.identifier) },
                        showDivider = index < entries.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun KindListSection(
    section: ManagementSection.KindList,
    lists: RelayManagementLists,
    onAddKind: (Int, Boolean) -> Unit,
    onRemoveKind: (Int, Boolean) -> Unit
) {
    val title = stringResource(if (section.allowed) R.string.relay_management_allowed else R.string.relay_management_banned)
    val kinds = if (section.allowed) lists.allowedKinds else lists.disallowedKinds
    ManagementGroup(title = title) {
        NumericInput(placeholderRes = R.string.relay_management_kind_placeholder) { kind ->
            onAddKind(kind, section.allowed)
        }
        if (kinds.isEmpty()) {
            InlineEmptyText(stringResource(R.string.relay_management_empty_list), Modifier.padding(horizontal = 16.dp))
        } else {
            kinds.forEachIndexed { index, kind ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = kind.toString(), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    RemoveIconButton { onRemoveKind(kind, section.allowed) }
                }
                if (index < kinds.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun IpListSection(
    lists: RelayManagementLists,
    onBlockIp: (String, String?) -> Unit,
    onUnblockIp: (String) -> Unit
) {
    ManagementGroup(title = stringResource(R.string.relay_management_tab_ips)) {
        IdentifierInput(
            placeholderRes = R.string.relay_management_ip_placeholder,
            onAdd = { ip, reason -> onBlockIp(ip, reason) }
        )
        val entries = lists.blockedIps
        if (entries.isEmpty()) {
            InlineEmptyText(stringResource(R.string.relay_management_empty_list), Modifier.padding(horizontal = 16.dp))
        } else {
            entries.forEachIndexed { index, entry ->
                EntryRow(
                    entry = entry,
                    onRemove = { onUnblockIp(entry.identifier) },
                    showDivider = index < entries.lastIndex
                )
            }
        }
    }
}

@Composable
private fun RelayFieldsSection(
    state: RelayManagementState,
    onChangeName: (String) -> Unit,
    onChangeDescription: (String) -> Unit,
    onChangeIcon: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(state.relayName.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(state.relayDescription.orEmpty()) }
    var icon by rememberSaveable { mutableStateOf(state.relayIcon.orEmpty()) }

    ManagementGroup(title = stringResource(R.string.relay_management_tab_relay)) {
        TextFieldRow(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.relay_management_name_label),
            onSave = { onChangeName(name) }
        )
        TextFieldRow(
            value = description,
            onValueChange = { description = it },
            placeholder = stringResource(R.string.relay_management_description_label),
            onSave = { onChangeDescription(description) }
        )
        TextFieldRow(
            value = icon,
            onValueChange = { icon = it },
            placeholder = stringResource(R.string.relay_management_icon_label),
            onSave = { onChangeIcon(icon) }
        )
    }
}

@Composable
private fun ManagementGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
        )
        content()
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun IdentifierInput(
    placeholderRes: Int,
    onAdd: (value: String, reason: String?) -> Unit
) {
    var value by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(stringResource(placeholderRes), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MonoStyle,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                placeholder = { Text(stringResource(R.string.relay_management_reason_hint), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val v = value.trim()
                    if (v.isNotBlank()) {
                        onAdd(v, reason.trim().takeIf { it.isNotBlank() })
                        value = ""
                        reason = ""
                    }
                },
                enabled = value.isNotBlank()
            ) {
                Text(stringResource(R.string.relay_management_add))
            }
        }
    }
}

@Composable
private fun NumericInput(placeholderRes: Int, onAdd: (Int) -> Unit) {
    var value by remember { mutableStateOf("") }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.filter { c -> c.isDigit() } },
            placeholder = { Text(stringResource(placeholderRes), style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MonoStyle,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = {
                value.toIntOrNull()?.let(onAdd)
                value = ""
            },
            enabled = value.isNotBlank()
        ) {
            Text(stringResource(R.string.relay_management_add))
        }
    }
}

@Composable
private fun EntryRow(
    entry: RelayManagementEntry,
    onRemove: () -> Unit,
    showDivider: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.identifier,
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            entry.reason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        RemoveIconButton(onClick = onRemove)
    }
    if (showDivider) {
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Quiet remove control — reversible, so a muted icon rather than a red shout. */
@Composable
private fun RemoveIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = Icons.Outlined.RemoveCircleOutline,
            contentDescription = stringResource(R.string.relay_management_remove),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ManagementTextButton(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun TextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSave: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        trailingIcon = {
            androidx.compose.material3.TextButton(onClick = onSave) {
                Text(stringResource(R.string.relay_management_save))
            }
        }
    )
}
