package com.umbra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.ColorPainter
import coil3.compose.AsyncImage
import com.umbra.app.R
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.EmojiGroup

/**
 * Grid picker for a NIP-25 reaction. [reactionEmojis] is the user's own fully editable quick
 * reaction list (Unicode and image-backed custom emoji alike); their NIP-30 catalog comes from
 * [LocalCustomEmojiGroups], so every saved shortcode can be reacted with too. Selecting any
 * entry calls [onSelect] with the content to publish and dismisses; edit mode (top-right toggle)
 * turns taps into removals / additions instead of reacting, replacing the old hidden long-press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiReactionPickerSheet(
    reactionEmojis: List<ReactionEmoji>,
    onSelect: (content: String, emoji: CustomEmoji?) -> Unit,
    onAddReactionEmoji: (ReactionEmoji) -> Unit,
    onRemoveReactionEmoji: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        ReactionPickerContent(
            reactionEmojis = reactionEmojis,
            customGroups = LocalCustomEmojiGroups.current,
            onSelect = onSelect,
            onAddReactionEmoji = onAddReactionEmoji,
            onRemoveReactionEmoji = onRemoveReactionEmoji
        )
    }
}

/** Stateless content, split off [EmojiReactionPickerSheet] so snapshot tests can render it alone. */
@Composable
internal fun ReactionPickerContent(
    reactionEmojis: List<ReactionEmoji>,
    customGroups: List<EmojiGroup>,
    onSelect: (content: String, emoji: CustomEmoji?) -> Unit,
    onAddReactionEmoji: (ReactionEmoji) -> Unit,
    onRemoveReactionEmoji: (String) -> Unit,
    editMode: Boolean = false
) {
    var editMode by rememberSaveable { mutableStateOf(editMode) }
    var query by rememberSaveable { mutableStateOf("") }
    var isAddingCustom by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.reaction_picker_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { editMode = !editMode }) {
                Text(
                    text = stringResource(
                        if (editMode) R.string.reaction_picker_done else R.string.reaction_picker_edit
                    )
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.reaction_picker_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        val trimmedQuery = query.trim().lowercase()
        val quickMatch = reactionEmojis.filter {
            trimmedQuery.isEmpty() || matchesReactionQuery(it, trimmedQuery)
        }
        val matchedGroups = customGroups
            .map { group ->
                group to group.emojis.filter {
                    trimmedQuery.isEmpty() || it.shortcode.contains(trimmedQuery, ignoreCase = true)
                }
            }
            .filter { (_, emojis) -> emojis.isNotEmpty() }

        val quickReactionsTitle = stringResource(R.string.reaction_picker_quick_reactions)
        val yourEmojiTitle = stringResource(R.string.reaction_picker_your_emoji)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(52.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            // Most saved reactions and sets fit on a screen; the cap keeps huge catalogs from
            // stretching the sheet past 60% of the display so reactions stay a quick gesture.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.6f })
        ) {
            if (quickMatch.isNotEmpty() || trimmedQuery.isEmpty()) {
                gridHeader(quickReactionsTitle)
                items(quickMatch, key = { "pa:${it.key}" }) { entry ->
                    PickerEmojiTile(
                        entry = entry.toTileData(),
                        badge = if (editMode) TileBadge.Remove else null,
                        onClick = {
                            if (editMode) {
                                onRemoveReactionEmoji(entry.key)
                            } else {
                                when (entry) {
                                    is ReactionEmoji.Unicode -> onSelect(entry.emoji, null)
                                    is ReactionEmoji.Custom -> onSelect(":${entry.emoji.shortcode}:", entry.emoji)
                                }
                            }
                        }
                    )
                }
            }
            matchedGroups.forEach { (group, emojis) ->
                gridHeader(group.title ?: yourEmojiTitle)
                items(emojis, key = { "cg:${it.shortcode}" }) { emoji ->
                    val inQuick = reactionEmojis.any { (it as? ReactionEmoji.Custom)?.emoji?.shortcode == emoji.shortcode }
                    PickerEmojiTile(
                        entry = TileData.Custom(emoji),
                        badge = when {
                            editMode && !inQuick -> TileBadge.Add
                            else -> null
                        },
                        onClick = {
                            when {
                                editMode && inQuick -> Unit
                                editMode -> onAddReactionEmoji(ReactionEmoji.Custom(emoji))
                                else -> {
                                    onSelect(":${emoji.shortcode}:", emoji)
                                    // Selecting dismisses the sheet from the parent; nothing extra here.
                                }
                            }
                        }
                    )
                }
            }
        }

        if (customGroups.isEmpty()) {
            Text(
                text = stringResource(R.string.reaction_picker_empty_custom),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (editMode) {
            if (isAddingCustom) {
                AddReactionEmojiRow(
                    onAdd = onAddReactionEmoji
                )
            } else {
                TextButton(onClick = { isAddingCustom = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.reaction_picker_add_custom_emoji),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

private sealed interface TileData {
    data class Uni(val glyph: String) : TileData
    data class Custom(val emoji: CustomEmoji) : TileData
}

private fun ReactionEmoji.toTileData(): TileData = when (this) {
    is ReactionEmoji.Unicode -> TileData.Uni(emoji)
    is ReactionEmoji.Custom -> TileData.Custom(emoji)
}

private fun matchesReactionQuery(entry: ReactionEmoji, query: String): Boolean = when (entry) {
    is ReactionEmoji.Unicode -> entry.emoji == query
    is ReactionEmoji.Custom -> entry.emoji.shortcode.contains(query, ignoreCase = true)
}

private enum class TileBadge { Remove, Add }

/**
 * One picker tile: the same 48dp round target whatever it renders. Unicode glyphs are drawn at
 * 28sp so text and image emoji read at the same visual size side by side.
 */
@Composable
private fun PickerEmojiTile(
    entry: TileData,
    badge: TileBadge?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (entry) {
            is TileData.Uni -> Text(
                text = entry.glyph,
                fontSize = 28.sp
            )
            is TileData.Custom -> AsyncImage(
                model = entry.emoji.url,
                contentDescription = entry.emoji.shortcode,
                contentScale = ContentScale.Fit,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier
                    .size(32.dp)
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small)
            )
        }
        when (badge) {
            TileBadge.Remove -> TileBadgeDot(icon = Icons.Default.Close, contentDescription = null)
            TileBadge.Add -> TileBadgeDot(icon = Icons.Default.Add, contentDescription = null)
            null -> Unit
        }
    }
}

/** A small 18dp round badge in the tile's top-end corner (edit-mode remove / add affordance). */
@Composable
private fun BoxScope.TileBadgeDot(icon: ImageVector, contentDescription: String?) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(12.dp)
        )
    }
}

private fun LazyGridScope.gridHeader(title: String) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "hdr:$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}

/**
 * One field doubles as both a literal Unicode emoji (when [url] is left blank — added as
 * [ReactionEmoji.Unicode]) and a custom emoji's shortcode (when [url] is a valid http(s) URL —
 * added as [ReactionEmoji.Custom]), so a single row covers adding either kind.
 */
@Composable
private fun AddReactionEmojiRow(onAdd: (ReactionEmoji) -> Unit) {
    var emojiOrShortcode by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    val trimmedValue = emojiOrShortcode.trim()
    val trimmedUrl = url.trim()
    val isValidUrl = trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")
    val canAdd = trimmedValue.isNotBlank() && (trimmedUrl.isBlank() || isValidUrl)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
    ) {
        // 32dp live preview of what a custom emoji's image actually points at — a broken or
        // wrong URL is visible before it's saved.
        if (trimmedUrl.isNotBlank()) {
            AsyncImage(
                model = trimmedUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        } else {
            Spacer(modifier = Modifier.size(32.dp))
        }
        OutlinedTextField(
            value = emojiOrShortcode,
            onValueChange = { emojiOrShortcode = it },
            label = { Text(stringResource(R.string.reaction_picker_shortcode_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.reaction_picker_image_url_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1.4f)
        )
        IconButton(onClick = { if (canAdd) onAdd(if (trimmedUrl.isBlank()) ReactionEmoji.Unicode(trimmedValue) else ReactionEmoji.Custom(CustomEmoji(shortcode = trimmedValue, url = trimmedUrl))) }, enabled = canAdd) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.reaction_picker_add_action)
            )
        }
    }
}
