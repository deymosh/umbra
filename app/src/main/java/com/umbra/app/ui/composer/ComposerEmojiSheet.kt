package com.umbra.app.ui.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.umbra.app.R
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.EmojiGroup
import androidx.compose.ui.text.style.TextAlign
import com.umbra.app.ui.components.LocalCustomEmojiGroups

/**
 * The composer's custom emoji picker: the user's NIP-30 emoji, one section per pack, searchable
 * by shortcode. Ordinary emoji come from the keyboard, so they aren't repeated here. Tapping
 * inserts the emoji (shown as its image in the editor) and the sheet stays open for more picks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerEmojiSheet(
    onInsert: (CustomEmoji) -> Unit,
    onManagePacks: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        ComposerEmojiContent(
            groups = LocalCustomEmojiGroups.current,
            onInsert = onInsert,
            onManagePacks = onManagePacks
        )
    }
}

/** Stateless content, split off [ComposerEmojiSheet] so snapshot tests can render it alone. */
@Composable
internal fun ComposerEmojiContent(
    groups: List<EmojiGroup>,
    onInsert: (CustomEmoji) -> Unit,
    onManagePacks: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.composer_emoji_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onManagePacks) {
                Text(stringResource(R.string.composer_emoji_manage_packs))
            }
        }

        if (groups.none { it.emojis.isNotEmpty() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.composer_emoji_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.composer_emoji_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.reaction_picker_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp)
        )

        val trimmedQuery = query.trim().trim(':')
        val yourEmojiTitle = stringResource(R.string.composer_emoji_yours)
        val sections = groups.mapNotNull { group ->
            val emojis = group.emojis.filter {
                trimmedQuery.isEmpty() || it.shortcode.contains(trimmedQuery, ignoreCase = true)
            }
            if (emojis.isEmpty()) null else (group.title ?: yourEmojiTitle) to emojis
        }
        // Cap the grid at 60% of the window height so a large catalog never pushes the sheet
        // off-screen. Window size (LocalWindowInfo), not display size (Configuration), bounds it.
        val maxGridHeight = with(LocalDensity.current) {
            (LocalWindowInfo.current.containerSize.height.toDp() * 0.6f)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(52.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxGridHeight)
        ) {
            // Keys by section position: two packs may share a title, and a shortcode may appear
            // in more than one pack.
            sections.forEachIndexed { sectionIndex, (title, emojis) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "header:$sectionIndex") {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                items(emojis, key = { "$sectionIndex:${it.shortcode}" }) { emoji ->
                    EmojiTile(emoji = emoji, onClick = { onInsert(emoji) })
                }
            }
        }
    }
}

/** One emoji tile: the same round target every picker section uses. */
@Composable
private fun EmojiTile(emoji: CustomEmoji, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = emoji.url,
            contentDescription = emoji.shortcode,
            contentScale = ContentScale.Fit,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
            modifier = Modifier
                .size(32.dp)
                .clip(MaterialTheme.shapes.small)
        )
    }
}
