package com.umbra.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Normalized storage for indexable Nostr event tags (e, p, t, a, d).
 *
 * Raw [tagsJson] on [EventEntity] is a JSON blob — querying it for engagement
 * counts would require a full-table LIKE scan. Instead, on every insert we
 * extract the indexable tags and persist them here so Room can resolve
 * reaction/reply/repost aggregates with a plain JOIN + COUNT.
 *
 * Primary key: (event_id, tag_name, tag_index) — one row per tag slot per event.
 * Index on (tag_name, tag_value) — drives the reverse lookup in [EventDao.observeProfileNotes].
 * Index on (event_id) — drives cascade deletes when the parent event is removed.
 */
@Entity(
    tableName = "event_tags",
    primaryKeys = ["event_id", "tag_name", "tag_index"],
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["tag_name", "tag_value", "event_id"]),
        Index(value = ["event_id", "tag_name", "tag_value"]),
        // Support reverse lookups by tag value first (common in hashtag/mention searches)
        Index(value = ["tag_value", "tag_name", "event_id"])
    ]
)
data class EventTagEntity(
    @ColumnInfo(name = "event_id")  val eventId:  String,
    @ColumnInfo(name = "tag_name")  val tagName:  String,
    @ColumnInfo(name = "tag_value") val tagValue: String,
    @ColumnInfo(name = "tag_index") val tagIndex: Int
)
