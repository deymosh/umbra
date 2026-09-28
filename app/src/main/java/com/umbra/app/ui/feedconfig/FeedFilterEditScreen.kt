package com.umbra.app.ui.feedconfig

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.feed.DefaultFeedFilters
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.InlineAddField
import com.umbra.app.ui.components.SettingsGroup
import com.umbra.app.ui.components.TopBarPrimaryAction
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.privateKeyboardOptions

/**
 * Full-screen filter create/edit form, following ComposerScreen's Scaffold+UmbraTopAppBar pattern
 * (close icon = cancel, "Save" pill in actions). Shares [viewModel] with
 * [FeedConfigScreen] via the FeedConfigGraph nested navigation graph (see NavHost.kt), and reuses
 * its existing showAddDialog/editingFilter state exactly as the old dialog did — this screen just
 * pops the back stack once [FeedConfigViewModel.saveFilter] flips showAddDialog back to false
 * instead of a dialog closing itself.
 */
@Composable
fun FeedFilterEditScreen(
    navController: NavController,
    viewModel: FeedConfigViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    val draft = remember(editKey) { FeedFilterDraft.from(filter) }
    val focusRequester = remember(editKey) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(editKey) {
        if (filter == null) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    FeedFilterEditContent(
        isNew = filter == null,
        draft = draft,
        onCancel = viewModel::closeAddDialog,
        onSave = {
            if (draft.name.isNotBlank()) {
                val base = filter ?: DefaultFeedFilters.create(name = draft.name)
                viewModel.saveFilter(draft.applyTo(base))
            }
        },
        nameFocusRequester = focusRequester
    )
}

/** Editable copy of a [FeedFilter]; nothing is written back until Save. */
@Stable
internal class FeedFilterDraft(
    name: String,
    hideNsfw: Boolean,
    scopeToFollows: Boolean,
    tags: Collection<String>,
    hashtags: Collection<String>,
    prefixes: Collection<String>
) {
    var name by mutableStateOf(name)
    var hideNsfw by mutableStateOf(hideNsfw)
    var scopeToFollows by mutableStateOf(scopeToFollows)
    val tags = mutableStateListOf<String>().apply { addAll(tags) }
    val hashtags = mutableStateListOf<String>().apply { addAll(hashtags) }
    val prefixes = mutableStateListOf<String>().apply { addAll(prefixes) }
    var tagInput by mutableStateOf("")
    var hashtagInput by mutableStateOf("")
    var prefixInput by mutableStateOf("")

    fun applyTo(base: FeedFilter): FeedFilter = base.copy(
        name = name.trim(),
        hideNsfw = hideNsfw,
        scopeToFollows = scopeToFollows,
        excludedTags = tags.toSet(),
        excludedHashtags = hashtags.toSet(),
        excludedContentPrefixes = prefixes.toSet(),
        updatedAtMillis = System.currentTimeMillis()
    )

    companion object {
        fun from(filter: FeedFilter?) = FeedFilterDraft(
            name = filter?.name.orEmpty(),
            hideNsfw = filter?.hideNsfw ?: true,
            scopeToFollows = filter?.scopeToFollows ?: false,
            tags = filter?.excludedTags.orEmpty(),
            hashtags = filter?.excludedHashtags.orEmpty(),
            prefixes = filter?.excludedContentPrefixes.orEmpty()
        )
    }
}

private fun MutableList<String>.addNormalized(raw: String, stripHash: Boolean) {
    val value = raw.trim().let { if (stripHash) it.removePrefix("#") else it }
    if (value.isNotEmpty() && value !in this) add(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedFilterEditContent(
    isNew: Boolean,
    draft: FeedFilterDraft,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    nameFocusRequester: FocusRequester? = null
) {
    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(if (isNew) R.string.create_feed_filter else R.string.edit_filter)) },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(
                        onClick = onCancel,
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel)
                    )
                },
                actions = {
                    TopBarPrimaryAction(
                        label = stringResource(R.string.save),
                        onClick = onSave,
                        enabled = draft.name.isNotBlank()
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            val titleStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface)
            BasicTextField(
                value = draft.name,
                onValueChange = { draft.name = it },
                singleLine = true,
                textStyle = titleStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = privateKeyboardOptions(KeyboardOptions(imeAction = ImeAction.Next)),
                modifier = (nameFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.name.isEmpty()) {
                            Text(
                                text = stringResource(R.string.filter_name),
                                style = titleStyle,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        inner()
                    }
                }
            )
            Text(
                text = stringResource(R.string.filter_edit_ownership_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            SettingsGroup(title = stringResource(R.string.filter_edit_rules_header)) {
                FilterToggleRow(
                    title = stringResource(R.string.hide_nsfw),
                    checked = draft.hideNsfw,
                    onCheckedChange = { draft.hideNsfw = it },
                    showDivider = true
                )
                FilterToggleRow(
                    title = stringResource(R.string.filter_follows_only),
                    checked = draft.scopeToFollows,
                    onCheckedChange = { draft.scopeToFollows = it },
                    showDivider = false
                )
            }

            ExclusionGroup(
                title = stringResource(R.string.excluded_hashtags),
                values = draft.hashtags,
                chipPrefix = "#",
                input = draft.hashtagInput,
                onInputChange = { draft.hashtagInput = it },
                placeholder = stringResource(R.string.add_hashtag_placeholder),
                inputPrefix = "#",
                onAdd = {
                    draft.hashtags.addNormalized(draft.hashtagInput, stripHash = true)
                    draft.hashtagInput = ""
                },
                onRemove = { draft.hashtags.remove(it) }
            )
            ExclusionGroup(
                title = stringResource(R.string.excluded_tags),
                values = draft.tags,
                input = draft.tagInput,
                onInputChange = { draft.tagInput = it },
                placeholder = stringResource(R.string.add_tag_placeholder),
                onAdd = {
                    draft.tags.addNormalized(draft.tagInput, stripHash = true)
                    draft.tagInput = ""
                },
                onRemove = { draft.tags.remove(it) }
            )
            ExclusionGroup(
                title = stringResource(R.string.excluded_content_prefixes),
                values = draft.prefixes,
                input = draft.prefixInput,
                onInputChange = { draft.prefixInput = it },
                placeholder = stringResource(R.string.add_content_prefix_placeholder),
                onAdd = {
                    draft.prefixes.addNormalized(draft.prefixInput, stripHash = false)
                    draft.prefixInput = ""
                },
                onRemove = { draft.prefixes.remove(it) }
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FilterToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(checked = checked, onCheckedChange = null)
    }
    if (showDivider) {
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExclusionGroup(
    title: String,
    values: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    placeholder: String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    chipPrefix: String = "",
    inputPrefix: String? = null
) {
    SettingsGroup(title = title) {
        if (values.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                values.forEach { item ->
                    ChipBadge(text = chipPrefix + item, onClick = { onRemove(item) }, removable = true)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        InlineAddField(
            value = input,
            onValueChange = onInputChange,
            placeholder = placeholder,
            onAdd = onAdd,
            prefix = inputPrefix
        )
    }
}
