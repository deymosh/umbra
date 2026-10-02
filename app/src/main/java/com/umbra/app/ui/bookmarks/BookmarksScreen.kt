package com.umbra.app.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.TextOverflow
import com.umbra.app.ui.components.ConfirmDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.datasource.DataSource
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.feed.EventCard

/** Bookmark/unbookmark from any note's menu; null when signed out or outside the root. */
class BookmarkActions(
    val isBookmarked: (Event) -> Boolean,
    val toggle: (Event) -> Unit,
    val addToList: (Event) -> Unit
)

val LocalBookmarks = staticCompositionLocalOf<BookmarkActions?> { null }

@Composable
fun BookmarksScreen(
    viewModel: BookmarksViewModel,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BookmarksContent(
        state = state,
        userRepository = viewModel.userRepositoryPublic,
        dataSourceFactory = viewModel.mediaDataSourceFactory,
        onNavigateBack = onNavigateBack,
        onOpenThread = onOpenThread,
        onOpenProfile = onOpenProfile,
        onNewBookmarksPrivateChange = viewModel::setNewBookmarksPrivate,
        onUnlockPrivate = viewModel::unlockPrivate,
        onSelectSet = viewModel::selectSet,
        onCreateSet = { viewModel.createSet(it) },
        onRenameSet = viewModel::renameSet,
        onDeleteSet = viewModel::deleteSet
    )
}

/**
 * The saved notes and articles, newest first, private ones marked. Above them: whether new
 * bookmarks are private, and — when the signer hasn't been asked yet — a way to show the
 * private ones, since reading them is the user's call, not something the screen does on open.
 */
@Composable
internal fun BookmarksContent(
    state: BookmarksState,
    userRepository: UserRepository,
    dataSourceFactory: DataSource.Factory,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onNewBookmarksPrivateChange: (Boolean) -> Unit,
    onUnlockPrivate: () -> Unit,
    onSelectSet: (String?) -> Unit,
    onCreateSet: (String) -> Unit,
    onRenameSet: (String, String) -> Unit,
    onDeleteSet: (String) -> Unit,
    animateAvatars: Boolean = true
) {
    var dialog by remember { mutableStateOf<SetDialog?>(null) }
    val selected = state.selectedSet
    when (val shown = dialog) {
        SetDialog.Create -> ListNameDialog(
            title = stringResource(R.string.bookmark_sets_new),
            confirmLabel = stringResource(R.string.bookmark_sets_create),
            onConfirm = { onCreateSet(it); dialog = null },
            onDismiss = { dialog = null }
        )
        is SetDialog.Rename -> ListNameDialog(
            title = stringResource(R.string.bookmark_sets_rename),
            confirmLabel = stringResource(R.string.bookmark_sets_rename_confirm),
            initial = shown.title,
            onConfirm = { onRenameSet(shown.identifier, it); dialog = null },
            onDismiss = { dialog = null }
        )
        is SetDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.bookmark_sets_delete_title, shown.title),
            message = stringResource(R.string.bookmark_sets_delete_message),
            confirmLabel = stringResource(R.string.bookmark_sets_delete),
            onConfirm = { onDeleteSet(shown.identifier); dialog = null },
            onDismiss = { dialog = null },
            isDestructive = true
        )
        null -> Unit
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.bookmarks_title)) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) },
                actions = {
                    val title = state.selectedSetTitle
                    if (selected != null && title != null) {
                        SetMenu(
                            onRename = { dialog = SetDialog.Rename(selected, title) },
                            onDelete = { dialog = SetDialog.Delete(selected, title) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "sets", contentType = "header") {
                SetChips(
                    sets = state.sets,
                    selected = selected,
                    onSelect = onSelectSet,
                    onNewList = { dialog = SetDialog.Create }
                )
            }
            item(key = "private-default", contentType = "header") {
                NewBookmarksPrivateRow(state.newBookmarksPrivate, onNewBookmarksPrivateChange)
            }
            when (state.privateState) {
                PrivateBookmarksState.LOCKED -> item(key = "private-locked", contentType = "header") {
                    PrivateNotice(
                        text = stringResource(R.string.bookmarks_private_locked),
                        action = stringResource(R.string.bookmarks_private_show),
                        onAction = onUnlockPrivate
                    )
                }
                PrivateBookmarksState.UNREADABLE -> item(key = "private-unreadable", contentType = "header") {
                    PrivateNotice(text = stringResource(R.string.bookmarks_private_unreadable))
                }
                PrivateBookmarksState.READ -> Unit
            }
            when {
                state.items.isEmpty() && state.isLoading -> item(key = "loading", contentType = "status") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                state.items.isEmpty() -> item(key = "empty", contentType = "status") {
                    EmptyState(
                        title = stringResource(if (selected == null) R.string.bookmarks_empty else R.string.bookmark_sets_empty),
                        message = stringResource(
                            if (selected == null) R.string.bookmarks_empty_message else R.string.bookmark_sets_empty_message
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
                    )
                }
            }
            items(state.items, key = { it.event.id }, contentType = { "note" }) { item ->
                Column {
                    if (item.isPrivate) PrivateLabel()
                    EventCard(
                        event = item.event,
                        userProfile = state.profiles[item.event.pubkey.lowercase()],
                        userRepository = userRepository,
                        torDataSourceFactory = dataSourceFactory,
                        animateAvatars = animateAvatars,
                        onEventClick = { onOpenThread(it.id) },
                        onProfileClick = onOpenProfile,
                        onEventReferenceClick = onOpenThread
                    )
                }
            }
        }
    }
}

private sealed interface SetDialog {
    data object Create : SetDialog
    data class Rename(val identifier: String, val title: String) : SetDialog
    data class Delete(val identifier: String, val title: String) : SetDialog
}

/** "All bookmarks", then each set, then a chip to start a new one. */
@Composable
private fun SetChips(
    sets: List<BookmarkSetChip>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onNewList: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        item(key = "all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.bookmark_sets_all)) }
            )
        }
        items(sets, key = { it.identifier }) { set ->
            FilterChip(
                selected = selected == set.identifier,
                onClick = { onSelect(set.identifier) },
                label = { Text(set.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) }
            )
        }
        item(key = "new") {
            AssistChip(
                onClick = onNewList,
                label = { Text(stringResource(R.string.bookmark_sets_new)) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
    }
}

@Composable
private fun SetMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.bookmark_sets_menu_cd))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bookmark_sets_rename)) },
                onClick = { open = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bookmark_sets_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() }
            )
        }
    }
}

@Composable
private fun NewBookmarksPrivateRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.bookmarks_private_default), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.bookmarks_private_default_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PrivateNotice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun PrivateLabel() {
    Row(
        modifier = Modifier.padding(start = 70.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Text(
            stringResource(R.string.bookmarks_private_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
