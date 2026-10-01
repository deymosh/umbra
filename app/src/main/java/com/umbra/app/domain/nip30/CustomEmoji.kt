package com.umbra.app.domain.nip30

/**
 * NIP-30 custom emoji tag representation: ["emoji", shortcode, url].
 */
data class CustomEmoji(
    val shortcode: String,
    val url: String
)

/**
 * Parses "emoji" tags into a shortcode-keyed map. Only http(s) URLs are accepted — this feeds
 * an image loader, so a non-http(s) scheme (e.g. file://, javascript:, data:) is rejected rather
 * than handed to it. A duplicate shortcode keeps the last tag's URL (matches NIP-01 event tag
 * ordering conventions elsewhere: later tags take precedence).
 */
fun extractCustomEmojis(tags: List<List<String>>): Map<String, CustomEmoji> {
    return tags.asSequence()
        .filter { tag -> tag.getOrNull(0) == "emoji" }
        .mapNotNull { tag ->
            val shortcode = tag.getOrNull(1)?.trim().orEmpty()
            val url = tag.getOrNull(2)?.trim().orEmpty()
            if (shortcode.isBlank() || url.isBlank()) return@mapNotNull null
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
            shortcode to CustomEmoji(shortcode = shortcode, url = url)
        }
        .toMap()
}

/** NIP-51 user emoji list: the emoji a user has picked, inline or by reference to sets. */
const val KIND_USER_EMOJI_LIST = 10030

/** NIP-51 emoji set: a named, addressable collection of custom emoji. */
const val KIND_EMOJI_SET = 30030

private val SHORTCODE_REGEX = Regex("^[A-Za-z0-9_-]+$")

/** Coordinates of a `30030` emoji set referenced by an `a` tag. */
data class EmojiSetAddress(val pubkey: String, val identifier: String)

/**
 * Splits a kind-10030 list into its inline emoji and the `30030` sets it references. Only
 * well-formed `30030:<64-hex pubkey>:<d>` addresses are kept.
 */
fun parseUserEmojiList(tags: List<List<String>>): Pair<List<CustomEmoji>, List<EmojiSetAddress>> {
    val inline = extractCustomEmojis(tags).values.filter { SHORTCODE_REGEX.matches(it.shortcode) }
    val sets = tags.asSequence()
        .filter { it.getOrNull(0) == "a" }
        .mapNotNull { tag ->
            val parts = tag.getOrNull(1)?.split(":", limit = 3) ?: return@mapNotNull null
            if (parts.size != 3 || parts[0] != KIND_EMOJI_SET.toString()) return@mapNotNull null
            val pubkey = parts[1].lowercase()
            if (pubkey.length != 64 || pubkey.any { it !in '0'..'9' && it !in 'a'..'f' }) return@mapNotNull null
            EmojiSetAddress(pubkey, parts[2])
        }
        .distinct()
        .toList()
    return inline to sets
}

/**
 * The `emoji` tags a note needs: one per available emoji whose `:shortcode:` actually appears in
 * [content], so a note never carries tags for emoji it doesn't use.
 */
fun emojiTagsFor(content: String, available: List<CustomEmoji>): List<List<String>> {
    if (available.isEmpty() || !content.contains(':')) return emptyList()
    return available.asSequence()
        .distinctBy { it.shortcode }
        .filter { content.contains(":${it.shortcode}:") }
        .map { listOf("emoji", it.shortcode, it.url) }
        .toList()
}

/**
 * One named section of the user's NIP-30 emoji catalog: either their inline emoji ([title] null,
 * kind-10030) or a referenced kind-30030 set titled by that event.
 */
data class EmojiGroup(val title: String?, val emojis: List<CustomEmoji>)

/** Flattens the groups back into one deduplicated list, first occurrence of a shortcode wins. */
fun List<EmojiGroup>.allEmojis(): List<CustomEmoji> = flatMap { it.emojis }.distinctBy { it.shortcode }

/** Start index and partial shortcode of an in-progress `:query` the caret sits in. */
data class EmojiQuery(val startIndex: Int, val query: String)

/**
 * Detects an in-progress `:shortcode` before [caret]: the colon must start the text or follow
 * whitespace, and the run up to the caret must be shortcode characters only. A closed
 * `:shortcode:` (caret right after the second colon) is not a query.
 */
fun detectEmojiQuery(text: String, caret: Int): EmojiQuery? {
    if (caret <= 0 || caret > text.length) return null
    var i = caret - 1
    while (i >= 0 && text[i] != ':' && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) i--
    if (i < 0 || text[i] != ':') return null
    if (i > 0 && !text[i - 1].isWhitespace()) return null
    val query = text.substring(i + 1, caret)
    return if (query.isEmpty()) null else EmojiQuery(i, query)
}
