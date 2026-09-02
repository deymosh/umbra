package com.umbra.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.umbra.app.data.db.dao.EventDao
import com.umbra.app.data.db.dao.EventTagDao
import com.umbra.app.data.db.dao.FeedFilterDao
import com.umbra.app.data.db.dao.ReactionEmojiDao
import com.umbra.app.data.db.dao.RelayDao
import com.umbra.app.data.db.dao.UserProfileDao
import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.data.db.entities.EventTagEntity
import com.umbra.app.data.db.entities.FeedFilterEntity
import com.umbra.app.data.db.entities.ReactionEmojiEntity
import com.umbra.app.data.db.entities.RelayEntity
import com.umbra.app.data.db.entities.UserProfileEntity

@Database(
    entities = [
        EventEntity::class,
        UserProfileEntity::class,
        EventTagEntity::class,
        RelayEntity::class,
        FeedFilterEntity::class,
        ReactionEmojiEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class EncryptedUmbraDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "umbra_secure.db"
    }

    abstract fun eventDao(): EventDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun eventTagDao(): EventTagDao
    abstract fun relayDao(): RelayDao
    abstract fun feedFilterDao(): FeedFilterDao
    abstract fun reactionEmojiDao(): ReactionEmojiDao
}