package com.umbra.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.umbra.app.data.db.entities.LocalEmojiPackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalEmojiPackDao {

    @Query("SELECT * FROM local_emoji_packs WHERE accountPubkey = :accountPubkey ORDER BY addedAtMillis ASC")
    fun observeForAccount(accountPubkey: String): Flow<List<LocalEmojiPackEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LocalEmojiPackEntity)

    @Query("DELETE FROM local_emoji_packs WHERE accountPubkey = :accountPubkey AND coordinate = :coordinate")
    suspend fun delete(accountPubkey: String, coordinate: String)

    @Query("DELETE FROM local_emoji_packs")
    suspend fun deleteAll()
}
