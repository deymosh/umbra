package com.umbra.app.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.umbra.app.R
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji

/**
 * Grid picker for a NIP-25 reaction — [reactionEmojis] is the user's own fully editable list
 * (Unicode and image-backed custom emoji alike; the shipped defaults are just normal seeded
 * entries in it, not a fixed set — see ReactionEmojiRepository), with an inline row to add
 * another of either kind. Selecting any entry calls [onSelect] with the content to publish and
 * dismisses; long-pressing an entry removes it via [onRemoveReactionEmoji] instead of selecting.
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
    var isAdding by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.reaction_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(reactionEmojis, key = { it.key }, contentType = { entry ->
                    when (entry) {
                        is ReactionEmoji.Unicode -> "unicode"
                        is ReactionEmoji.Custom -> "custom"
                    }
                }) { entry ->
                    when (entry) {
                        is ReactionEmoji.Unicode -> Text(
                            text = entry.emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        onSelect(entry.emoji, null)
                                        onDismissRequest()
                                    },
                                    onLongClick = { onRemoveReactionEmoji(entry.key) }
                                )
                                .padding(8.dp)
                        )

                        is ReactionEmoji.Custom -> AsyncImage(
                            model = entry.emoji.url,
                            contentDescription = entry.emoji.shortcode,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        onSelect(":${entry.emoji.shortcode}:", entry.emoji)
                                        onDismissRequest()
                                    },
                                    onLongClick = { onRemoveReactionEmoji(entry.key) }
                                )
                                .padding(8.dp)
                                .size(28.dp)
                                .aspectRatio(1f)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (isAdding) {
                AddReactionEmojiRow(
                    onAdd = { emoji ->
                        onAddReactionEmoji(emoji)
                        isAdding = false
                    },
                    onCancel = { isAdding = false }
                )
            } else {
                TextButton(onClick = { isAdding = true }) {
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

/**
 * One field doubles as both a literal Unicode emoji (when [url] is left blank — added as
 * [ReactionEmoji.Unicode]) and a custom emoji's shortcode (when [url] is a valid http(s) URL —
 * added as [ReactionEmoji.Custom]), so a single row covers adding either kind.
 */
@Composable
private fun AddReactionEmojiRow(
    onAdd: (ReactionEmoji) -> Unit,
    onCancel: () -> Unit
) {
    var emojiOrShortcode by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
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
        val trimmedValue = emojiOrShortcode.trim()
        val trimmedUrl = url.trim()
        val isValidUrl = trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")
        val canAdd = trimmedValue.isNotBlank() && (trimmedUrl.isBlank() || isValidUrl)
        IconButton(
            onClick = {
                if (!canAdd) {
                    onCancel()
                    return@IconButton
                }
                val emoji = if (trimmedUrl.isBlank()) {
                    ReactionEmoji.Unicode(trimmedValue)
                } else {
                    ReactionEmoji.Custom(CustomEmoji(shortcode = trimmedValue, url = trimmedUrl))
                }
                onAdd(emoji)
            }
        ) {
            Icon(
                imageVector = if (canAdd) Icons.Default.Add else Icons.Default.Close,
                contentDescription = stringResource(R.string.reaction_picker_add_action)
            )
        }
    }
}
