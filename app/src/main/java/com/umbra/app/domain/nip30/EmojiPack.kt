package com.umbra.app.domain.nip30

import com.umbra.app.domain.nip01.Event

/**
 * A NIP-51 kind-30030 emoji set as shown when browsing packs: its address, the NIP-51 set
 * metadata (`title`, `description`, `image`), and its emoji.
 */
data class EmojiPack(
    val address: EmojiSetAddress,
    val title: String,
    val description: String?,
    val image: String?,
    val emojis: List<CustomEmoji>,
    val createdAt: Long
)

/** The `30030:<pubkey>:<d>` coordinate an `a` tag uses to point at this set. */
fun EmojiSetAddress.coordinate(): String = "$KIND_EMOJI_SET:$pubkey:$identifier"

/**
 * Parses a kind-30030 [event] into an [EmojiPack], or null when it isn't one or has no usable
 * emoji. Shortcodes outside NIP-30's alphabet are dropped, the title falls back to the `d`
 * identifier, and only an http(s) cover image is kept (it feeds an image loader).
 */
fun parseEmojiPack(event: Event): EmojiPack? {
    if (event.kind != KIND_EMOJI_SET) return null
    val identifier = event.getTagValue("d") ?: return null
    val emojis = extractCustomEmojis(event.tags).values.filter { isValidShortcode(it.shortcode) }
    if (emojis.isEmpty()) return null
    return EmojiPack(
        address = EmojiSetAddress(event.pubkey.lowercase(), identifier),
        title = event.getTagValue("title")?.takeIf { it.isNotBlank() }
            ?: event.getTagValue("name")?.takeIf { it.isNotBlank() }
            ?: identifier,
        description = event.getTagValue("description")?.takeIf { it.isNotBlank() },
        image = event.getTagValue("image")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        emojis = emojis,
        createdAt = event.createdAt
    )
}

/** The latest revision of each set among [events], newest pack first. */
fun latestEmojiPacks(events: List<Event>): List<EmojiPack> =
    events.asSequence()
        .mapNotNull(::parseEmojiPack)
        .groupBy { it.address }
        .values
        .map { revisions -> revisions.maxBy { it.createdAt } }
        .sortedByDescending { it.createdAt }
