@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.umbra.app.ui.profile

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.model.FeedNotesResult
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip02.ContactList
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.nip51.IndexRelaysList
import com.umbra.app.domain.nip51.MuteList
import com.umbra.app.domain.nip51.PinList
import com.umbra.app.domain.nip51.SearchRelaysList
import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.domain.nipb7.UserServerList
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.BuildHydrationAuthorSetUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationFiltersUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [ProfileObserversCoordinator]'s extraction from `ProfileViewModel` —
 * mirrors [com.umbra.app.data.repository.EventIngestCacheTest]'s structure: a
 * [subject] factory building the coordinator with permissive fake defaults, nested file-private
 * `FakeXxx` test doubles implementing only the methods this coordinator actually calls
 * (`NotImplementedError()` for the rest), plain JUnit assertions, and `runTest`'s virtual time for
 * the follows-hydration debounce window instead of a real `delay()`.
 */
class ProfileObserversCoordinatorTest {

    private val pubkey = "a".repeat(64)

    private fun subject(
        scope: CoroutineScope,
        eventRepository: EventRepository = FakeEventRepository(),
        userRepository: UserRepository = FakeUserRepository(),
        contactListRepository: ContactListRepository = FakeContactListRepository(),
        muteListRepository: MuteListRepository = FakeMuteListRepository(),
        pinListRepository: PinListRepository = FakePinListRepository(),
        relayRepository: RelayRepository = FakeRelayRepository(),
        userPreferences: UserPreferences = FakeUserPreferences(),
        state: MutableStateFlow<ProfileState> = MutableStateFlow(ProfileState())
    ): ProfileObserversCoordinator = ProfileObserversCoordinator(
        pubkey = pubkey,
        eventRepository = eventRepository,
        userRepository = userRepository,
        contactListRepository = contactListRepository,
        muteListRepository = muteListRepository,
        pinListRepository = pinListRepository,
        relayRepository = relayRepository,
        userPreferences = userPreferences,
        buildHydrationAuthorSetUseCase = BuildHydrationAuthorSetUseCase(),
        buildProfileHydrationRequestsUseCase = BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase()),
        state = state,
        scope = scope
    )

    private fun event(id: String, createdAt: Long, pubkey: String = "b".repeat(64)): Event = Event(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = "text",
        sig = "c".repeat(128)
    )

    /** A file-private fake of [UserPreferences] — a plain [MutableStateFlow]-backed session, no
     * signing/Amber behavior needed by this coordinator's tests. */
    private class FakeUserPreferences(publicKey: String? = "d".repeat(64)) : UserPreferences {
        private val publicKeyFlow = MutableStateFlow(publicKey)
        override fun savePublicKey(pubkey: String) { publicKeyFlow.value = pubkey }
        override fun getPublicKey(): String? = publicKeyFlow.value
        override fun isLoggedIn(): Boolean = publicKeyFlow.value != null
        override fun isAnonymousSession(): Boolean = false
        override fun canSignWithAmber(): Boolean = false
        override fun logout() { publicKeyFlow.value = null }
        override fun clearAll() { publicKeyFlow.value = null }
        override fun getPublicKeyFlow(): StateFlow<String?> = publicKeyFlow
    }

    /** A file-private fake of [ContactListRepository] — none of this coordinator's 5 tests drive
     * [ProfileObserversCoordinator.observeFollowsForViewedProfile], so every member throws. */
    private class FakeContactListRepository : ContactListRepository {
        override fun getContactList(pubkey: String): Flow<ContactList?> = throw NotImplementedError()
        override suspend fun follow(pubkey: String): Result<Unit> = throw NotImplementedError()
        override suspend fun unfollow(pubkey: String): Result<Unit> = throw NotImplementedError()
        override suspend fun getCurrentFollowedPubkeys(): Set<String> = throw NotImplementedError()
        override fun clearAll(): Unit = throw NotImplementedError()
    }

    /** A file-private fake of [MuteListRepository] serving [muteListFlow] from
     * [getMuteList] — the only member Test 1 exercises. */
    private class FakeMuteListRepository(
        private val muteListFlow: Flow<MuteList?> = flowOf(null)
    ) : MuteListRepository {
        override fun getMuteList(pubkey: String): Flow<MuteList?> = muteListFlow
        override suspend fun mute(pubkey: String): Result<Unit> = throw NotImplementedError()
        override suspend fun unmute(pubkey: String): Result<Unit> = throw NotImplementedError()
        override suspend fun getCurrentMutedPubkeys(): Set<String> = throw NotImplementedError()
        override fun clearAll(): Unit = throw NotImplementedError()
    }

    /** A file-private fake of [PinListRepository] serving [pinListFlow] from
     * [getPinList] — the only member Test 2 exercises. */
    private class FakePinListRepository(
        private val pinListFlow: Flow<PinList?> = flowOf(null)
    ) : PinListRepository {
        override fun getPinList(pubkey: String): Flow<PinList?> = pinListFlow
        override suspend fun pin(eventId: String): Result<Unit> = throw NotImplementedError()
        override suspend fun unpin(eventId: String): Result<Unit> = throw NotImplementedError()
        override suspend fun isPinned(eventId: String): Boolean = throw NotImplementedError()
        override suspend fun getCurrentPinnedEventIds(): Set<String> = throw NotImplementedError()
        override fun clearAll(): Unit = throw NotImplementedError()
    }

    /** A file-private fake of [RelayRepository] serving [relaysFlow] from
     * [getAllRelays] — the only member Test 3 exercises. */
    private class FakeRelayRepository(
        private val relaysFlow: Flow<List<Relay>> = flowOf(emptyList())
    ) : RelayRepository {
        override fun getAllRelays(): Flow<List<Relay>> = relaysFlow
        override suspend fun getRelayById(id: String): Relay? = throw NotImplementedError()
        override suspend fun addRelay(relay: Relay): Unit = throw NotImplementedError()
        override suspend fun updateRelay(relay: Relay): Unit = throw NotImplementedError()
        override suspend fun removeRelay(id: String): Unit = throw NotImplementedError()
        override suspend fun bootstrapDefaultsOnFirstLogin(): Unit = throw NotImplementedError()
        override suspend fun clearUserRelayConfig(): Unit = throw NotImplementedError()
    }

    /** A file-private fake of [UserRepository] — none of this coordinator's 5 tests exercise it
     * (observeFollowsForViewedProfile/observeTargetUserRelayLists aren't under test here), so every
     * member throws except the mandatory [profileFlow] val, which must be constructible. */
    private class FakeUserRepository : UserRepository {
        override suspend fun getProfile(pubkey: String): UserProfile? = throw NotImplementedError()
        override suspend fun getProfiles(pubkeys: List<String>): List<UserProfile> = throw NotImplementedError()
        override fun isSignedInUser(pubkey: String): Boolean = throw NotImplementedError()
        override suspend fun searchLocalProfiles(query: String, limit: Int): List<UserProfile> = throw NotImplementedError()
        override fun saveProfile(profile: UserProfile): Unit = throw NotImplementedError()
        override fun observeProfile(pubkey: String): Flow<UserProfile?> = throw NotImplementedError()
        override fun getRelayList(pubkey: String): RelayListMetadata? = throw NotImplementedError()
        override fun saveRelayList(relayList: RelayListMetadata): Unit = throw NotImplementedError()
        override fun discoverRelayHints(relayUrls: List<String>): Unit = throw NotImplementedError()
        override fun getDmRelayList(pubkey: String): DmRelayList? = throw NotImplementedError()
        override fun saveDmRelayList(dmRelayList: DmRelayList): Unit = throw NotImplementedError()
        override fun getServerList(pubkey: String): UserServerList? = throw NotImplementedError()
        override fun saveServerList(serverList: UserServerList): Unit = throw NotImplementedError()
        override fun saveSearchRelaysList(list: SearchRelaysList): Unit = throw NotImplementedError()
        override fun saveIndexRelaysList(list: IndexRelaysList): Unit = throw NotImplementedError()
        override fun observeSearchRelaysList(pubkey: String): Flow<SearchRelaysList?> = throw NotImplementedError()
        override fun observeIndexRelaysList(pubkey: String): Flow<IndexRelaysList?> = throw NotImplementedError()
        override suspend fun applyDecryptedSearchRelays(pubkey: String, relayUrls: Set<String>): Unit = throw NotImplementedError()
        override suspend fun applyDecryptedIndexRelays(pubkey: String, relayUrls: Set<String>): Unit = throw NotImplementedError()
        override fun wasRelayListContentApplied(encryptedContent: String): Boolean = throw NotImplementedError()
        override fun markRelayListContentApplied(encryptedContent: String): Unit = throw NotImplementedError()
        override fun clearAll(): Unit = throw NotImplementedError()
        override val profileFlow: SharedFlow<UserProfile> = MutableSharedFlow()
        override suspend fun isProfileFresh(pubkey: String): Boolean = throw NotImplementedError()
    }

    /** A file-private fake of [EventRepository] recording every [subscribeChannel]/[clearChannel]/
     * [requestCount] call for exact-count assertions, and serving [getEventsByIds]/
     * [observeCountEventsByPubkeyAndKind]/[observeConnectedRelayUrls]/[observeRelayCounts] from
     * settable in-memory state — same rationale as
     * [com.umbra.app.data.repository.EventIngestCacheTest]'s FakeOwnEventArchive: a plain JVM unit
     * test never needs a real relay/Room round trip. */
    private class FakeEventRepository(
        private val localNotesCountFlow: Flow<Int> = flowOf(0),
        private val eventsById: Map<String, Event> = emptyMap(),
        private val connectedRelayUrlsFlow: Flow<Set<String>> = flowOf(emptySet())
    ) : EventRepository {
        val relayCounts = MutableSharedFlow<RelayCountResult>(extraBufferCapacity = 10)
        val subscribeChannelCalls = mutableListOf<Pair<String, List<EventFilter>>>()
        val clearChannelCalls = mutableListOf<String>()
        val requestCountCalls = mutableListOf<Triple<String, String, List<EventFilter>>>()

        override fun pinProfileAuthorForPersistence(pubkey: String): Unit = throw NotImplementedError()
        override fun unpinProfileAuthorForPersistence(pubkey: String): Unit = throw NotImplementedError()
        override suspend fun clearAllData(): Unit = throw NotImplementedError()
        override fun clearBackfillAnchors(pubkey: String): Unit = throw NotImplementedError()
        override fun activateUserSession(pubkey: String?, feedFilter: FeedFilter, authors: Set<String>): Unit =
            throw NotImplementedError()
        override fun setSubscriptionNamespace(namespace: String): Unit = throw NotImplementedError()
        override fun subscribeToEvents(filters: List<EventFilter>): Flow<Event> = throw NotImplementedError()

        override fun subscribeChannel(channelId: String, filters: List<EventFilter>) {
            subscribeChannelCalls += channelId to filters
        }

        override fun setChannelOverlay(channelId: String, overlayFilters: List<EventFilter>): Unit =
            throw NotImplementedError()

        override fun clearChannel(channelId: String) {
            clearChannelCalls += channelId
        }

        override fun reapplyChannelsToRelay(relayUrl: String): Unit = throw NotImplementedError()

        // No-op rather than throw: enqueueFollowProfileHydration's followsHydrationCloseJob launches
        // this unconditionally right after subscribeChannel, in both Test 4 and Test 5.
        override suspend fun awaitChannelEoseOrTimeout(channelId: String, timeoutMs: Long) = Unit

        override fun loadOlderEvents(channelId: String, untilTimestamp: Long, windowSeconds: Long, limit: Int): Unit =
            throw NotImplementedError()
        override fun resyncRecentHistory(channelId: String, sinceTimestamp: Long, untilTimestamp: Long, limit: Int): Unit =
            throw NotImplementedError()
        override suspend fun getOldestAuthorNoteTimestamp(pubkey: String): Long? = throw NotImplementedError()
        override suspend fun getOldestInboxNoteTimestamp(pubkey: String): Long? = throw NotImplementedError()
        override suspend fun getOldestInboxReactionTimestamp(pubkey: String): Long? = throw NotImplementedError()
        override fun getCachedEvents(): Flow<List<Event>> = throw NotImplementedError()
        override fun observeRecentEvents(limit: Int): Flow<List<Event>> = throw NotImplementedError()
        override suspend fun getEventById(id: String): Event? = throw NotImplementedError()
        override suspend fun getEventRelays(eventId: String): Set<String> = throw NotImplementedError()
        override suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): Event? =
            throw NotImplementedError()

        override suspend fun getEventsByIds(ids: List<String>): List<Event> = ids.mapNotNull { eventsById[it] }

        override suspend fun getEventsReferencingIds(targetIds: List<String>): List<Event> = throw NotImplementedError()
        override fun observeEventsByPubkeyAndKind(pubkey: String, kind: Int, limit: Int): Flow<List<Event>> =
            throw NotImplementedError()

        override fun observeCountEventsByPubkeyAndKind(pubkey: String, kind: Int): Flow<Int> = localNotesCountFlow

        override suspend fun clearCache(): Unit = throw NotImplementedError()
        override suspend fun deleteEvent(eventId: String): Unit = throw NotImplementedError()
        override suspend fun connectToEnabledRelays(relays: List<Relay>): Result<Unit> = throw NotImplementedError()
        override fun disconnectFromAll(): Unit = throw NotImplementedError()
        override suspend fun publishEvent(event: Event): Result<Set<String>> = throw NotImplementedError()
        override suspend fun publishAuthEvent(relayUrl: String, event: Event): Result<Unit> = throw NotImplementedError()
        override fun observeRelayRequests(): Flow<List<RelayRequestInfo>> = throw NotImplementedError()
        override fun observeRelayIssues(): Flow<RelayIssue> = throw NotImplementedError()

        override fun observeConnectedRelayUrls(): Flow<Set<String>> = connectedRelayUrlsFlow

        override fun resetRelayFailureCount(relayUrl: String): Unit = throw NotImplementedError()
        override fun disconnectRelay(relayUrl: String): Unit = throw NotImplementedError()
        override fun resetAllRelayBackoff(): Unit = throw NotImplementedError()

        override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {
            requestCountCalls += Triple(relayUrl, subscriptionId, filters)
        }

        override fun observeRelayCounts(): Flow<RelayCountResult> = relayCounts

        override fun observeFeedNotes(
            since: Long,
            limit: Int,
            authors: Set<String>,
            mutedPubkeys: Set<String>,
            excludedHashtagsLower: Set<String>,
            includeMentions: Boolean,
            hideNsfw: Boolean,
            currentNpub: String?,
            currentUserPubkey: String?,
            desiredTagsLower: Set<String>
        ): Flow<FeedNotesResult> = throw NotImplementedError()

        override fun observeProfileNotes(pubkey: String, kind: Int, limit: Int): Flow<FeedNotesResult> =
            throw NotImplementedError()
        override suspend fun searchNotes(query: String): Flow<List<Event>> = throw NotImplementedError()
    }

    // --- Test 1: observeMutedAuthorsForCurrentSession ---

    @Test
    fun `given a mute list update when observeMutedAuthorsForCurrentSession collects then sorted mutedPubkeys is written into shared state`() = runTest {
        val mutedA = "b".repeat(64)
        val mutedB = "c".repeat(64)
        val muteListRepository = FakeMuteListRepository(
            muteListFlow = flowOf(MuteList(ownerPubkey = pubkey, mutedPubkeys = setOf(mutedB, mutedA), updatedAt = 1L))
        )
        val state = MutableStateFlow(ProfileState())
        val coordinator = subject(this, muteListRepository = muteListRepository, state = state)

        coordinator.observeMutedAuthorsForCurrentSession()
        advanceUntilIdle()

        assertEquals(listOf(mutedA, mutedB).sorted(), state.value.mutedPubkeys)

        // observeMutedAuthorsForCurrentSession's collector never completes on its own (a standing
        // observer over userPreferences.getPublicKeyFlow(), a StateFlow) — cancel it explicitly so
        // it doesn't leave a running child job at the end of runTest (UncompletedCoroutinesError).
        coroutineContext.cancelChildren()
    }

    // --- Test 2: observePinnedNotesForCurrentSession ---

    @Test
    fun `given pinned event ids when observePinnedNotesForCurrentSession collects then resolved events are sorted by createdAt descending`() = runTest {
        val older = event(id = "older", createdAt = 100L)
        val newer = event(id = "newer", createdAt = 200L)
        val pinListRepository = FakePinListRepository(
            pinListFlow = flowOf(PinList(ownerPubkey = pubkey, pinnedEventIds = setOf(older.id, newer.id), updatedAt = 1L))
        )
        val eventRepository = FakeEventRepository(eventsById = mapOf(older.id to older, newer.id to newer))
        val state = MutableStateFlow(ProfileState())
        val coordinator = subject(this, eventRepository = eventRepository, pinListRepository = pinListRepository, state = state)

        coordinator.observePinnedNotesForCurrentSession()
        advanceUntilIdle()

        assertEquals(listOf(newer, older), state.value.pinnedNotes)

        // Same rationale as Test 1 — observePinnedNotesForCurrentSession's collector never
        // completes on its own.
        coroutineContext.cancelChildren()
    }

    // --- Test 3: recomputeTotalNotesCount (via observeLocalNotesCount + the NIP-45 note-count observers) ---

    @Test
    fun `given a local count of 5 and a remote relay count of 12 when both note-count observers run then totalNotesCount reflects the higher remote value`() = runTest {
        val relayUrl = "wss://relay-a.example"
        val eventRepository = FakeEventRepository(
            localNotesCountFlow = flowOf(5),
            connectedRelayUrlsFlow = flowOf(setOf(relayUrl))
        )
        val relayRepository = FakeRelayRepository(
            relaysFlow = flowOf(listOf(Relay(id = "r1", url = relayUrl, relayInfo = RelayInfo(supportedNips = listOf(45)))))
        )
        val state = MutableStateFlow(ProfileState())
        val coordinator = subject(this, eventRepository = eventRepository, relayRepository = relayRepository, state = state)

        coordinator.observeLocalNotesCount()
        coordinator.observeNip45NoteCounts()
        coordinator.requestNip45NoteCountsOnRelayChanges()
        advanceUntilIdle()

        val subscriptionId = eventRepository.requestCountCalls.single().second
        eventRepository.relayCounts.emit(RelayCountResult(relayUrl = relayUrl, subscriptionId = subscriptionId, count = 12L))
        advanceUntilIdle()

        assertEquals(12, state.value.totalNotesCount)

        // observeNip45NoteCounts' collector (over a MutableSharedFlow) never completes on its own.
        coroutineContext.cancelChildren()
    }

    @Test
    fun `given a local count of 20 with remote relay counts all below it when both note-count observers run then totalNotesCount stays at the local value`() = runTest {
        val relayUrl = "wss://relay-a.example"
        val eventRepository = FakeEventRepository(
            localNotesCountFlow = flowOf(20),
            connectedRelayUrlsFlow = flowOf(setOf(relayUrl))
        )
        val relayRepository = FakeRelayRepository(
            relaysFlow = flowOf(listOf(Relay(id = "r1", url = relayUrl, relayInfo = RelayInfo(supportedNips = listOf(45)))))
        )
        val state = MutableStateFlow(ProfileState())
        val coordinator = subject(this, eventRepository = eventRepository, relayRepository = relayRepository, state = state)

        coordinator.observeLocalNotesCount()
        coordinator.observeNip45NoteCounts()
        coordinator.requestNip45NoteCountsOnRelayChanges()
        advanceUntilIdle()

        val subscriptionId = eventRepository.requestCountCalls.single().second
        eventRepository.relayCounts.emit(RelayCountResult(relayUrl = relayUrl, subscriptionId = subscriptionId, count = 5L))
        advanceUntilIdle()

        assertEquals(20, state.value.totalNotesCount)

        // observeNip45NoteCounts' collector (over a MutableSharedFlow) never completes on its own.
        coroutineContext.cancelChildren()
    }

    // --- Test 4: enqueueFollowProfileHydration batches within the debounce window ---

    @Test
    fun `given two enqueueFollowProfileHydration calls within the debounce window when time advances then exactly one subscribeChannel call is made`() = runTest {
        val eventRepository = FakeEventRepository()
        val coordinator = subject(this, eventRepository = eventRepository)

        coordinator.enqueueFollowProfileHydration(listOf("b".repeat(64)))
        advanceTimeBy(100L)
        coordinator.enqueueFollowProfileHydration(listOf("c".repeat(64)))
        advanceTimeBy(600L)
        advanceUntilIdle()

        assertEquals(1, eventRepository.subscribeChannelCalls.size)
    }

    // --- Test 5: cancelScheduledWork() cancels the in-flight follows-hydration job ---

    @Test
    fun `given a follows-hydration job in flight when cancelScheduledWork runs then the job is no longer active`() = runTest {
        val eventRepository = FakeEventRepository()
        val coordinator = subject(this, eventRepository = eventRepository)

        coordinator.enqueueFollowProfileHydration(listOf("b".repeat(64)))
        assertTrue(coordinator.followsHydrationJob?.isActive == true)

        coordinator.cancelScheduledWork()

        assertFalse(coordinator.followsHydrationJob?.isActive == true)

        // The pending debounce delay never got to fire subscribeChannel — proves the job was
        // genuinely cancelled, not just marked inactive after already completing its work.
        advanceUntilIdle()
        assertEquals(0, eventRepository.subscribeChannelCalls.size)
    }
}
