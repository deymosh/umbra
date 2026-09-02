package com.umbra.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.umbra.app.data.db.entities.RelayEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for relay persistence and querying.
 * All queries return Flow<T> to transparently handle invalidation
 * when underlying tables change.
 */
@Dao
interface RelayDao {

    /**
     * Get all relays (ordered by addedAtMillis descending)
     */
    @Query("SELECT * FROM relays ORDER BY addedAtMillis DESC")
    fun getAllRelays(): Flow<List<RelayEntity>>

    /**
     * Get a single relay by URL
     */
    @Query("SELECT * FROM relays WHERE url = :url LIMIT 1")
    suspend fun getRelayByUrl(url: String): RelayEntity?

    /**
     * Get a single relay by ID
     */
    @Query("SELECT * FROM relays WHERE id = :id LIMIT 1")
    suspend fun getRelayById(id: String): RelayEntity?

    /**
     * Insert a relay (replace if ID already exists)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelay(relay: RelayEntity)

    /**
     * Insert multiple relays atomically
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelays(relays: List<RelayEntity>)

    /**
     * Update an existing relay
     */
    @Update
    suspend fun updateRelay(relay: RelayEntity)

    /**
     * Delete a relay by ID
     */
    @Query("DELETE FROM relays WHERE id = :id")
    suspend fun deleteRelayById(id: String)

    /**
     * Delete all relays
     */
    @Query("DELETE FROM relays")
    suspend fun deleteAll()

    /**
     * Count total relays
     */
    @Query("SELECT COUNT(*) FROM relays")
    suspend fun countRelays(): Int
}
