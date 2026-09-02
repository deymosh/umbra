package com.umbra.app.ui.devoptions.dbinspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.model.DbEventDetail
import com.umbra.app.domain.model.DbTableSummary
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbInspectorScreen(
    onNavigateBack: () -> Unit,
    viewModel: DbInspectorViewModel
) {
    val state by viewModel.state.collectAsState()

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
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.db_inspector_tables_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            items(state.tableSummaries, key = { it.name }, contentType = { "db_table_summary_row" }) { summary ->
                TableSummaryRow(summary)
            }

            item {
                Text(
                    text = stringResource(R.string.db_inspector_search_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = state.searchKind,
                    onValueChange = viewModel::updateSearchKind,
                    label = { Text(stringResource(R.string.db_inspector_search_kind_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = state.searchPubkey,
                    onValueChange = viewModel::updateSearchPubkey,
                    label = { Text(stringResource(R.string.db_inspector_search_pubkey_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = state.searchContent,
                    onValueChange = viewModel::updateSearchContent,
                    label = { Text(stringResource(R.string.db_inspector_search_content_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedButton(
                    onClick = viewModel::search,
                    enabled = !state.isSearching,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.db_inspector_search_action))
                }
            }

            if (state.hasSearched && state.searchResults.isEmpty() && !state.isSearching) {
                item {
                    Text(
                        text = stringResource(R.string.db_inspector_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(state.searchResults, key = { it.id }, contentType = { "db_search_result_row" }) { event ->
                EventResultRow(event = event, onClick = { viewModel.selectEvent(event.id) })
            }

            if (state.isSearching) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (state.hasMoreResults && !state.isSearching) {
                item {
                    OutlinedButton(
                        onClick = viewModel::loadMore,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.db_inspector_load_more_action))
                    }
                }
            }
        }
    }

    state.selectedEvent?.let { event ->
        EventDetailDialog(event = event, onDismiss = viewModel::clearSelectedEvent)
    }
}

@Composable
private fun TableSummaryRow(summary: DbTableSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = summary.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = pluralStringResource(R.plurals.db_inspector_row_count, summary.rowCount, summary.rowCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EventResultRow(event: DbEventDetail, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = "kind ${event.kind} · ${event.pubkey.take(12)}…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = event.content.ifBlank { event.id },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun EventDetailDialog(event: DbEventDetail, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.db_inspector_event_detail_title)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailField(stringResource(R.string.db_inspector_event_id), event.id)
                    DetailField(stringResource(R.string.db_inspector_event_pubkey), event.pubkey)
                    DetailField(stringResource(R.string.db_inspector_event_kind), event.kind.toString())
                    DetailField(stringResource(R.string.db_inspector_event_created_at), event.createdAt.toString())
                    DetailField(stringResource(R.string.db_inspector_event_content), event.content)
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
private fun DetailField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
