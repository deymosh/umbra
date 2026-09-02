package com.umbra.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.umbra.app.data.db.entities.FeedFilterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedFilterDao {

    @Query("SELECT * FROM feed_filters ORDER BY createdAtMillis ASC")
    fun observeAllFilters(): Flow<List<FeedFilterEntity>>

    @Query("SELECT * FROM feed_filters WHERE isActive = 1 ORDER BY createdAtMillis ASC")
    fun observeActiveFilters(): Flow<List<FeedFilterEntity>>

    @Query("SELECT * FROM feed_filters WHERE id = :id LIMIT 1")
    suspend fun getFilterById(id: String): FeedFilterEntity?

    @Query("SELECT COUNT(*) FROM feed_filters")
    suspend fun countFilters(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFilter(filter: FeedFilterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFilters(filters: List<FeedFilterEntity>)

    @Query("DELETE FROM feed_filters WHERE id = :id")
    suspend fun deleteFilterById(id: String)

    @Query("DELETE FROM feed_filters")
    suspend fun deleteAll()

    @Query("UPDATE feed_filters SET isActive = :active, updatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun setFilterActive(id: String, active: Boolean, updatedAtMillis: Long)
}
