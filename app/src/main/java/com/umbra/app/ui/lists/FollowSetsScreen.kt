package com.umbra.app.ui.lists

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umbra.app.R
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.ConfirmDialog
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.ListNameDialog
import com.umbra.app.ui.components.ListPrivateNotice
import com.umbra.app.ui.components.ListSetChips
import com.umbra.app.ui.components.ListSetMenu
import com.umbra.app.ui.components.PersonRow
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults

/** Opens "Add to list" for a person's pubkey; null when signed out or outside the root. */
val LocalPeopleLists = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun FollowSetsScreen(
    viewModel: FollowSetsViewModel,
    onNavigateBack: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FollowSetsContent(
        state = state,
        canEdit = viewModel.canEdit,
        userRepository = viewModel.userRepositoryPublic,
        onNavigateBack = onNavigateBack,
        onOpenProfile = onOpenProfile,
        onSelectSet = viewModel::selectSet,
        onCreateSet = { viewModel.createSet(it) },
        onRenameSet = viewModel::renameSet,
        onDeleteSet = viewModel::deleteSet,
        onRemoveMember = viewModel::removeMember,
        onUnlockPrivate = viewModel::unlockPrivate
    )
}

/** The user's people lists as chips, and the people in the one shown. */
@Composable
internal fun FollowSetsContent(
    state: FollowSetsState,
    canEdit: Boolean,
    userRepository: UserRepository?,
    onNavigateBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onSelectSet: (String?) -> Unit,
    onCreateSet: (String) -> Unit,
    onRenameSet: (String, String) -> Unit,
    onDeleteSet: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onUnlockPrivate: () -> Unit
) {
    var dialog by remember { mutableStateOf<SetDialog?>(null) }
    val selected = state.selectedSet
    when (val shown = dialog) {
        SetDialog.Create -> ListNameDialog(
            title = stringResource(R.string.list_sets_new),
            confirmLabel = stringResource(R.string.list_sets_create),
            onConfirm = { onCreateSet(it); dialog = null },
            onDismiss = { dialog = null }
        )
        is SetDialog.Rename -> ListNameDialog(
            title = stringResource(R.string.list_sets_rename),
            confirmLabel = stringResource(R.string.list_sets_rename_confirm),
            initial = shown.title,
            onConfirm = { onRenameSet(shown.identifier, it); dialog = null },
            onDismiss = { dialog = null }
        )
        is SetDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.list_sets_delete_title, shown.title),
            message = stringResource(R.string.follow_sets_delete_message),
            confirmLabel = stringResource(R.string.list_sets_delete),
            onConfirm = { onDeleteSet(shown.identifier); dialog = null },
            onDismiss = { dialog = null },
            isDestructive = true
        )
        null -> Unit
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.follow_sets_title)) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) },
                actions = {
                    val title = state.selectedSetTitle
                    if (canEdit && selected != null && title != null) {
                        ListSetMenu(
                            onRename = { dialog = SetDialog.Rename(selected, title) },
                            onDelete = { dialog = SetDialog.Delete(selected, title) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (state.sets.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.follow_sets_none),
                message = stringResource(R.string.follow_sets_none_message),
                actionLabel = if (canEdit) stringResource(R.string.list_sets_new) else null,
                onAction = if (canEdit) ({ dialog = SetDialog.Create }) else null,
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "sets", contentType = "header") {
                ListSetChips(
                    sets = state.sets,
                    selected = selected,
                    allLabel = null,
                    onSelect = onSelectSet,
                    onNewList = { dialog = SetDialog.Create }
                )
            }
            when {
                state.privateLocked -> item(key = "private-locked", contentType = "header") {
                    ListPrivateNotice(
                        text = stringResource(R.string.follow_sets_private_locked),
                        action = stringResource(R.string.bookmarks_private_show),
                        onAction = onUnlockPrivate
                    )
                }
                state.privateUnreadable -> item(key = "private-unreadable", contentType = "header") {
                    ListPrivateNotice(text = stringResource(R.string.follow_sets_private_unreadable))
                }
            }
            if (state.members.isEmpty()) {
                item(key = "empty", contentType = "status") {
                    EmptyState(
                        title = stringResource(R.string.follow_sets_empty),
                        message = stringResource(R.string.follow_sets_empty_message),
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
                    )
                }
            }
            items(state.members, key = { it.pubkey }, contentType = { "person" }) { member ->
                PersonRow(
                    pubkey = member.pubkey,
                    profile = state.profiles[member.pubkey],
                    onClick = { onOpenProfile(member.pubkey) },
                    userRepository = userRepository,
                    trailing = {
                        if (member.isPrivate) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = stringResource(R.string.bookmarks_private_label),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp).size(18.dp)
                            )
                        }
                        if (canEdit) {
                            IconButton(onClick = { onRemoveMember(member.pubkey) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.follow_sets_remove_cd),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

private sealed interface SetDialog {
    data object Create : SetDialog
    data class Rename(val identifier: String, val title: String) : SetDialog
    data class Delete(val identifier: String, val title: String) : SetDialog
}
