package com.umbra.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry in the user's reaction picker list — either a plain Unicode emoji ([unicodeEmoji] set,
 * [customShortcode]/[customUrl] null) or an image-backed NIP-30 custom emoji (the reverse).
 */
@Entity(
    tableName = "reaction_emojis",
    indices = [Index("sortOrder")]
)
data class ReactionEmojiEntity(
    @PrimaryKey
    val key: String,
    val unicodeEmoji: String? = null,
    val customShortcode: String? = null,
    val customUrl: String? = null,
    val sortOrder: Int
)
