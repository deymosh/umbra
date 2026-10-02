package com.umbra.app.ui.mutes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umbra.app.R
import com.umbra.app.domain.nip51.MuteItem
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.InlineAddField
import com.umbra.app.ui.components.ListPrivateNotice
import com.umbra.app.ui.components.PersonRow
import com.umbra.app.ui.components.PrivateByDefaultRow
import com.umbra.app.ui.components.SettingsGroup
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.truncatePublicKey
import com.umbra.app.ui.theme.MonoStyle

@Composable
fun MuteListScreen(
    viewModel: MuteListViewModel,
    onNavigateBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MuteListContent(
        state = state,
        userRepository = viewModel.userRepositoryPublic,
        onNavigateBack = onNavigateBack,
        onOpenProfile = onOpenProfile,
        onOpenThread = onOpenThread,
        onMuteHashtag = viewModel::muteHashtag,
        onMuteWord = viewModel::muteWord,
        onUnmute = viewModel::unmute,
        onNewMutesPrivateChange = viewModel::setNewMutesPrivate,
        onUnlockPrivate = viewModel::unlockPrivate
    )
}

/** The whole mute list, by kind, each entry removable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MuteListContent(
    state: MuteListState,
    userRepository: UserRepository?,
    onNavigateBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onMuteHashtag: (String) -> Unit,
    onMuteWord: (String) -> Unit,
    onUnmute: (MuteItem) -> Unit,
    onNewMutesPrivateChange: (Boolean) -> Unit,
    onUnlockPrivate: () -> Unit
) {
    var hashtagInput by remember { mutableStateOf("") }
    var wordInput by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.mutes_title)) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.mutes_entry_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            if (state.canEdit) {
                item(key = "private-default") {
                    PrivateByDefaultRow(
                        title = stringResource(R.string.mutes_private_default),
                        body = stringResource(R.string.mutes_private_default_body),
                        checked = state.newMutesPrivate,
                        onCheckedChange = onNewMutesPrivateChange
                    )
                }
            }
            when {
                state.privateLocked -> item(key = "private-locked") {
                    ListPrivateNotice(
                        text = stringResource(R.string.mutes_private_locked),
                        action = stringResource(R.string.bookmarks_private_show),
                        onAction = onUnlockPrivate
                    )
                }
                state.privateUnreadable -> item(key = "private-unreadable") {
                    ListPrivateNotice(text = stringResource(R.string.mutes_private_unreadable))
                }
            }
            item(key = "people") {
                val people = state.of(MuteItem.Kind.PERSON)
                SettingsGroup(title = stringResource(R.string.mutes_people)) {
                    if (people.isEmpty()) {
                        Text(
                            stringResource(R.string.mutes_people_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    people.forEachIndexed { index, item ->
                        PersonRow(
                            pubkey = item.value,
                            profile = state.profiles[item.value],
                            onClick = { onOpenProfile(item.value) },
                            userRepository = userRepository,
                            trailing = {
                                if (item.isPrivate) PrivateIcon()
                                if (state.canEdit) TextButton(onClick = { onUnmute(item) }) { Text(stringResource(R.string.unmute_user)) }
                            }
                        )
                        if (index < people.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            item(key = "hashtags") {
                ValueGroup(
                    title = stringResource(R.string.mutes_hashtags),
                    items = state.of(MuteItem.Kind.HASHTAG),
                    chipPrefix = "#",
                    canEdit = state.canEdit,
                    onRemove = onUnmute
                ) {
                    InlineAddField(
                        value = hashtagInput,
                        onValueChange = { hashtagInput = it },
                        placeholder = stringResource(R.string.add_hashtag_placeholder),
                        onAdd = { onMuteHashtag(hashtagInput); hashtagInput = "" },
                        prefix = "#"
                    )
                }
            }
            item(key = "words") {
                ValueGroup(
                    title = stringResource(R.string.mutes_words),
                    items = state.of(MuteItem.Kind.WORD),
                    canEdit = state.canEdit,
                    onRemove = onUnmute
                ) {
                    InlineAddField(
                        value = wordInput,
                        onValueChange = { wordInput = it },
                        placeholder = stringResource(R.string.mutes_add_word_placeholder),
                        onAdd = { onMuteWord(wordInput); wordInput = "" }
                    )
                }
                Text(
                    stringResource(R.string.mutes_words_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            val threads = state.of(MuteItem.Kind.THREAD)
            if (threads.isNotEmpty()) {
                item(key = "threads") {
                    SettingsGroup(title = stringResource(R.string.mutes_threads)) {
                        threads.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { onOpenThread(item.value) }, modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.mutes_thread_label, item.value.truncatePublicKey(8, 8)),
                                        style = MonoStyle,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (item.isPrivate) PrivateIcon()
                                if (state.canEdit) TextButton(onClick = { onUnmute(item) }) { Text(stringResource(R.string.unmute_user)) }
                            }
                            if (index < threads.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Hashtags or words as removable chips, then [addField]. Private ones carry a lock. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValueGroup(
    title: String,
    items: List<MuteItem>,
    canEdit: Boolean,
    onRemove: (MuteItem) -> Unit,
    chipPrefix: String = "",
    addField: @Composable () -> Unit
) {
    SettingsGroup(title = title) {
        if (items.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items.forEach { item ->
                    ChipBadge(
                        text = chipPrefix + item.value,
                        onClick = if (canEdit) ({ onRemove(item) }) else null,
                        removable = canEdit,
                        leadingIcon = if (item.isPrivate) Icons.Outlined.Lock else null
                    )
                }
            }
            if (canEdit) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (canEdit) addField()
    }
}

@Composable
private fun PrivateIcon() {
    Icon(
        Icons.Outlined.Lock,
        contentDescription = stringResource(R.string.bookmarks_private_label),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp).size(18.dp)
    )
}
