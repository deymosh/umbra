package com.umbra.app.ui.emoji

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.umbra.app.R
import com.umbra.app.domain.nip30.EmojiPack
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.SectionHeader
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.truncatePublicKey

@Composable
fun EmojiPacksScreen(
    viewModel: EmojiPacksViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EmojiPacksContent(
        state = state,
        onNavigateBack = onNavigateBack,
        onQueryChange = viewModel::setQuery,
        onAdd = viewModel::addPack,
        onRemove = viewModel::removePack,
        onMove = viewModel::movePack
    )
}

/**
 * Two lists on one page: the packs the user picked (each marked public or this-device only), then
 * recent packs from relays to pick from. Adding asks where the pack should live, since publishing
 * the list tells relays which packs the user uses; a read-only session keeps packs on the device.
 */
@Composable
internal fun EmojiPacksContent(
    state: EmojiPacksState,
    onNavigateBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onAdd: (EmojiPack, PackStorage) -> Unit,
    onRemove: (OwnedEmojiPack) -> Unit,
    onMove: (OwnedEmojiPack) -> Unit
) {
    var packToAdd by remember { mutableStateOf<EmojiPack?>(null) }
    packToAdd?.let { pack ->
        AddPackDialog(
            pack = pack,
            onChoose = { storage ->
                onAdd(pack, storage)
                packToAdd = null
            },
            onDismiss = { packToAdd = null }
        )
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.emoji_packs_title)) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) }
            )
        }
    ) { padding ->
        val picked = state.pickedAddresses
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "yours-header") { SectionHeader(title = stringResource(R.string.emoji_packs_yours)) }
            if (state.yourPacks.isEmpty()) {
                item(key = "yours-empty") {
                    Text(
                        text = stringResource(R.string.emoji_packs_yours_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.yourPacks, key = { "yours:${it.address.pubkey}:${it.address.identifier}" }) { owned ->
                PackRow(
                    pack = owned.pack,
                    fallbackTitle = owned.address.identifier,
                    author = state.authors[owned.address.pubkey],
                    authorPubkey = owned.address.pubkey,
                    storageLabel = stringResource(
                        if (owned.storage == PackStorage.PUBLIC_LIST) R.string.emoji_packs_storage_public
                        else R.string.emoji_packs_storage_device
                    )
                ) {
                    OwnedPackMenu(
                        owned = owned,
                        canPublish = state.canPublish,
                        onRemove = { onRemove(owned) },
                        onMove = { onMove(owned) }
                    )
                }
            }

            item(key = "discover-header") {
                SectionHeader(
                    title = stringResource(R.string.emoji_packs_discover),
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.emoji_packs_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val discover = state.visibleDiscover
            if (discover.isEmpty()) {
                item(key = "discover-empty") {
                    Text(
                        text = stringResource(
                            if (state.discover.isEmpty()) R.string.emoji_packs_discover_loading
                            else R.string.emoji_packs_discover_no_match
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(discover, key = { "discover:${it.address.pubkey}:${it.address.identifier}" }) { pack ->
                PackRow(
                    pack = pack,
                    fallbackTitle = pack.title,
                    author = state.authors[pack.address.pubkey],
                    authorPubkey = pack.address.pubkey
                ) {
                    if (pack.address in picked) {
                        ChipBadge(text = stringResource(R.string.emoji_packs_added))
                    } else {
                        FilledTonalButton(
                            onClick = {
                                if (state.canPublish) packToAdd = pack else onAdd(pack, PackStorage.THIS_DEVICE)
                            }
                        ) { Text(stringResource(R.string.emoji_packs_add)) }
                    }
                }
            }
        }
    }
}

/** One pack: cover, title, author and size, a strip of its emoji, and [trailing] actions. */
@Composable
private fun PackRow(
    pack: EmojiPack?,
    fallbackTitle: String,
    author: UserProfile?,
    authorPubkey: String,
    storageLabel: String? = null,
    trailing: @Composable () -> Unit
) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = pack?.image ?: pack?.emojis?.firstOrNull()?.url,
                contentDescription = null,
                placeholder = placeholder,
                error = placeholder,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pack?.title ?: fallbackTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val authorName = author?.getUserDisplayName() ?: authorPubkey.truncatePublicKey()
                Text(
                    text = if (pack == null) {
                        stringResource(R.string.emoji_packs_loading_pack, authorName)
                    } else {
                        pluralStringResource(R.plurals.emoji_packs_byline, pack.emojis.size, authorName, pack.emojis.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                storageLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
        val preview = pack?.emojis.orEmpty().take(PREVIEW_COUNT)
        if (preview.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.forEach { emoji ->
                    AsyncImage(
                        model = emoji.url,
                        contentDescription = emoji.shortcode,
                        placeholder = placeholder,
                        error = placeholder,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                    )
                }
            }
        }
    }
}

private const val PREVIEW_COUNT = 8

@Composable
private fun OwnedPackMenu(
    owned: OwnedEmojiPack,
    canPublish: Boolean,
    onRemove: () -> Unit,
    onMove: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.emoji_packs_more_cd))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canPublish) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (owned.storage == PackStorage.PUBLIC_LIST) R.string.emoji_packs_move_to_device
                                else R.string.emoji_packs_move_to_public
                            )
                        )
                    },
                    onClick = {
                        open = false
                        onMove()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.emoji_packs_remove)) },
                onClick = {
                    open = false
                    onRemove()
                }
            )
        }
    }
}

/** Where a newly picked pack goes — the one decision adding a pack needs. */
@Composable
private fun AddPackDialog(
    pack: EmojiPack,
    onChoose: (PackStorage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emoji_packs_add_title, pack.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The private choice leads and is the one in the accent colour.
                StorageOption(
                    title = stringResource(R.string.emoji_packs_storage_device),
                    body = stringResource(R.string.emoji_packs_add_device_body),
                    prominent = true,
                    onClick = { onChoose(PackStorage.THIS_DEVICE) }
                )
                StorageOption(
                    title = stringResource(R.string.emoji_packs_storage_public),
                    body = stringResource(R.string.emoji_packs_add_public_body),
                    prominent = false,
                    onClick = { onChoose(PackStorage.PUBLIC_LIST) }
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun StorageOption(title: String, body: String, prominent: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
