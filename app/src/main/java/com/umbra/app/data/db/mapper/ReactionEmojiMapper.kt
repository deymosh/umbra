package com.umbra.app.data.db.mapper

import com.umbra.app.data.db.entities.ReactionEmojiEntity
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji

fun ReactionEmojiEntity.toDomain(): ReactionEmoji? {
    val unicode = unicodeEmoji
    if (unicode != null) return ReactionEmoji.Unicode(unicode)
    val shortcode = customShortcode
    val url = customUrl
    if (shortcode != null && url != null) return ReactionEmoji.Custom(CustomEmoji(shortcode, url))
    return null
}

fun ReactionEmoji.toEntity(sortOrder: Int): ReactionEmojiEntity = when (this) {
    is ReactionEmoji.Unicode -> ReactionEmojiEntity(key = key, unicodeEmoji = emoji, sortOrder = sortOrder)
    is ReactionEmoji.Custom -> ReactionEmojiEntity(
        key = key,
        customShortcode = emoji.shortcode,
        customUrl = emoji.url,
        sortOrder = sortOrder
    )
}
