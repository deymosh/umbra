package com.umbra.app.data.db

import androidx.room.TypeConverter
import com.umbra.app.domain.nip30.CustomEmoji

class RoomConverters {

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        if (value == null) return null
        return value.joinToString(separator = "\u001F")
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        if (value == null) return null
        if (value.isEmpty()) return emptyList()
        return value.split("\u001F")
    }

    /**
     * NIP-30 profile emoji: one row per `emoji` tag ("shortcode|url"), lines joined with \u001E.
     * Shortcodes/URLs can contain neither a pipe nor a newline (shortcodes are [A-Za-z0-9_-]+,
     * only http(s) URLs parse at all), so the encoding is lossless for anything
     * extractCustomEmojis accepted.
     */
    @TypeConverter
    fun fromCustomEmojiList(value: List<CustomEmoji>?): String? {
        if (value == null) return null
        if (value.isEmpty()) return ""
        return value.joinToString(separator = "\u001E") { "${it.shortcode}|${it.url}" }
    }

    @TypeConverter
    fun toCustomEmojiList(value: String?): List<CustomEmoji> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split("\u001E").mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0 || i == line.lastIndex) return@mapNotNull null
            CustomEmoji(shortcode = line.substring(0, i), url = line.substring(i + 1))
        }
    }
}
