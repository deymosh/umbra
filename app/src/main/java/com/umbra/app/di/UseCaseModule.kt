package com.umbra.app.di

import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nostr.NostrSessionController
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.BroadcastRepository
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.MediaUploadRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.ResourceUsageRepository
import com.umbra.app.domain.repository.TorStatusRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.AddFeedFilterUseCase
import com.umbra.app.domain.usecase.AddMutedAuthorUseCase
import com.umbra.app.domain.usecase.AddRelayUseCase
import com.umbra.app.domain.usecase.CheckTorStatusUseCase
import com.umbra.app.domain.usecase.DismissBroadcastUseCase
import com.umbra.app.domain.usecase.GetActiveFilterUseCase
import com.umbra.app.domain.usecase.GetAllFiltersUseCase
import com.umbra.app.domain.usecase.GetAllRelaysUseCase
import com.umbra.app.domain.usecase.LogoutUseCase
import com.umbra.app.domain.usecase.ObserveActiveBroadcastsUseCase
import com.umbra.app.domain.usecase.ObserveResourceUsageUseCase
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.PublishAuthEventUseCase
import com.umbra.app.util.logging.UmbraLog
import com.umbra.app.domain.usecase.DeleteNoteUseCase
import com.umbra.app.domain.usecase.RemoveDeletedNoteFromCacheUseCase
import com.umbra.app.domain.usecase.BackfillProfileUseCase
import com.umbra.app.domain.usecase.ResolveProfileRelayHintsUseCase
import com.umbra.app.domain.usecase.BootstrapOwnProfileUseCase
import com.umbra.app.domain.usecase.StopProfileBackfillUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationFiltersUseCase
import com.umbra.app.domain.usecase.BuildHydrationAuthorSetUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import com.umbra.app.domain.usecase.BuildEventShareUrlUseCase
import com.umbra.app.domain.usecase.BuildEngagementFiltersUseCase
import com.umbra.app.domain.usecase.DetermineMissingHydrationKindsUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.domain.usecase.RemoveFeedFilterUseCase
import com.umbra.app.domain.usecase.RemoveMutedAuthorUseCase
import com.umbra.app.domain.usecase.RemoveRelayUseCase
import com.umbra.app.domain.usecase.ResetFeedFiltersUseCase
import com.umbra.app.domain.usecase.RetryBroadcastRelaysUseCase
import com.umbra.app.domain.usecase.SetFilterActiveUseCase
import com.umbra.app.domain.usecase.UpdateFeedFilterUseCase
import com.umbra.app.domain.usecase.TrimMemoryCachesUseCase
import com.umbra.app.domain.usecase.UpdateRelayUseCase
import com.umbra.app.domain.usecase.UploadBlossomBlobUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Provides
    @Singleton
    fun provideCheckTorStatusUseCase(repo: TorStatusRepository): CheckTorStatusUseCase =
        CheckTorStatusUseCase(repo)

    @Provides
    @Singleton
    fun provideObserveResourceUsageUseCase(repo: ResourceUsageRepository): ObserveResourceUsageUseCase =
        ObserveResourceUsageUseCase(repo)

    @Provides
    @Singleton
    fun provideGetAllFiltersUseCase(repo: FeedRepository): GetAllFiltersUseCase =
        GetAllFiltersUseCase(repo)

    @Provides
    @Singleton
    fun provideGetActiveFilterUseCase(repo: FeedRepository): GetActiveFilterUseCase =
        GetActiveFilterUseCase(repo)

    @Provides
    @Singleton
    fun provideSetFilterActiveUseCase(repo: FeedRepository): SetFilterActiveUseCase =
        SetFilterActiveUseCase(repo)

    @Provides
    @Singleton
    fun provideAddFeedFilterUseCase(repo: FeedRepository): AddFeedFilterUseCase =
        AddFeedFilterUseCase(repo)

    @Provides
    @Singleton
    fun provideUpdateFeedFilterUseCase(repo: FeedRepository): UpdateFeedFilterUseCase =
        UpdateFeedFilterUseCase(repo)

    @Provides
    @Singleton
    fun provideRemoveFeedFilterUseCase(repo: FeedRepository): RemoveFeedFilterUseCase =
        RemoveFeedFilterUseCase(repo)

    @Provides
    @Singleton
    fun provideAddMutedAuthorUseCase(repo: FeedRepository): AddMutedAuthorUseCase =
        AddMutedAuthorUseCase(repo)

    @Provides
    @Singleton
    fun provideRemoveMutedAuthorUseCase(repo: FeedRepository): RemoveMutedAuthorUseCase =
        RemoveMutedAuthorUseCase(repo)

    @Provides
    @Singleton
    fun provideResetFeedFiltersUseCase(repo: FeedRepository): ResetFeedFiltersUseCase =
        ResetFeedFiltersUseCase(repo)

    @Provides
    @Singleton
    fun provideGetAllRelaysUseCase(repo: RelayRepository): GetAllRelaysUseCase =
        GetAllRelaysUseCase(repo)

    @Provides
    @Singleton
    fun provideAddRelayUseCase(repo: RelayRepository): AddRelayUseCase =
        AddRelayUseCase(repo)

    @Provides
    @Singleton
    fun provideUpdateRelayUseCase(repo: RelayRepository): UpdateRelayUseCase =
        UpdateRelayUseCase(repo)

    @Provides
    @Singleton
    fun provideRemoveRelayUseCase(repo: RelayRepository): RemoveRelayUseCase =
        RemoveRelayUseCase(repo)

    @Provides
    @Singleton
    fun providePublishSignedEventUseCase(
        repo: EventRepository,
        broadcastRepository: BroadcastRepository
    ): PublishSignedEventUseCase =
        PublishSignedEventUseCase(repo, broadcastRepository, UmbraLog.tag("UmbraPublishUC"))

    @Provides
    @Singleton
    fun provideObserveActiveBroadcastsUseCase(broadcastRepository: BroadcastRepository): ObserveActiveBroadcastsUseCase =
        ObserveActiveBroadcastsUseCase(broadcastRepository)

    @Provides
    @Singleton
    fun provideUploadBlossomBlobUseCase(
        mediaUploadRepository: MediaUploadRepository,
        amberSignerGateway: AmberSignerGateway,
        userPreferences: UserPreferences
    ): UploadBlossomBlobUseCase =
        UploadBlossomBlobUseCase(mediaUploadRepository, amberSignerGateway, userPreferences)

    @Provides
    @Singleton
    fun provideRetryBroadcastRelaysUseCase(broadcastRepository: BroadcastRepository): RetryBroadcastRelaysUseCase =
        RetryBroadcastRelaysUseCase(broadcastRepository)

    @Provides
    @Singleton
    fun provideDismissBroadcastUseCase(broadcastRepository: BroadcastRepository): DismissBroadcastUseCase =
        DismissBroadcastUseCase(broadcastRepository)

    @Provides
    @Singleton
    fun providePublishAuthEventUseCase(repo: EventRepository): PublishAuthEventUseCase =
        PublishAuthEventUseCase(repo, UmbraLog.tag("UmbraPublishUC"))

    @Provides
    @Singleton
    fun provideDeleteNoteUseCase(): DeleteNoteUseCase = DeleteNoteUseCase()

    @Provides
    @Singleton
    fun provideRemoveDeletedNoteFromCacheUseCase(repo: EventRepository): RemoveDeletedNoteFromCacheUseCase =
        RemoveDeletedNoteFromCacheUseCase(repo)

    @Provides
    @Singleton
    fun provideResolveProfileRelayHintsUseCase(
        userRepository: UserRepository,
        eventRepository: EventRepository
    ): ResolveProfileRelayHintsUseCase =
        ResolveProfileRelayHintsUseCase(userRepository, eventRepository)

    @Provides
    @Singleton
    fun provideBackfillProfileUseCase(
        repo: EventRepository,
        userRepository: UserRepository,
        buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase,
        determineMissingHydrationKindsUseCase: DetermineMissingHydrationKindsUseCase,
        resolveProfileRelayHintsUseCase: ResolveProfileRelayHintsUseCase
    ): BackfillProfileUseCase =
        BackfillProfileUseCase(
            repo,
            userRepository,
            buildProfileHydrationRequestsUseCase,
            determineMissingHydrationKindsUseCase,
            resolveProfileRelayHintsUseCase
        )

    @Provides
    @Singleton
    fun provideStopProfileBackfillUseCase(repo: EventRepository): StopProfileBackfillUseCase =
        StopProfileBackfillUseCase(repo)

    @Provides
    @Singleton
    fun provideBuildProfileHydrationFiltersUseCase(): BuildProfileHydrationFiltersUseCase =
        BuildProfileHydrationFiltersUseCase()

    @Provides
    @Singleton
    fun provideBuildProfileHydrationRequestsUseCase(
        buildProfileHydrationFiltersUseCase: BuildProfileHydrationFiltersUseCase
    ): BuildProfileHydrationRequestsUseCase =
        BuildProfileHydrationRequestsUseCase(buildProfileHydrationFiltersUseCase)

@Provides
    @Singleton
    fun provideBuildHydrationAuthorSetUseCase(): BuildHydrationAuthorSetUseCase =
        BuildHydrationAuthorSetUseCase()

    @Provides
    @Singleton
    fun provideBuildEventShareUrlUseCase(): BuildEventShareUrlUseCase =
        BuildEventShareUrlUseCase()

    @Provides
    @Singleton
    fun provideBuildEngagementFiltersUseCase(): BuildEngagementFiltersUseCase =
        BuildEngagementFiltersUseCase()

    @Provides
    @Singleton
    fun provideDetermineMissingHydrationKindsUseCase(
        userRepository: UserRepository,
        contactListRepository: ContactListRepository,
        muteListRepository: MuteListRepository
    ): DetermineMissingHydrationKindsUseCase =
        DetermineMissingHydrationKindsUseCase(userRepository, contactListRepository, muteListRepository)

    @Provides
    @Singleton
    fun provideTrackReferencedAuthorUseCase(
        eventRepository: EventRepository,
        userRepository: UserRepository,
        buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase,
        determineMissingHydrationKindsUseCase: DetermineMissingHydrationKindsUseCase
    ): TrackReferencedAuthorUseCase =
        TrackReferencedAuthorUseCase(
            eventRepository,
            userRepository,
            buildProfileHydrationRequestsUseCase,
            determineMissingHydrationKindsUseCase
        )

    @Provides
    @Singleton
    fun provideBootstrapOwnProfileUseCase(
        eventRepository: EventRepository,
        buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase
    ): BootstrapOwnProfileUseCase =
        BootstrapOwnProfileUseCase(eventRepository, buildProfileHydrationRequestsUseCase)

    @Provides
    @Singleton
    fun provideLogoutUseCase(
        eventRepository: EventRepository,
        userRepository: UserRepository,
        userPreferences: UserPreferences,
        contactListRepository: ContactListRepository,
        muteListRepository: MuteListRepository,
        pinListRepository: PinListRepository,
        nostrSessionController: NostrSessionController
    ): LogoutUseCase = LogoutUseCase(
        eventRepository,
        userRepository,
        userPreferences,
        contactListRepository,
        muteListRepository,
        pinListRepository,
        nostrSessionController,
        UmbraLog.tag("UmbraLogout")
    )

    @Provides
    @Singleton
    fun provideTrimMemoryCachesUseCase(
        eventRepository: EventRepository,
        userRepository: UserRepository,
        contactListRepository: ContactListRepository,
        muteListRepository: MuteListRepository,
        pinListRepository: PinListRepository
    ): TrimMemoryCachesUseCase = TrimMemoryCachesUseCase(
        eventRepository,
        userRepository,
        contactListRepository,
        muteListRepository,
        pinListRepository,
        UmbraLog.tag("UmbraTrimMemory")
    )
}
