package com.umbra.app.ui.feedconfig

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.feed.DefaultFeedFilters
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.privateKeyboardOptions

/**
 * Full-screen filter create/edit form, following ComposerScreen's Scaffold+UmbraTopAppBar pattern
 * (close icon = cancel, "Save" TextButton in actions) — replaces the former AlertDialog, which had
 * too many fields for a dialog's fixed width to lay out comfortably. Shares [viewModel] with
 * [FeedConfigScreen] via the FeedConfigGraph nested navigation graph (see NavHost.kt), and reuses
 * its existing showAddDialog/editingFilter state exactly as the old dialog did — this screen just
 * pops the back stack once [FeedConfigViewModel.saveFilter] flips showAddDialog back to false
 * instead of a dialog closing itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedFilterEditScreen(
    navController: NavController,
    viewModel: FeedConfigViewModel
) {
    val state by viewModel.state.collectAsState()
    val filter = state.editingFilter

    LaunchedEffect(state.showAddDialog) {
        if (!state.showAddDialog) {
            navController.popBackStack()
        }
    }

    // System back/gesture bypasses the close icon's onClick below, which is the only other path
    // that calls closeAddDialog() — without this, editingFilter/showAddDialog are left set after
    // a system-back dismissal, and the next "create new filter" tap reopens this screen in stale
    // edit mode (openAddDialog() alone can't fully guard against that, since it only runs on the
    // next open, after the leak already happened).
    BackHandler {
        viewModel.closeAddDialog()
    }

    val editKey = filter?.id ?: "new-filter"
    val focusRequester = remember(editKey) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var name by remember(editKey) { mutableStateOf(filter?.name ?: "") }
    var hideNsfw by remember(editKey) { mutableStateOf(filter?.hideNsfw ?: true) }
    var scopeToFollows by remember(editKey) { mutableStateOf(filter?.scopeToFollows ?: false) }

    val excludedTags = remember(editKey) {
        mutableStateListOf<String>().apply { addAll(filter?.excludedTags ?: emptySet()) }
    }
    var newExcludedTag by remember(editKey) { mutableStateOf("") }

    val excludedHashtags = remember(editKey) {
        mutableStateListOf<String>().apply { addAll(filter?.excludedHashtags ?: emptySet()) }
    }
    var newExcludedHashtag by remember(editKey) { mutableStateOf("") }

    val excludedContentPrefixes = remember(editKey) {
        mutableStateListOf<String>().apply { addAll(filter?.excludedContentPrefixes ?: emptySet()) }
    }
    var newExcludedContentPrefix by remember(editKey) { mutableStateOf("") }

    LaunchedEffect(editKey) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun save() {
        if (name.isNotBlank()) {
            val base = filter ?: DefaultFeedFilters.create(name = name)
            viewModel.saveFilter(
                base.copy(
                    name = name.trim(),
                    hideNsfw = hideNsfw,
                    scopeToFollows = scopeToFollows,
                    excludedTags = excludedTags.toSet(),
                    excludedHashtags = excludedHashtags.toSet(),
                    excludedContentPrefixes = excludedContentPrefixes.toSet(),
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = {
                    Text(
                        if (filter == null) stringResource(R.string.create_feed_filter)
                        else stringResource(R.string.edit_filter)
                    )
                },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(
                        onClick = { viewModel.closeAddDialog() },
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel)
                    )
                },
                actions = {
                    TextButton(onClick = ::save, enabled = name.isNotBlank()) {
                        Text(stringResource(R.string.save))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.filter_name)) },
                keyboardOptions = privateKeyboardOptions(),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .fillMaxWidth(),
                singleLine = true
            )

            ToggleRow(
                label = stringResource(R.string.hide_nsfw),
                checked = hideNsfw,
                onCheckedChange = { hideNsfw = it }
            )

            ToggleRow(
                label = stringResource(R.string.filter_follows_only),
                checked = scopeToFollows,
                onCheckedChange = { scopeToFollows = it }
            )

            HorizontalDivider()

            TagEditorSection(
                title = stringResource(R.string.excluded_tags),
                value = newExcludedTag,
                placeholder = stringResource(R.string.add_tag_placeholder),
                onValueChange = { newExcludedTag = it },
                onAdd = {
                    val value = newExcludedTag.trim()
                    if (value.isNotEmpty() && !excludedTags.contains(value)) {
                        excludedTags.add(value)
                    }
                    newExcludedTag = ""
                },
                values = excludedTags,
                onRemove = { excludedTags.remove(it) }
            )

            HorizontalDivider()

            TagEditorSection(
                title = stringResource(R.string.excluded_hashtags),
                value = newExcludedHashtag,
                placeholder = stringResource(R.string.add_hashtag_placeholder),
                onValueChange = { newExcludedHashtag = it },
                onAdd = {
                    val value = newExcludedHashtag.trim()
                    if (value.isNotEmpty() && !excludedHashtags.contains(value)) {
                        excludedHashtags.add(value)
                    }
                    newExcludedHashtag = ""
                },
                values = excludedHashtags,
                onRemove = { excludedHashtags.remove(it) }
            )

            HorizontalDivider()

            TagEditorSection(
                title = stringResource(R.string.excluded_content_prefixes),
                value = newExcludedContentPrefix,
                placeholder = stringResource(R.string.add_content_prefix_placeholder),
                onValueChange = { newExcludedContentPrefix = it },
                onAdd = {
                    val value = newExcludedContentPrefix.trim()
                    if (value.isNotEmpty() && !excludedContentPrefixes.contains(value)) {
                        excludedContentPrefixes.add(value)
                    }
                    newExcludedContentPrefix = ""
                },
                values = excludedContentPrefixes,
                onRemove = { excludedContentPrefixes.remove(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditorSection(
    title: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    values: List<String>,
    onRemove: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(placeholder) },
                keyboardOptions = privateKeyboardOptions(),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_tag))
            }
        }

        if (values.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    values.forEach { item ->
                        ChipBadge(text = item, onClick = { onRemove(item) }, removable = true)
                    }
                }
            }
        }
    }
}
