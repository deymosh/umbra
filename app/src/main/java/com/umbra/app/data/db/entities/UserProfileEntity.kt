package com.umbra.app.data.db.entities

import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Entity

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
    val updatedAt: Long = System.currentTimeMillis()
)
