package com.umbra.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.umbra.app.data.db.EncryptedDatabasePassphraseProvider
import com.umbra.app.data.db.EncryptedUmbraDatabase
import com.umbra.app.data.db.dao.EventDao
import com.umbra.app.data.db.dao.EventTagDao
import com.umbra.app.data.db.dao.ReactionEmojiDao
import com.umbra.app.data.db.dao.UserProfileDao
import com.umbra.app.data.db.dao.RelayDao
import com.umbra.app.data.db.dao.FeedFilterDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import javax.inject.Named
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// Single physical database, SQLCipher-encrypted (see EncryptedDatabasePassphraseProvider — the
// passphrase is a device-local random key in Android Keystore-backed storage, not derived from
// any Nostr credential, so this is available even in anonymous/read-only mode). All local state —
// the signed-in user's own events, plus profile/relay/feed-filter caches for everyone — lives
// here. There is intentionally no second, unencrypted database.
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    @Named("encrypted")
    fun provideEncryptedDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: EncryptedDatabasePassphraseProvider
    ): EncryptedUmbraDatabase {
        // Unlike the old net.sqlcipher:android-database-sqlcipher artifact this project used to
        // depend on, net.zetetic:sqlcipher-android (adopted for 16 KB page-size support) never
        // calls System.loadLibrary itself anywhere in its own classes — the app must load
        // libsqlcipher.so before the first database operation, or SQLiteConnection.nativeOpen
        // throws UnsatisfiedLinkError ("No implementation found for..."). This call was never
        // added when the library migration swapped SupportFactory for SupportOpenHelperFactory.
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, EncryptedUmbraDatabase::class.java, EncryptedUmbraDatabase.DATABASE_NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphraseProvider.getOrCreatePassphrase()))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    @Singleton
    @Named("encrypted")
    fun provideEncryptedEventDao(@Named("encrypted") db: EncryptedUmbraDatabase): EventDao = db.eventDao()

    @Provides
    @Singleton
    @Named("encrypted")
    fun provideEncryptedUserProfileDao(@Named("encrypted") db: EncryptedUmbraDatabase): UserProfileDao = db.userProfileDao()

    @Provides
    @Singleton
    @Named("encrypted")
    fun provideEncryptedEventTagDao(@Named("encrypted") db: EncryptedUmbraDatabase): EventTagDao = db.eventTagDao()

    @Provides
    @Singleton
    @Named("encrypted")
    fun provideEncryptedRelayDao(@Named("encrypted") db: EncryptedUmbraDatabase): RelayDao = db.relayDao()

    @Provides
    @Singleton
    @Named("encrypted")
    fun provideEncryptedFeedFilterDao(@Named("encrypted") db: EncryptedUmbraDatabase): FeedFilterDao = db.feedFilterDao()

    @Provides
    @Singleton
    @Named("encrypted")
    fun provideEncryptedReactionEmojiDao(@Named("encrypted") db: EncryptedUmbraDatabase): ReactionEmojiDao = db.reactionEmojiDao()
}
