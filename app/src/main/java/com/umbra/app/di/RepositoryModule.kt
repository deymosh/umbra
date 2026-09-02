package com.umbra.app.di

import com.umbra.app.data.nostr.BackfillAnchorClearer
import com.umbra.app.data.nostr.BackfillAnchorStore
import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.data.preferences.AppearancePreferencesImpl
import com.umbra.app.data.preferences.DeveloperPreferencesImpl
import com.umbra.app.data.preferences.SyncPreferencesImpl
import com.umbra.app.data.preferences.UserPreferencesImpl
import com.umbra.app.data.nostr.UmbraNostrClient
import com.umbra.app.data.repository.BroadcastRepositoryImpl
import com.umbra.app.data.repository.DbInspectorRepositoryImpl
import com.umbra.app.data.repository.ReactionEmojiRepositoryImpl
import com.umbra.app.data.repository.EventRepositoryImpl
import com.umbra.app.data.repository.FeedRepositoryImpl
import com.umbra.app.data.repository.ContactListRepositoryImpl
import com.umbra.app.data.repository.MediaUploadRepositoryImpl
import com.umbra.app.data.repository.MuteListRepositoryImpl
import com.umbra.app.data.repository.PinListRepositoryImpl
import com.umbra.app.data.repository.RelayInfoRepositoryImpl
import com.umbra.app.data.repository.RelayRepositoryImpl
import com.umbra.app.data.repository.ResourceUsageRepositoryImpl
import com.umbra.app.data.repository.TorStatusRepositoryImpl
import com.umbra.app.data.repository.UserRepositoryImpl
import com.umbra.app.data.repository.Nip05RepositoryImpl
import com.umbra.app.domain.preferences.AppearancePreferences
import com.umbra.app.domain.preferences.DeveloperPreferences
import com.umbra.app.domain.preferences.SyncPreferences
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.BroadcastRepository
import com.umbra.app.domain.repository.DbInspectorRepository
import com.umbra.app.domain.repository.ReactionEmojiRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.MediaUploadRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.RelayInfoRepository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.ResourceUsageRepository
import com.umbra.app.domain.repository.TorStatusRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.repository.Nip05Repository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module
 * Provides singleton instances of repositories across the application
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Singleton
    @Binds
    abstract fun bindRelayRepository(impl: RelayRepositoryImpl): RelayRepository

    @Singleton
    @Binds
    abstract fun bindFeedRepository(impl: FeedRepositoryImpl): FeedRepository

    @Singleton
    @Binds
    abstract fun bindReactionEmojiRepository(impl: ReactionEmojiRepositoryImpl): ReactionEmojiRepository

    @Singleton
    @Binds
    abstract fun bindContactListRepository(impl: ContactListRepositoryImpl): ContactListRepository

    @Singleton
    @Binds
    abstract fun bindMuteListRepository(impl: MuteListRepositoryImpl): MuteListRepository

    @Singleton
    @Binds
    abstract fun bindMediaUploadRepository(impl: MediaUploadRepositoryImpl): MediaUploadRepository

    @Singleton
    @Binds
    abstract fun bindPinListRepository(impl: PinListRepositoryImpl): PinListRepository

    @Singleton
    @Binds
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Singleton
    @Binds
    abstract fun bindNostrClient(client: UmbraNostrClient): NostrClient

    @Singleton
    @Binds
    abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository

    // EventRepositoryImpl only needs BackfillAnchorStore's clear(pubkey) slice — see
    // BackfillAnchorClearer's doc comment for why this narrow seam exists (JVM-unit-test
    // constructibility, not a DI redesign). BackfillAnchorStore itself is still directly
    // constructor-injected wherever its full get/set API is needed (e.g. NostrSessionManager).
    @Binds
    abstract fun bindBackfillAnchorClearer(impl: BackfillAnchorStore): BackfillAnchorClearer

    @Singleton
    @Binds
    abstract fun bindRelayInfoRepository(impl: RelayInfoRepositoryImpl): RelayInfoRepository

    @Singleton
    @Binds
    abstract fun bindTorStatusRepository(impl: TorStatusRepositoryImpl): TorStatusRepository

    @Singleton
    @Binds
    abstract fun bindNip05Repository(impl: Nip05RepositoryImpl): Nip05Repository

    @Singleton
    @Binds
    abstract fun bindUserPreferences(impl: UserPreferencesImpl): UserPreferences

    @Singleton
    @Binds
    abstract fun bindDeveloperPreferences(impl: DeveloperPreferencesImpl): DeveloperPreferences

    @Singleton
    @Binds
    abstract fun bindSyncPreferences(impl: SyncPreferencesImpl): SyncPreferences

    @Singleton
    @Binds
    abstract fun bindAppearancePreferences(impl: AppearancePreferencesImpl): AppearancePreferences

    @Singleton
    @Binds
    abstract fun bindResourceUsageRepository(impl: ResourceUsageRepositoryImpl): ResourceUsageRepository

    @Singleton
    @Binds
    abstract fun bindBroadcastRepository(impl: BroadcastRepositoryImpl): BroadcastRepository

    @Singleton
    @Binds
    abstract fun bindDbInspectorRepository(impl: DbInspectorRepositoryImpl): DbInspectorRepository
}
