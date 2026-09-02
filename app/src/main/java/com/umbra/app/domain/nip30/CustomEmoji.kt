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
