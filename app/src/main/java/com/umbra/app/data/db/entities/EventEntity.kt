package com.umbra.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["pubkey"]),
        Index(value = ["kind"]),
        Index(value = ["created_at"]),
        Index(value = ["kind", "created_at"]),
        Index(value = ["pubkey", "kind", "created_at"])
    ]
)
data class EventEntity(
    @PrimaryKey val id: String,
    val pubkey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val kind: Int,
    val content: String,
    val sig: String,
    val tagsJson: String
)
