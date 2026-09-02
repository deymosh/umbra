package com.umbra.app.ui.profile

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.logging.NoOpUmbraLogger
import com.umbra.app.domain.media.MediaDataSourceProvider
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.broadcast.BroadcastEvent
import com.umbra.app.domain.repository.BroadcastRepository
import com.umbra.app.domain.repository.ReactionEmojiRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.testutil.fakes.FakeContactListRepository
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeMuteListRepository
import com.umbra.app.testutil.fakes.FakePinListRepository
import com.umbra.app.testutil.fakes.FakeUserPreferences
import com.umbra.app.testutil.fakes.FakeUserRepository
import com.umbra.app.domain.usecase.BackfillProfileUseCase
import com.umbra.app.domain.usecase.StopProfileBackfillUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationFiltersUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import com.umbra.app.domain.usecase.DeleteNoteUseCase
import com.umbra.app.domain.usecase.RemoveDeletedNoteFromCacheUseCase
import com.umbra.app.domain.usecase.BuildHydrationAuthorSetUseCase
import com.umbra.app.domain.usecase.BuildEngagementFiltersUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.domain.usecase.DetermineMissingHydrationKindsUseCase
import com.umbra.app.domain.usecase.ResolveProfileRelayHintsUseCase
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.BuildEventShareUrlUseCase
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Ignore
import org.junit.Test

class ProfileNip05VerificationStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Ignore("NIP-05 verification moved to UserRepository auto-trigger")
    @Test
    fun `given_cachedProfileWithNip05_when_verifying_then_pendingThenVerified`() = runTest {
        val pubkey = "a".repeat(64)
        val deferredResult = CompletableDeferred<Result<Nip05VerificationState>>()
        val nip05Repo = FakeNip05Repository(nextResult = deferredResult)
        val userRepo = FakeUserRepository(
            cachedProfile = UserProfile(pubkey = pubkey, nip05 = "alice@example.com")
        )

        val viewModel = createViewModel(pubkey, userRepo, nip05Repo)

        waitUntil { viewModel.state.value.profile?.nip05VerificationState == Nip05VerificationState.Pending }
        deferredResult.complete(Result.success(Nip05VerificationState.Verified))
        waitUntil { viewModel.state.value.profile?.nip05VerificationState == Nip05VerificationState.Verified }

        assertEquals(1, nip05Repo.callCount)
        assertEquals("alice@example.com", nip05Repo.lastNip05)
        assertEquals(pubkey, nip05Repo.lastPubkey)
    }

    @Ignore("NIP-05 verification moved to UserRepository auto-trigger")
    @Test
    fun `given_profileWithNip05_when_verificationFails_then_resultIsFailed`() = runTest {
        val pubkey = "b".repeat(64)
        val nip05Repo = FakeNip05Repository(immediateResult = Result.success(Nip05VerificationState.Failed))
        val userRepo = FakeUserRepository(
            cachedProfile = UserProfile(pubkey = pubkey, nip05 = "bob@example.com")
        )

        val viewModel = createViewModel(pubkey, userRepo, nip05Repo)

        waitUntil { viewModel.state.value.profile?.nip05VerificationState == Nip05VerificationState.Failed }

        assertEquals(1, nip05Repo.callCount)
    }

    @Ignore("NIP-05 verification moved to UserRepository auto-trigger")
    @Test
    fun `given_profileWithoutNip05_when_checking_then_notAvailable`() = runTest {
        val pubkey = "c".repeat(64)
        val nip05Repo = FakeNip05Repository(immediateResult = Result.success(Nip05VerificationState.Verified))
        val userRepo = FakeUserRepository(
            cachedProfile = UserProfile(pubkey = pubkey, nip05 = null)
        )

        val viewModel = createViewModel(pubkey, userRepo, nip05Repo)

        waitUntil { viewModel.state.value.profile != null }
        assertEquals(Nip05VerificationState.NotAvailable, viewModel.state.value.profile?.nip05VerificationState)

        assertEquals(0, nip05Repo.callCount)
    }

    @Ignore("NIP-05 verification moved to UserRepository auto-trigger")
    @Test
    fun `given_repeatedProfileUpdates_when_verifying_then_skipsDuplicates`() = runTest {
        val pubkey = "d".repeat(64)
        val nip05Repo = FakeNip05Repository(immediateResult = Result.success(Nip05VerificationState.Verified))
        val userRepo = FakeUserRepository(cachedProfile = null)
        val viewModel = createViewModel(pubkey, userRepo, nip05Repo)
        val profile = UserProfile(pubkey = pubkey, nip05 = "same@example.com")

        userRepo.emitProfile(profile)
        waitUntil { viewModel.state.value.profile?.nip05VerificationState == Nip05VerificationState.Verified }
        userRepo.emitProfile(profile)
        delay(80)

        assertEquals(1, nip05Repo.callCount)
    }

    private fun createViewModel(
        pubkey: String,
        userRepository: FakeUserRepository,
        nip05Repository: FakeNip05Repository
    ): ProfileViewModel {
        val eventRepository = FakeEventRepository()
        val relayRepository = FakeRelayRepository()
        val contactListRepository = FakeContactListRepository()
        val muteListRepository = FakeMuteListRepository()
        val hydrationRequestsUseCase = BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase())
        val missingHydrationKindsUseCase = DetermineMissingHydrationKindsUseCase(
            userRepository,
            contactListRepository,
            muteListRepository
        )
        val resolveProfileRelayHintsUseCase = ResolveProfileRelayHintsUseCase(userRepository, eventRepository)
        return ProfileViewModel(
            savedStateHandle = SavedStateHandle(mapOf("pubkey" to pubkey)),
            eventRepository = eventRepository,
            userRepository = userRepository,
            reactionEmojiRepository = FakeReactionEmojiRepository(),
            contactListRepository = contactListRepository,
            muteListRepository = muteListRepository,
            pinListRepository = FakePinListRepository(),
            feedRepository = FakeFeedRepository(),
            relayRepository = relayRepository,
            userPreferences = FakeUserPreferences(pubkey),
            amberSignerGateway = FakeAmberSignerGateway(),
            publishSignedEventUseCase = PublishSignedEventUseCase(eventRepository, FakeBroadcastRepository(), NoOpUmbraLogger),
            mediaDataSourceProvider = FakeMediaDataSourceProvider(),
            videoCacheDataSourceProvider = FakeVideoCacheDataSourceProvider(),
            deleteNoteUseCase = DeleteNoteUseCase(),
            removeDeletedNoteFromCacheUseCase = RemoveDeletedNoteFromCacheUseCase(eventRepository),
            backfillProfileUseCase = BackfillProfileUseCase(
                eventRepository,
                userRepository,
                hydrationRequestsUseCase,
                missingHydrationKindsUseCase,
                resolveProfileRelayHintsUseCase
            ),
            stopProfileBackfillUseCase = StopProfileBackfillUseCase(eventRepository),
            resolveProfileRelayHintsUseCase = resolveProfileRelayHintsUseCase,
            buildProfileHydrationRequestsUseCase = hydrationRequestsUseCase,
            buildHydrationAuthorSetUseCase = BuildHydrationAuthorSetUseCase(),
            buildEngagementFiltersUseCase = BuildEngagementFiltersUseCase(),
            trackReferencedAuthorUseCase = TrackReferencedAuthorUseCase(
                eventRepository,
                userRepository,
                hydrationRequestsUseCase,
                missingHydrationKindsUseCase
            ),
            buildEventShareUrlUseCase = BuildEventShareUrlUseCase()
        )
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 3_000,
        condition: () -> Boolean
    ) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            delay(20)
        }
    }

    private class FakeNip05Repository(
        private val immediateResult: Result<Nip05VerificationState>? = null,
        private val nextResult: CompletableDeferred<Result<Nip05VerificationState>>? = null
    ) : com.umbra.app.domain.repository.Nip05Repository {
        var callCount: Int = 0
        var lastNip05: String? = null
        var lastPubkey: String? = null

        override suspend fun verifyNip05(nip05: String, pubkey: String): Result<Nip05VerificationState> {
            callCount += 1
            lastNip05 = nip05
            lastPubkey = pubkey
            immediateResult?.let { return it }
            return nextResult?.await() ?: Result.success(Nip05VerificationState.Failed)
        }
    }

    private class FakeFeedRepository : FeedRepository {
        override fun getAllFilters(): Flow<List<FeedFilter>> = flowOf(emptyList())
        override fun getActiveFilters(): Flow<List<FeedFilter>> = flowOf(emptyList())
        override suspend fun getFilterById(id: String): FeedFilter? = null
        override suspend fun addFilter(filter: FeedFilter) = Unit
        override suspend fun updateFilter(filter: FeedFilter) = Unit
        override suspend fun removeFilter(id: String) = Unit
        override suspend fun setFilterActive(id: String, active: Boolean) = Unit
        override suspend fun addMutedAuthor(filterId: String, pubkey: String) = Unit
        override suspend fun removeMutedAuthor(filterId: String, pubkey: String) = Unit
        override suspend fun updateMutedAuthors(filterId: String, mutedPubkeys: Set<String>) = Unit
        override suspend fun resetToDefaults() = Unit
        override suspend fun ensureDefaultFiltersSeeded() = Unit
    }

    private class FakeReactionEmojiRepository : ReactionEmojiRepository {
        override fun observeAll(): Flow<List<ReactionEmoji>> = flowOf(emptyList())
        override suspend fun add(emoji: ReactionEmoji) = Unit
        override suspend fun remove(key: String) = Unit
    }

    private class FakeRelayRepository : RelayRepository {
        override fun getAllRelays(): Flow<List<Relay>> = flowOf(emptyList())
        override suspend fun getRelayById(id: String): Relay? = null
        override suspend fun addRelay(relay: Relay) = Unit
        override suspend fun updateRelay(relay: Relay) = Unit
        override suspend fun removeRelay(id: String) = Unit
        override suspend fun bootstrapDefaultsOnFirstLogin() = Unit
        override suspend fun clearUserRelayConfig() = Unit
    }

    private class FakeBroadcastRepository : BroadcastRepository {
        override val activeBroadcasts: StateFlow<List<BroadcastEvent>> = MutableStateFlow(emptyList())
        override fun trackPublish(event: Event, targetRelays: Set<String>) = Unit
        override fun retryFailedRelays(broadcastId: String) = Unit
        override fun dismiss(broadcastId: String) = Unit
    }

    private class FakeAmberSignerGateway : AmberSignerGateway {
        override fun isAmberInstalled(): Boolean = true
        override fun createLoginIntent(): Intent = Intent("umbra.test.login")
        override fun createSignEventIntent(eventJson: String, currentUserHex: String?): Intent = Intent("umbra.test.sign")
        override fun createStoreIntent(): Intent = Intent("umbra.test.store")
        override fun extractPublicKeyFromResult(data: Intent?): String? = null
        override fun extractSignedEventFromResult(data: Intent?): String? = null
        override suspend fun trySignEventInBackground(eventJson: String, currentUserHex: String?): String? = null
        override suspend fun signEvent(eventJson: String, currentUserHex: String?): String? = null
        override suspend fun requestPublicKey(): String? = null
        override fun openStore(): Boolean = false
    }

    private class FakeMediaDataSourceProvider : MediaDataSourceProvider {
        override fun getDataSourceFactory(): OkHttpDataSource.Factory {
            throw UnsupportedOperationException("Not needed for this test")
        }
    }

    private class FakeVideoCacheDataSourceProvider : VideoCacheDataSourceProvider {
        override fun getCacheDataSourceFactory(): DataSource.Factory {
            throw UnsupportedOperationException("Not needed for this test")
        }
    }
}

