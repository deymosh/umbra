package com.umbra.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.umbra.app.data.db.entities.EventTagEntity

/**
 * DAO for the [EventTagEntity] table.
 *
 * Tags are inserted in the same batch as their parent event and are used
 * exclusively as a fast reverse-lookup index by [EventDao]'s JOIN queries.
 * No reads are expected from this DAO directly.
 */
@Dao
interface EventTagDao {

    @Upsert
    suspend fun insertTags(tags: List<EventTagEntity>)

    @Query("DELETE FROM event_tags WHERE event_id = :eventId")
    suspend fun deleteTagsForEvent(eventId: String)

    @Query("DELETE FROM event_tags WHERE event_id IN (:eventIds)")
    suspend fun deleteTagsForEvents(eventIds: List<String>)

    @Query("DELETE FROM event_tags")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM event_tags")
    suspend fun countTags(): Int
}
