package com.umbra.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feed_filters",
    indices = [
        Index("name"),
        Index("createdAtMillis"),
        Index("updatedAtMillis")
    ]
)
data class FeedFilterEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val hideNsfw: Boolean = true,
    val mutedPubkeysJson: String = "[]",
    val excludedTagsJson: String = "[]",
    val excludedHashtagsJson: String = "[]",
    val excludedContentPrefixesJson: String = "[]",
    val isActive: Boolean = false,
    val scopeToFollows: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)
