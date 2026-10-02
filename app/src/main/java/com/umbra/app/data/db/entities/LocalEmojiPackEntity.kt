package com.umbra.app.data.db.entities

import androidx.room.Entity

/**
 * An emoji pack (kind-30030 set, by its `30030:<pubkey>:<d>` coordinate) an account keeps only
 * on this device — never published. Scoped by [accountPubkey] so one account's packs never show
 * under another.
 */
@Entity(tableName = "local_emoji_packs", primaryKeys = ["accountPubkey", "coordinate"])
data class LocalEmojiPackEntity(
    val accountPubkey: String,
    val coordinate: String,
    val addedAtMillis: Long
)
