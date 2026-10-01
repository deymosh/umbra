package com.umbra.app.data.db.entities

import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Entity
import com.umbra.app.domain.nip30.CustomEmoji

@Entity(
    tableName = "user_profiles",
    indices = [
        Index(value = ["updatedAt"])
    ]
)
data class UserProfileEntity(
    @PrimaryKey val pubkey: String,
    val name: String?,
    val displayName: String?,
    val picture: String?,
    val banner: String?,
    val about: String?,
    val nip05: String?,
    val lud16: String?,
    val lud06: String?,
    val website: String?,
    val nip05VerificationState: String = "NotAvailable",  // Serialized Nip05VerificationState enum
    // NIP-30 `emoji` tags of the kind-0 event this profile came from (NIP-30 inline
    // `:shortcode:` rendering; see UserProfile.customEmojis).
    val customEmojis: List<CustomEmoji> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)
