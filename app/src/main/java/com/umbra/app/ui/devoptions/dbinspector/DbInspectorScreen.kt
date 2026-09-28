package com.umbra.app.ui.devoptions.dbinspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.model.DbEventDetail
import com.umbra.app.domain.model.DbTableSummary
import com.umbra.app.ui.components.TimeFormatter
import com.umbra.app.ui.components.InlineEmptyText
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.SettingsGroup
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.truncatePublicKey
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme

@Composable
fun DbInspectorScreen(
    onNavigateBack: () -> Unit,
    viewModel: DbInspectorViewModel
) {
    val state by viewModel.state.collectAsState()
    DbInspectorContent(
        state = state,
        onNavigateBack = onNavigateBack,
        onKindChange = viewModel::updateSearchKind,
        onPubkeyChange = viewModel::updateSearchPubkey,
        onContentChange = viewModel::updateSearchContent,
        onSearch = viewModel::search,
        onLoadMore = viewModel::loadMore,
        onSelectEvent = viewModel::selectEvent,
        onDismissEvent = viewModel::clearSelectedEvent
    )
}

@Composable
internal fun DbInspectorContent(
    state: DbInspectorState,
    onNavigateBack: () -> Unit,
    onKindChange: (String) -> Unit,
    onPubkeyChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectEvent: (String) -> Unit,
    onDismissEvent: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        UmbraTopAppBar(
            title = { Text(stringResource(R.string.db_inspector_title)) },
            navigationIcon = {
                UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack)
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "notice", contentType = "notice") { EncryptedNotice() }

            item(key = "tables", contentType = "tables") {
                SettingsGroup(title = stringResource(R.string.db_inspector_tables_header)) {
                    if (state.tableSummaries.isEmpty() && state.isLoadingSummaries) {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            LoadingSpinner(size = 20.dp)
                        }
                    }
                    state.tableSummaries.forEachIndexed { index, summary ->
                        TableSummaryRow(summary, showDivider = index < state.tableSummaries.lastIndex)
                    }
                }
            }

            item(key = "search", contentType = "search") {
                SearchForm(
                    state = state,
                    onKindChange = onKindChange,
                    onPubkeyChange = onPubkeyChange,
                    onContentChange = onContentChange,
                    onSearch = onSearch
                )
            }

            if (state.hasSearched && state.searchResults.isEmpty() && !state.isSearching) {
                item(key = "empty", contentType = "empty") {
                    InlineEmptyText(
                        text = stringResource(R.string.db_inspector_no_results),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            itemsIndexed(state.searchResults, key = { _, it -> it.id }, contentType = { _, _ -> "db_search_result_row" }) { index, event ->
                EventResultRow(
                    event = event,
                    onClick = { onSelectEvent(event.id) },
                    showDivider = index < state.searchResults.lastIndex
                )
            }

            if (state.isSearching) {
                item(key = "searching", contentType = "spinner") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        LoadingSpinner(size = 24.dp)
                    }
                }
            }

            if (state.hasMoreResults && !state.isSearching) {
                item(key = "more", contentType = "more") {
                    OutlinedButton(
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.db_inspector_load_more_action))
                    }
                }
            }
        }
    }

    state.selectedEvent?.let { event ->
        EventDetailDialog(event = event, onDismiss = onDismissEvent)
    }
}

@Composable
private fun EncryptedNotice() {
    val secure = UmbraTheme.colors.secure
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(secure.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = secure, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.db_inspector_encrypted_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TableSummaryRow(summary: DbTableSummary, showDivider: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = summary.name,
            style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = pluralStringResource(R.plurals.db_inspector_row_count, summary.rowCount, summary.rowCount),
            style = MonoStyle,
            color = if (summary.rowCount == 0) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
    if (showDivider) {
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SearchForm(
    state: DbInspectorState,
    onKindChange: (String) -> Unit,
    onPubkeyChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.db_inspector_search_header),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
        )
        val searchActions = KeyboardActions(onSearch = { onSearch() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.searchKind,
                onValueChange = onKindChange,
                label = { Text(stringResource(R.string.db_inspector_event_kind)) },
                singleLine = true,
                textStyle = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = searchActions,
                modifier = Modifier.weight(0.32f)
            )
            OutlinedTextField(
                value = state.searchPubkey,
                onValueChange = onPubkeyChange,
                label = { Text(stringResource(R.string.db_inspector_event_pubkey)) },
                singleLine = true,
                textStyle = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = searchActions,
                modifier = Modifier.weight(0.68f)
            )
        }
        OutlinedTextField(
            value = state.searchContent,
            onValueChange = onContentChange,
            label = { Text(stringResource(R.string.db_inspector_search_content_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = searchActions,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Button(
            onClick = onSearch,
            enabled = !state.isSearching,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.db_inspector_search_action),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun KindBadge(kind: Int) {
    Text(
        text = stringResource(R.string.db_inspector_kind_badge, kind),
        style = MonoStyle,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun EventResultRow(event: DbEventDetail, onClick: () -> Unit, showDivider: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindBadge(event.kind)
            Text(
                text = event.id.truncatePublicKey(8, 6),
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = TimeFormatter.formatCompactRelativeTime(event.createdAt),
                style = MonoStyle,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (event.content.isNotBlank()) {
            Text(
                text = event.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (showDivider) {
        HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EventDetailDialog(event: DbEventDetail, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.db_inspector_event_detail_title))
                KindBadge(event.kind)
            }
        },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DetailField(stringResource(R.string.db_inspector_event_id), event.id)
                    DetailField(stringResource(R.string.db_inspector_event_pubkey), event.pubkey)
                    DetailField(stringResource(R.string.db_inspector_event_created_at), event.createdAt.toString())
                    DetailField(stringResource(R.string.db_inspector_event_content), event.content, mono = false)
                    DetailField(stringResource(R.string.db_inspector_event_tags), event.tagsJson)
                    DetailField(stringResource(R.string.db_inspector_event_sig), event.sig)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.db_inspector_close_action))
            }
        }
    )
}

@Composable
private fun DetailField(label: String, value: String, mono: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(10.dp)
        )
    }
}
