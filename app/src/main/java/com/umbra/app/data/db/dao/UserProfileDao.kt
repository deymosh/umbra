package com.umbra.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.umbra.app.data.db.entities.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles WHERE pubkey = :pubkey")
    suspend fun getProfile(pubkey: String): UserProfileEntity?

    // Reactive counterpart of getProfile: Room re-queries and re-emits on every write to this
    // row, so a subscriber always converges on the latest state (e.g. NIP-05 verification
    // landing asynchronously after the initial save) instead of depending on a broadcast event
    // arriving while something happens to be listening.
    @Query("SELECT * FROM user_profiles WHERE pubkey = :pubkey")
    fun observeProfile(pubkey: String): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profiles WHERE pubkey IN (:pubkeys)")
    suspend fun getProfiles(pubkeys: List<String>): List<UserProfileEntity>

    @Query("SELECT COUNT(*) FROM user_profiles")
    suspend fun countProfiles(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<UserProfileEntity>)

    @Query("DELETE FROM user_profiles")
    suspend fun deleteAll()

    // Delete stale profiles, excluding the signed-in user's own row (if any) — the current
    // user's own profile shouldn't be evicted just because they haven't edited it recently; only
    // cached copies of *other* people's profiles are meant to be swept here.
    @Query("DELETE FROM user_profiles WHERE updatedAt < :threshold AND (:excludePubkey IS NULL OR pubkey != :excludePubkey)")
    suspend fun deleteStaleProfiles(threshold: Long, excludePubkey: String?): Int

    // Check if profile exists and is fresh (avoid unnecessary network fetch)
    @Query("SELECT COUNT(*) FROM user_profiles WHERE pubkey = :pubkey AND updatedAt > :freshThreshold")
    suspend fun isFresh(pubkey: String, freshThreshold: Long): Int

    // Prefix match against name/displayName for @-mention autocomplete — local cache only, no
    // relay round trip. [query] must already be escaped/lowercased by the caller since LIKE's `%`
    // wildcard is applied here; matches SQLite's default case-insensitive-for-ASCII LIKE behavior.
    @Query(
        "SELECT * FROM user_profiles WHERE name LIKE :query || '%' OR displayName LIKE :query || '%' " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun searchProfilesByName(query: String, limit: Int): List<UserProfileEntity>
}
