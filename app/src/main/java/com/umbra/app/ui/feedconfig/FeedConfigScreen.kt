package com.umbra.app.ui.feedconfig

import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umbra.app.ui.components.ConfirmDialog
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import com.umbra.app.ui.components.MenuItemRow
import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.ui.Screen
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.ErrorBanner
import com.umbra.app.ui.components.SectionHeader
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedConfigScreen(
    navController: NavController,
    viewModel: FeedConfigViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val availableFilters by remember(state.filters, state.activeFilters) {
        derivedStateOf {
            val activeIds = state.activeFilters.map { it.id }.toSet()
            state.filters.filter { it.id !in activeIds }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        UmbraTopAppBar(
            title = { Text(stringResource(R.string.feed_settings)) },
            navigationIcon = {
                UmbraTopAppBarDefaults.BackNavigationIcon(onClick = { navController.popBackStack() })
            },
            actions = {
                IconButton(
                    onClick = {
                        viewModel.openAddDialog()
                        navController.navigate(Screen.FeedFilterEdit.route)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_filter)
                    )
                }
                IconButton(onClick = { viewModel.resetToDefaults() }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.reset_defaults)
                    )
                }
            }
        )

        if (state.errorMessage != null) {
            ErrorBanner(
                message = state.errorMessage!!.resolve(context),
                onDismiss = { viewModel.clearError() }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The NIP-51 mute list applies under every filter, so it sits above them.
            item(key = "mute-list", contentType = "mute_list_entry") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    MenuItemRow(
                        icon = Icons.AutoMirrored.Outlined.VolumeOff,
                        title = stringResource(R.string.mutes_title),
                        subtitle = stringResource(R.string.mutes_entry_body),
                        onClick = { navController.navigate(Screen.MuteList.route) },
                        showDivider = false
                    )
                }
            }
            if (state.activeFilters.isNotEmpty()) {
                stickySectionHeader(R.string.active_filters)
                items(
                    state.activeFilters,
                    key = { it.id },
                    contentType = { "active_filter_card" }
                ) { filter ->
                    ActiveFilterCard(
                        filter = filter,
                        onEdit = {
                            viewModel.startEditingFilter(filter)
                            navController.navigate(Screen.FeedFilterEdit.route)
                        },
                        onDeactivate = { viewModel.setFilterActive(filter.id, false) }
                    )
                }
            }

            if (availableFilters.isNotEmpty()) {
                stickySectionHeader(R.string.available_filters)
                items(
                    availableFilters,
                    key = { it.id },
                    contentType = { "feed_filter_card" }
                ) { filter ->
                    FeedFilterCard(
                        filter = filter,
                        isSelected = state.selectedFilter?.id == filter.id,
                        onSelect = { viewModel.selectFilter(filter) },
                        onActivate = { viewModel.setFilterActive(filter.id, true) },
                        onEdit = {
                            viewModel.startEditingFilter(filter)
                            navController.navigate(Screen.FeedFilterEdit.route)
                        },
                        onDelete = { viewModel.deleteFilter(filter.id) }
                    )
                }
            }

            if (state.filters.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.no_filters_configured),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.stickySectionHeader(titleRes: Int) {
    stickyHeader {
        SectionHeader(
            title = stringResource(titleRes),
            modifier = Modifier.background(MaterialTheme.colorScheme.background)
        )
    }
}

@Composable
internal fun ActiveFilterCard(
    filter: FeedFilter,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, UmbraTheme.colors.corona.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = filter.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ChipBadge(
                    text = stringResource(R.string.active_feed),
                    backgroundColor = UmbraTheme.colors.corona.copy(alpha = 0.16f),
                    textColor = UmbraTheme.colors.corona
                )
            }

            FilterSummaryChips(filter = filter)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(stringResource(R.string.edit))
                }
                OutlinedButton(
                    onClick = onDeactivate,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.deactivate))
                }
            }
        }
    }
}

@Composable
internal fun FeedFilterCard(
    filter: FeedFilter,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember(filter.id, isSelected) { mutableStateOf(isSelected) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.feed_filter_delete_confirm_title),
            message = stringResource(R.string.feed_filter_delete_confirm_message),
            confirmLabel = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false }
        )
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
                onSelect()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = filter.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            FilterSummaryChips(filter = filter)

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onActivate,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.activate))
                        }
                        FilledTonalButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp).size(18.dp)
                            )
                            Text(stringResource(R.string.edit))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSummaryChips(filter: FeedFilter) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (filter.hideNsfw) {
            ChipBadge(
                text = stringResource(R.string.hide_nsfw),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                textColor = MaterialTheme.colorScheme.onSurface
            )
        }
        if (filter.scopeToFollows) {
            ChipBadge(
                text = stringResource(R.string.filter_follows_only_chip),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                textColor = MaterialTheme.colorScheme.onSurface
            )
        }
        if (filter.followSets.isNotEmpty()) {
            ChipBadge(
                text = pluralStringResource(R.plurals.filter_lists_chip, filter.followSets.size, filter.followSets.size),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                textColor = MaterialTheme.colorScheme.onSurface
            )
        }
        if (filter.mutedPubkeys.isNotEmpty()) {
            ChipBadge(text = stringResource(R.string.muted_authors_count, filter.mutedPubkeys.size))
        }
        if (filter.excludedTags.isNotEmpty()) {
            ChipBadge(text = stringResource(R.string.excluded_tags_count, filter.excludedTags.size))
        }
        if (filter.excludedHashtags.isNotEmpty()) {
            ChipBadge(text = stringResource(R.string.excluded_hashtags_count, filter.excludedHashtags.size))
        }
        if (filter.excludedContentPrefixes.isNotEmpty()) {
            ChipBadge(text = stringResource(R.string.excluded_content_prefixes_count, filter.excludedContentPrefixes.size))
        }
    }
}


