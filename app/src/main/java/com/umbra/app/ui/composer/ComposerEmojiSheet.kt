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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.ColorPainter
import coil3.compose.AsyncImage
import com.umbra.app.R
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.EmojiGroup
import com.umbra.app.ui.components.LocalCustomEmojiGroups

/**
 * Frequent Unicode emoji the composer's picker leads with. Deliberately a small, shared list —
 * the user's own NIP-30 catalog gets its own named sections below it.
 */
private val COMMON_EMOJIS = listOf(
    "😀", "😂", "😊", "😍", "🤔", "🙃", "😴", "😭",
    "🤝", "👍", "👎", "👏", "🙏", "💪", "😎", "🤗",
    "❤️", "🔥", "✨", "⭐", "🎉", "😉", "😮", "😅",
    "🚀", "🛠️", "📌", "✅", "❌", "💡", "⚡", "🌈"
)

/**
 * Emoji picker for the composer: a searchable "Common" Unicode section plus the user's own
 * NIP-30 groups, read from [LocalCustomEmojiGroups]. Tapping inserts into the note and the
 * sheet stays open for repeated picks; dismissing is swipe or back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerEmojiSheet(
    onInsertUnicode: (String) -> Unit,
    onInsertCustom: (CustomEmoji) -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        ComposerEmojiContent(
            groups = LocalCustomEmojiGroups.current,
            onInsertUnicode = onInsertUnicode,
            onInsertCustom = onInsertCustom
        )
    }
}

/** Stateless content, split off [ComposerEmojiSheet] so snapshot tests can render it alone. */
@Composable
internal fun ComposerEmojiContent(
    groups: List<EmojiGroup>,
    onInsertUnicode: (String) -> Unit,
    onInsertCustom: (CustomEmoji) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.composer_emoji_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
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

        val trimmedQuery = query.trim()
        val commonTitle = stringResource(R.string.composer_emoji_common)
        val yourEmojiTitle = stringResource(R.string.composer_emoji_yours)
        val sections = buildList {
            val common = matchingCommonEmojis(trimmedQuery)
            if (common.isNotEmpty() || trimmedQuery.isEmpty()) {
                add(EmojiSection(title = commonTitle, emojis = common.map { EmojiEntry.Uni(it) }))
            }
            groups.forEach { group ->
                val emojis = group.emojis
                    .filter { trimmedQuery.isEmpty() || it.shortcode.contains(trimmedQuery, ignoreCase = true) }
                    .map { EmojiEntry.CustomGlyph(it) }
                if (emojis.isNotEmpty()) {
                    add(EmojiSection(title = group.title ?: yourEmojiTitle, emojis = emojis))
                }
            }
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
            sections.forEach { section ->
                // Full-span header row so the section title sits above its whole tile block.
                item(span = { GridItemSpan(maxLineSpan) }, key = "header:${section.title}") {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                items(section.emojis, key = { it.key }) { emoji ->
                    val onClick = when (emoji) {
                        is EmojiEntry.Uni -> ({ onInsertUnicode(emoji.glyph) })
                        is EmojiEntry.CustomGlyph -> ({ onInsertCustom(emoji.emoji) })
                    }
                    EmojiTile(onClick = onClick) { EmojiTileEmoji(emoji) }
                }
            }
        }
    }
}

// Search applies only while the user has typed something: a non-empty query keeps common
// Unicode entries only on an exact glyph match, custom emoji on a substring shortcode match.
private fun matchingCommonEmojis(query: String): List<String> =
    if (query.isEmpty()) COMMON_EMOJIS else COMMON_EMOJIS.filter { it == query }

private sealed interface EmojiEntry {
    data class Uni(val glyph: String) : EmojiEntry
    data class CustomGlyph(val emoji: CustomEmoji) : EmojiEntry

    val key: String
        get() = when (this) {
            is Uni -> "u:$glyph"
            is CustomGlyph -> "c:${emoji.shortcode}"
        }
}

/** One emoji tile: the same 48dp round target every picker section uses. */
@Composable
private fun EmojiTile(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// Custom and Unicode emoji render at the same visual size so the grid stays even.
@Composable
private fun EmojiTileEmoji(emoji: EmojiEntry) {
    when (emoji) {
        is EmojiEntry.Uni -> Text(
            text = emoji.glyph,
            fontSize = 28.sp
        )
        is EmojiEntry.CustomGlyph -> AsyncImage(
            model = emoji.emoji.url,
            contentDescription = emoji.emoji.shortcode,
            contentScale = ContentScale.Fit,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
            modifier = Modifier
                .size(32.dp)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
        )
    }
}

private data class EmojiSection(val title: String, val emojis: List<EmojiEntry>)
