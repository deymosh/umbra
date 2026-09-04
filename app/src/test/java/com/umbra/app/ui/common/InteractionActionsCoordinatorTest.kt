package com.umbra.app.ui.common

import android.content.Intent
import com.umbra.app.domain.broadcast.BroadcastEvent
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.logging.NoOpUmbraLogger
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.repository.BroadcastRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.usecase.BuildEventShareUrlUseCase
import com.umbra.app.domain.usecase.DeleteNoteUseCase
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.RemoveDeletedNoteFromCacheUseCase
import com.umbra.app.domain.nip51.MuteList
import com.umbra.app.domain.nip51.PinList
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeUserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [InteractionActionsCoordinator]'s extraction of the near-identical
 * like/repost/mute/pin/delete/share/get-JSON plumbing duplicated across FeedViewModel and
 * ProfileViewModel — the highest-risk cluster in this coordinator (both callers commit only after
 * Amber confirms the signature; see `mirrorMuteIntoActiveFilter` for why its filter-resolution
 * strategy is caller-supplied rather than fixed). Structure follows
 * RelayIssueBannerCoordinatorTest/ProfileObserversCoordinatorTest: a `subject()` factory, nested
 * private Fake test doubles implementing only the members this coordinator actually calls
 * (`NotImplementedError` for the rest), plain JUnit assertions, no Mockito.
 */
class InteractionActionsCoordinatorTest {

    private val fakeSignedEventJson =
        """{"id":"evt1","pubkey":"pub1","created_at":1,"kind":1,"tags":[],"content":"","sig":"sig1"}"""

    /**
     * [com.umbra.app.domain.usecase.PublishSignedEventUseCase] and
     * [RemoveDeletedNoteFromCacheUseCase] hop onto real `Dispatchers.Default`/`Dispatchers.IO`
     * (not the test's virtual-time scheduler) — `advanceUntilIdle()` alone cannot fast-forward
     * past that hop, so tests exercising either use case need a real (wall-clock) wait on both
     * dispatchers as well, matching the established pattern for this exact category of problem.
     */
    private suspend fun awaitRealDispatch() {
        withContext(Dispatchers.IO) { delay(300) }
        withContext(Dispatchers.Default) { delay(300) }
    }

    private fun sampleEvent(id: String = "note1", pubkey: String = "author1") = Event(
        id = id,
        pubkey = pubkey,
        createdAt = 1L,
        kind = Event.KIND_TEXT_NOTE,
        content = "hello"
    )

    private fun subject(
        scope: CoroutineScope,
        userPreferences: FakeUserPreferences = FakeUserPreferences(initialPubkey = "a".repeat(64)),
        muteListRepository: RecordingMuteListRepository = RecordingMuteListRepository(),
        pinListRepository: RecordingPinListRepository = RecordingPinListRepository(),
        feedRepository: RecordingFeedRepository = RecordingFeedRepository(),
        amberSignerGateway: FakeAmberSignerGateway = FakeAmberSignerGateway(fakeSignedEventJson),
        broadcastRepository: RecordingBroadcastRepository = RecordingBroadcastRepository(),
        deleteNoteUseCase: DeleteNoteUseCase = DeleteNoteUseCase(),
        eventRepository: FakeEventRepository = FakeEventRepository()
    ): InteractionActionsCoordinator = InteractionActionsCoordinator(
        userPreferences = userPreferences,
        muteListRepository = muteListRepository,
        pinListRepository = pinListRepository,
        feedRepository = feedRepository,
        amberSignerGateway = amberSignerGateway,
        publishSignedEventUseCase = PublishSignedEventUseCase(eventRepository, broadcastRepository, NoOpUmbraLogger),
        deleteNoteUseCase = deleteNoteUseCase,
        removeDeletedNoteFromCacheUseCase = RemoveDeletedNoteFromCacheUseCase(eventRepository),
        buildEventShareUrlUseCase = BuildEventShareUrlUseCase(),
        scope = scope
    )

    /** Only [signEvent] does real work; every other [AmberSignerGateway] member is unreachable
     * from [InteractionActionsCoordinator] and throws if ever called. */
    private class FakeAmberSignerGateway(private val signedEventJson: String?) : AmberSignerGateway {
        val signEventCalls = mutableListOf<Pair<String, String?>>()

        override fun isAmberInstalled(): Boolean = throw NotImplementedError()
        override fun createLoginIntent(): Intent = throw NotImplementedError()
        override fun createSignEventIntent(eventJson: String, currentUserHex: String?): Intent = throw NotImplementedError()
        override fun createStoreIntent(): Intent = throw NotImplementedError()
        override fun extractPublicKeyFromResult(data: Intent?): String? = throw NotImplementedError()
        override fun extractSignedEventFromResult(data: Intent?): String? = throw NotImplementedError()
        override suspend fun trySignEventInBackground(eventJson: String, currentUserHex: String?): String? = throw NotImplementedError()

        override suspend fun signEvent(eventJson: String, currentUserHex: String?): String? {
            signEventCalls += eventJson to currentUserHex
            return signedEventJson
        }

        override suspend fun requestPublicKey(): String? = throw NotImplementedError()
        override fun openStore(): Boolean = throw NotImplementedError()
    }

    private class RecordingBroadcastRepository : BroadcastRepository {
        var trackPublishCalls: Int = 0
        override val activeBroadcasts: StateFlow<List<BroadcastEvent>> = MutableStateFlow(emptyList())
        override fun trackPublish(event: Event, targetRelays: Set<String>) {
            trackPublishCalls += 1
        }
        override fun retryFailedRelays(broadcastId: String): Unit = throw NotImplementedError()
        override fun dismiss(broadcastId: String): Unit = throw NotImplementedError()
    }

    private class RecordingMuteListRepository : MuteListRepository {
        val muteCalls = mutableListOf<String>()
        val unmuteCalls = mutableListOf<String>()
        override fun getMuteList(pubkey: String): Flow<MuteList?> = flowOf(null)
        override suspend fun mute(pubkey: String): Result<Unit> {
            muteCalls += pubkey
            return Result.success(Unit)
        }
        override suspend fun unmute(pubkey: String): Result<Unit> {
            unmuteCalls += pubkey
            return Result.success(Unit)
        }
        override suspend fun getCurrentMutedPubkeys(): Set<String> = emptySet()
        override fun clearAll() = Unit
    }

    private class RecordingPinListRepository : PinListRepository {
        val pinCalls = mutableListOf<String>()
        val unpinCalls = mutableListOf<String>()
        override fun getPinList(pubkey: String): Flow<PinList?> = flowOf(null)
        override suspend fun pin(eventId: String): Result<Unit> {
            pinCalls += eventId
            return Result.success(Unit)
        }
        override suspend fun unpin(eventId: String): Result<Unit> {
            unpinCalls += eventId
            return Result.success(Unit)
        }
        override suspend fun isPinned(eventId: String): Boolean = false
        override suspend fun getCurrentPinnedEventIds(): Set<String> = emptySet()
        override fun clearAll() = Unit
    }

    private class RecordingFeedRepository : FeedRepository {
        val updateMutedAuthorsCalls = mutableListOf<Pair<String, Set<String>>>()
        override fun getAllFilters(): Flow<List<FeedFilter>> = flowOf(emptyList())
        override fun getActiveFilters(): Flow<List<FeedFilter>> = flowOf(emptyList())
        override suspend fun getFilterById(id: String): FeedFilter? = null
        override suspend fun addFilter(filter: FeedFilter) = Unit
        override suspend fun updateFilter(filter: FeedFilter) = Unit
        override suspend fun removeFilter(id: String) = Unit
        override suspend fun setFilterActive(id: String, active: Boolean) = Unit
        override suspend fun addMutedAuthor(filterId: String, pubkey: String) = Unit
        override suspend fun removeMutedAuthor(filterId: String, pubkey: String) = Unit
        override suspend fun updateMutedAuthors(filterId: String, mutedPubkeys: Set<String>) {
            updateMutedAuthorsCalls += filterId to mutedPubkeys
        }
        override suspend fun resetToDefaults() = Unit
        override suspend fun ensureDefaultFiltersSeeded() = Unit
    }

    // ── Test 1/2: requestSignAndPublish ──

    @Test
    fun `given amber returns a signed event when requestSignAndPublish runs then onSigned is called and the event is published`() = runTest {
        val broadcastRepository = RecordingBroadcastRepository()
        val coordinator = subject(scope = this, broadcastRepository = broadcastRepository)
        var onSignedCalled = false
        var onRejectedCalled = false

        coordinator.requestSignAndPublish(
            eventJson = "{}",
            currentUserHex = "hex1",
            onSigned = { onSignedCalled = true },
            onRejected = { onRejectedCalled = true }
        )
        advanceUntilIdle()
        awaitRealDispatch()
        advanceUntilIdle()

        assertTrue(onSignedCalled)
        assertFalse(onRejectedCalled)
        assertEquals(1, broadcastRepository.trackPublishCalls)
    }

    @Test
    fun `given amber returns null when requestSignAndPublish runs then onRejected is called and onSigned is never called`() = runTest {
        val broadcastRepository = RecordingBroadcastRepository()
        val gateway = FakeAmberSignerGateway(signedEventJson = null)
        val coordinator = subject(scope = this, amberSignerGateway = gateway, broadcastRepository = broadcastRepository)
        var onSignedCalled = false
        var onRejectedCalled = false

        coordinator.requestSignAndPublish(
            eventJson = "{}",
            currentUserHex = "hex1",
            onSigned = { onSignedCalled = true },
            onRejected = { onRejectedCalled = true }
        )
        advanceUntilIdle()

        assertFalse(onSignedCalled)
        assertTrue(onRejectedCalled)
        assertEquals(0, broadcastRepository.trackPublishCalls)
    }

    // ── Test 3: applyMuteChange ──

    @Test
    fun `given mute is true when applyMuteChange runs then muteListRepository mute is called`() = runTest {
        val muteListRepository = RecordingMuteListRepository()
        val coordinator = subject(scope = this, muteListRepository = muteListRepository)

        val result = coordinator.applyMuteChange("target1", mute = true)

        assertTrue(result.isSuccess)
        assertEquals(listOf("target1"), muteListRepository.muteCalls)
        assertTrue(muteListRepository.unmuteCalls.isEmpty())
    }

    @Test
    fun `given mute is false when applyMuteChange runs then muteListRepository unmute is called`() = runTest {
        val muteListRepository = RecordingMuteListRepository()
        val coordinator = subject(scope = this, muteListRepository = muteListRepository)

        val result = coordinator.applyMuteChange("target1", mute = false)

        assertTrue(result.isSuccess)
        assertEquals(listOf("target1"), muteListRepository.unmuteCalls)
        assertTrue(muteListRepository.muteCalls.isEmpty())
    }

    // ── Test 4: mirrorMuteIntoActiveFilter ──

    @Test
    fun `given mute is true when mirrorMuteIntoActiveFilter runs then the target is added to the resolved filter's mutedPubkeys`() = runTest {
        val feedRepository = RecordingFeedRepository()
        val coordinator = subject(scope = this, feedRepository = feedRepository)
        val resolvedFilter = FeedFilter(id = "filter1", name = "F", mutedPubkeys = setOf("existing"))

        coordinator.mirrorMuteIntoActiveFilter(target = "newTarget", mute = true) { resolvedFilter }

        assertEquals(1, feedRepository.updateMutedAuthorsCalls.size)
        val (filterId, mutedPubkeys) = feedRepository.updateMutedAuthorsCalls.single()
        assertEquals("filter1", filterId)
        assertEquals(setOf("existing", "newTarget"), mutedPubkeys)
    }

    @Test
    fun `given mute is false when mirrorMuteIntoActiveFilter runs then the target is removed from the resolved filter's mutedPubkeys`() = runTest {
        val feedRepository = RecordingFeedRepository()
        val coordinator = subject(scope = this, feedRepository = feedRepository)
        val resolvedFilter = FeedFilter(id = "filter1", name = "F", mutedPubkeys = setOf("existing", "toRemove"))

        coordinator.mirrorMuteIntoActiveFilter(target = "toRemove", mute = false) { resolvedFilter }

        val (filterId, mutedPubkeys) = feedRepository.updateMutedAuthorsCalls.single()
        assertEquals("filter1", filterId)
        assertEquals(setOf("existing"), mutedPubkeys)
    }

    @Test
    fun `given resolveActiveFilter returns null when mirrorMuteIntoActiveFilter runs then updateMutedAuthors is never called`() = runTest {
        val feedRepository = RecordingFeedRepository()
        val coordinator = subject(scope = this, feedRepository = feedRepository)

        coordinator.mirrorMuteIntoActiveFilter(target = "target1", mute = true) { null }

        assertTrue(feedRepository.updateMutedAuthorsCalls.isEmpty())
    }

    // ── Test 5: applyPinChange ──

    @Test
    fun `given pin is true when applyPinChange runs then pinListRepository pin is called`() = runTest {
        val pinListRepository = RecordingPinListRepository()
        val coordinator = subject(scope = this, pinListRepository = pinListRepository)

        val result = coordinator.applyPinChange("event1", pin = true)

        assertTrue(result.isSuccess)
        assertEquals(listOf("event1"), pinListRepository.pinCalls)
        assertTrue(pinListRepository.unpinCalls.isEmpty())
    }

    @Test
    fun `given pin is false when applyPinChange runs then pinListRepository unpin is called`() = runTest {
        val pinListRepository = RecordingPinListRepository()
        val coordinator = subject(scope = this, pinListRepository = pinListRepository)

        val result = coordinator.applyPinChange("event1", pin = false)

        assertTrue(result.isSuccess)
        assertEquals(listOf("event1"), pinListRepository.unpinCalls)
        assertTrue(pinListRepository.pinCalls.isEmpty())
    }

    // ── Test 6: deleteEvent commits only after Amber confirms ──

    @Test
    fun `given amber signs the delete when deleteEvent runs then onDeleteConfirmed and the cache removal fire only after the sign resolves`() = runTest {
        val event = sampleEvent(pubkey = "a".repeat(64))
        val eventRepository = FakeEventRepository()
        val coordinator = subject(scope = this, eventRepository = eventRepository)
        var deleteConfirmedCalled = false

        coordinator.deleteEvent(
            event = event,
            currentUserHex = "a".repeat(64),
            onDeleteConfirmed = { deleteConfirmedCalled = true }
        )
        // Nothing is applied ahead of the async sign round trip resolving.
        assertFalse(deleteConfirmedCalled)
        assertNull(eventRepository.deletedEventId)

        advanceUntilIdle()
        awaitRealDispatch()
        advanceUntilIdle()

        assertTrue(deleteConfirmedCalled)
        assertEquals(event.id, eventRepository.deletedEventId)
    }

    @Test
    fun `given amber rejects the delete when deleteEvent runs then onDeleteConfirmed never fires and the cache and archive are untouched`() = runTest {
        val gateway = FakeAmberSignerGateway(signedEventJson = null)
        val broadcastRepository = RecordingBroadcastRepository()
        val eventRepository = FakeEventRepository()
        val event = sampleEvent(pubkey = "a".repeat(64))
        val coordinator = subject(
            scope = this,
            amberSignerGateway = gateway,
            broadcastRepository = broadcastRepository,
            eventRepository = eventRepository
        )
        var deleteConfirmedCalled = false

        coordinator.deleteEvent(
            event = event,
            currentUserHex = "a".repeat(64),
            onDeleteConfirmed = { deleteConfirmedCalled = true }
        )
        advanceUntilIdle()
        awaitRealDispatch()
        advanceUntilIdle()

        assertFalse(deleteConfirmedCalled)
        assertNull(eventRepository.deletedEventId)
        assertEquals(0, broadcastRepository.trackPublishCalls)
    }

    @Test
    fun `given deleteEvent's owner check fails then neither the sign round trip nor onDeleteConfirmed fire`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedEventJson)
        var deleteConfirmedCalled = false
        val event = sampleEvent(pubkey = "someoneElse")
        val coordinator = subject(scope = this, amberSignerGateway = gateway)

        coordinator.deleteEvent(
            event = event,
            currentUserHex = "a".repeat(64),
            onDeleteConfirmed = { deleteConfirmedCalled = true }
        )
        advanceUntilIdle()

        assertFalse(deleteConfirmedCalled)
        assertEquals(0, gateway.signEventCalls.size)
    }

    // ── Test 7: canSignEvents ──

    @Test
    fun `given canSignEvents runs then it returns userPreferences canSignWithAmber verbatim`() = runTest {
        val signedInPrefs = FakeUserPreferences(initialPubkey = "a".repeat(64))
        val anonymousPrefs = FakeUserPreferences(initialPubkey = null)

        assertTrue(subject(scope = this, userPreferences = signedInPrefs).canSignEvents())
        assertFalse(subject(scope = this, userPreferences = anonymousPrefs).canSignEvents())
        assertNull(anonymousPrefs.getPublicKey())
    }
}
