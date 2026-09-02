package com.umbra.app.data.db.pojo

import androidx.room.ColumnInfo

/**
 * Minimal (id, timestamp) projection — for building a [com.umbra.app.domain.nip77.NegentropyStorageVector]
 * index without paying for a full [com.umbra.app.data.db.entities.EventEntity] row load (tags,
 * content, sig) that NIP-77 sync has no use for.
 */
data class EventIdTimestamp(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
