package com.umbra.app.ui.components

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.umbra.app.R

private val PROFILE_EMOJI_REGEX = Regex(":([A-Za-z0-9_+-]+):")

/**
 * NIP-30 inline `:shortcode:` rendering for short profile text (display name, about): a
 * shortcode present in [customEmojis] (shortcode → https URL, from the profile event's own
 * `emoji` tags) becomes an inline image at line height; anything unmatched renders as the
 * literal `:shortcode:` text. Long text still ellipsizes via [maxLines]/[overflow] — same
 * contract as a plain Text.
 */
@Composable
fun CustomEmojiText(
    text: String,
    customEmojis: Map<String, String>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val context = LocalContext.current
    val annotated = remember(text, customEmojis) { annotateProfileEmoji(text, customEmojis) }
    val inlineContent = remember(text, customEmojis) {
        customEmojis
            .filter { (_, url) -> url.startsWith("https://") || url.startsWith("http://") }
            .filterKeys { shortcode -> text.contains(":$shortcode:") }
            .mapValues { (shortcode, url) ->
                InlineTextContent(
                    // 1em keeps the image at the surrounding text's line height; TextCenter
                    // aligns it like the note renderer does for in-note emoji.
                    Placeholder(
                        width = 1.em,
                        height = 1.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                    )
                ) {
                    AsyncImage(
                        model = url,
                        contentDescription = context.getString(
                            R.string.custom_emoji_content_description,
                            shortcode
                        ),
                        modifier = Modifier
                    )
                }
            }
            .mapKeys { (shortcode, _) -> customEmojiInlineContentId(shortcode) }
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = if (color == Color.Unspecified) style.color else color),
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent
    )
}

/**
 * Builds [text] with every `:shortcode:` that exists in [customEmojis] replaced by an
 * [appendInlineContent] reference (keyed by [customEmojiInlineContentId]); unmatched
 * shortcodes stay literal.
 */
internal fun annotateProfileEmoji(
    text: String,
    customEmojis: Map<String, String>
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in PROFILE_EMOJI_REGEX.findAll(text)) {
        val shortcode = match.groupValues.getOrNull(1).orEmpty()
        if (customEmojis.containsKey(shortcode)) {
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            appendInlineContent(customEmojiInlineContentId(shortcode), ":$shortcode:")
            cursor = match.range.last + 1
        }
    }
    if (cursor < text.length) append(text.substring(cursor))
}
