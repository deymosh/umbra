package com.umbra.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Nostr relay configurations.
 * Persisted to SQLite for SSoT relay management.
 */
@Entity(
    tableName = "relays",
    indices = [
        Index("url", unique = true),  // No duplicate URLs
        Index("isEnabled"),             // Query filter for enabled relays
    ]
)
data class RelayEntity(
    @PrimaryKey
    val id: String,

    val url: String,
    val isEnabled: Boolean = true,
    val isReadEnabled: Boolean = true,
    val isReadActive: Boolean = true,
    val isWriteEnabled: Boolean = true,
    val isWriteActive: Boolean = true,
    val isDmEnabled: Boolean = false,
    val isDmActive: Boolean = false,
    val dmRequiresAuth: Boolean = false,

    // See Relay.isSearchEnabled/isIndexEnabled — the user's declared NIP-51 search (kind 10007)
    // and index (kind 10086) relay lists, modeled as first-class roles the same way DM is.
    val isSearchEnabled: Boolean = false,
    val isSearchActive: Boolean = false,
    val isIndexEnabled: Boolean = false,
    val isIndexActive: Boolean = false,

    // Normalized relay type flag (avoid computation on query)
    val isOnion: Boolean = false,

    // See Relay.isDiscovered — auto-added for followed-author outbox coverage, not user-added.
    val isDiscovered: Boolean = false,

    val connectionTimeoutMs: Long = 5000L,
    val addedAtMillis: Long = System.currentTimeMillis(),

    // NIP-11 relayInfo as JSON (optional, can be null)
    val relayInfoJson: String? = null,

    // Epoch millis when NIP-11 was last successfully fetched (null = never)
    val nip11FetchedAtMillis: Long? = null
)
