package com.umbra.app.ui.composer

import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.ui.components.MENTION_URI_REGEX

/**
 * A run of the composer's raw text that is shown as something else: a `nostr:` mention URI shown
 * as "@name", or a known `:shortcode:` shown as its custom emoji image. The raw text keeps what
 * gets published; only the display changes.
 */
internal sealed interface ComposerToken {
    val start: Int
    val endExclusive: Int
    /** What the text field shows in place of the raw run. */
    val display: String

    data class Mention(override val start: Int, override val endExclusive: Int, override val display: String) : ComposerToken

    data class Emoji(override val start: Int, override val endExclusive: Int, val emoji: CustomEmoji) : ComposerToken {
        override val display: String get() = EMOJI_PLACEHOLDER
    }
}

/**
 * One em-wide character the image is drawn over: the text field and the overlay both lay out
 * this same character, so the emoji occupies exactly the space the caret steps over.
 */
internal const val EMOJI_PLACEHOLDER = " "

private val EMOJI_SHORTCODE_REGEX = Regex(":([A-Za-z0-9_-]+):")

/**
 * The tokens in [text], in order and never overlapping: every mention URI, then every
 * `:shortcode:` naming one of [emojis] that doesn't fall inside a mention. A shortcode the user
 * has no image for stays plain text.
 */
internal fun composerTokens(
    text: String,
    mentionLabel: (String) -> String,
    emojis: Map<String, CustomEmoji>
): List<ComposerToken> {
    val mentions = MENTION_URI_REGEX.findAll(text).map { match ->
        ComposerToken.Mention(match.range.first, match.range.last + 1, mentionLabel(match.value))
    }.toList()
    if (emojis.isEmpty() || !text.contains(':')) return mentions
    val emojiTokens = EMOJI_SHORTCODE_REGEX.findAll(text).mapNotNull { match ->
        val emoji = emojis[match.groupValues[1]] ?: return@mapNotNull null
        val start = match.range.first
        val end = match.range.last + 1
        if (mentions.any { start < it.endExclusive && end > it.start }) return@mapNotNull null
        ComposerToken.Emoji(start, end, emoji)
    }.toList()
    return (mentions + emojiTokens).sortedBy { it.start }
}

/** [text] with every token replaced by its [ComposerToken.display] — what the field shows. */
internal fun displayTextFor(text: String, tokens: List<ComposerToken>): String = buildString {
    var cursor = 0
    tokens.forEach { token ->
        append(text, cursor, token.start)
        append(token.display)
        cursor = token.endExclusive
    }
    append(text, cursor, text.length)
}
