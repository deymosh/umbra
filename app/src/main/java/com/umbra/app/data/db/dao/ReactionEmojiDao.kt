package com.umbra.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.umbra.app.data.db.entities.ReactionEmojiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionEmojiDao {

    @Query("SELECT * FROM reaction_emojis ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<ReactionEmojiEntity>>

    @Query("SELECT COUNT(*) FROM reaction_emojis")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReactionEmojiEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ReactionEmojiEntity>)

    @Query("DELETE FROM reaction_emojis WHERE key = :key")
    suspend fun deleteByKey(key: String)
}
