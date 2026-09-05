@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.umbra.app.ui.feed

import com.umbra.app.domain.feed.DefaultFeedFilters
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.model.FeedNotesResult
import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.testutil.fakes.FakeContactListRepository
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeMuteListRepository
import com.umbra.app.testutil.fakes.FakeUserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [FeedStateMergeCoordinator], extracted from [FeedViewModel] — the
 * cross-coordinator coupling callback into [FeedEngagementSchedulingCoordinator], the
 * `stateIn`-not-`shareIn` cold-start-stall fix, and the `mergeFilters` delegate's fallback
 * behavior are exactly what this kind of extraction most risks breaking silently.
 */
class FeedStateMergeCoordinatorTest {

    private val ownerPubkey = "a".repeat(64)

    /** Delegates every [FeedRepository] method to sensible no-op defaults except
     * [getActiveFilters], overridable per test — mirrors [FakeMuteListRepository]/
     * [FakeContactListRepository]'s shape (no shared fake exists yet for this small interface). */
    private class FakeFeedRepository(
        private val activeFilters: Flow<List<FeedFilter>> = flowOf(emptyList())
    ) : FeedRepository {
        override fun getAllFilters(): Flow<List<FeedFilter>> = flowOf(emptyList())
        override fun getActiveFilters(): Flow<List<FeedFilter>> = activeFilters
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

    /** Delegates every [EventRepository] method to a fresh [FakeEventRepository] except
     * [observeFeedNotes], which returns [notes] directly — lets a test drive multiple sequential
     * [FeedNotesResult] emissions through notesFlow via a controllable [MutableSharedFlow]. */
    private class ObservableFeedEventRepository(
        private val notes: Flow<FeedNotesResult>
    ) : EventRepository by FakeEventRepository() {
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
        ): Flow<FeedNotesResult> = notes
    }

    private fun subject(
        scope: CoroutineScope,
        eventRepository: EventRepository = FakeEventRepository(),
        feedRepository: FeedRepository = FakeFeedRepository(),
        muteListRepository: MuteListRepository = FakeMuteListRepository(),
        contactListRepository: ContactListRepository = FakeContactListRepository(),
        userPreferences: FakeUserPreferences = FakeUserPreferences(initialPubkey = ownerPubkey),
        displayLimit: MutableStateFlow<Int> = MutableStateFlow(300),
        uiState: MutableStateFlow<FeedState> = MutableStateFlow(FeedState(isLoading = true)),
        onVisibleNotesComputed: (List<NoteView>) -> Unit = {}
    ): FeedStateMergeCoordinator = FeedStateMergeCoordinator(
        eventRepository = eventRepository,
        feedRepository = feedRepository,
        muteListRepository = muteListRepository,
        contactListRepository = contactListRepository,
        userPreferences = userPreferences,
        scope = scope,
        displayLimit = displayLimit,
        uiState = uiState,
        onVisibleNotesComputed = onVisibleNotesComputed
    )

    private fun event(id: String, createdAt: Long = 1L): Event = Event(
        id = id,
        pubkey = "b".repeat(64),
        createdAt = createdAt,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = "hello",
        sig = "c".repeat(128)
    )

    private fun noteView(
        id: String,
        createdAt: Long = 1L,
        repostedAt: Long? = null
    ): NoteView = NoteView(
        event = event(id, createdAt = createdAt),
        authorProfile = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        repostedAt = repostedAt
    )

    /** [notesFlow] is `.flowOn(Dispatchers.IO)` and [FeedStateMergeCoordinator.computedFeedFlow]'s
     * own combine is `.flowOn(Dispatchers.Default)` — both moved verbatim from the pre-extraction
     * `FeedViewModel.kt`, so both real thread-pool dispatchers sit outside `runTest`'s virtual-time
     * `TestCoroutineScheduler` entirely;
     * `advanceUntilIdle()` cannot fast-forward either hop, mirroring
     * `EventIngestCacheTest.awaitInsertDebounce()`'s documented rationale for its own
     * `Dispatchers.IO`-hardcoded `scheduleInsert`. A short real suspend on each real dispatcher lets
     * that background work actually finish before `advanceUntilIdle()` drains whatever the result
     * schedules back onto the test dispatcher. */
    private suspend fun awaitRealDispatch() {
        withContext(Dispatchers.IO) { delay(300) }
        withContext(Dispatchers.Default) { delay(300) }
    }

    @Test
    fun `given computedFeedFlow collected across sequential notesFlow emissions when a distinct visible note set arrives then onVisibleNotesComputed reflects it each time`() = runTest {
        val notesChannel = MutableSharedFlow<FeedNotesResult>(replay = 1)
        val calls = mutableListOf<List<String>>()
        // scope = backgroundScope (not `this`): computedFeedFlow/notesFlow/activeFiltersFlow are
        // all shareIn/stateIn against this scope — those sharing coroutines are designed to run
        // until the scope itself is cancelled (viewModelScope.onCleared() in production), so they
        // never complete on their own. `this@runTest`'s own Job is what runTest waits on for
        // completion at the end of the test body; backgroundScope is the sanctioned kotlinx-
        // coroutines-test scope auto-cancelled after the test body finishes, exactly for
        // never-completing background work like this (see UncompletedCoroutinesError's own message
        // when this was `this`: "Use TestScope.backgroundScope to launch the coroutines that need
        // to be cancelled when the test body finishes").
        val coordinator = subject(
            scope = backgroundScope,
            eventRepository = ObservableFeedEventRepository(notesChannel),
            onVisibleNotesComputed = { notes -> calls.add(notes.map { it.event.id }) }
        )

        val job = launch { coordinator.computedFeedFlow.collect {} }
        advanceUntilIdle()

        val note1 = noteView("note-1")
        notesChannel.emit(FeedNotesResult(notes = listOf(note1)))
        awaitRealDispatch()
        advanceUntilIdle()
        // Same visible-note-set re-delivered (e.g. an unrelated Room re-emission) — the callback
        // still fires (it's a direct per-emission call, not itself deduped;
        // FeedEngagementSchedulingCoordinator's own schedulePendingRelayWork fingerprint check is
        // what absorbs a no-op re-delivery in production — see
        // FeedEngagementSchedulingCoordinatorTest).
        notesChannel.emit(FeedNotesResult(notes = listOf(note1)))
        awaitRealDispatch()
        advanceUntilIdle()
        val note2 = noteView("note-2")
        notesChannel.emit(FeedNotesResult(notes = listOf(note2)))
        awaitRealDispatch()
        advanceUntilIdle()

        // The callback is wired (not silently dropped, not stuck on stale data) and reflects the
        // CURRENT combine emission's visible notes every time — this cross-coordinator coupling is
        // exactly what could break silently during extraction if the callback were ever wired to
        // stale state instead of the live combine emission.
        assertTrue(calls.isNotEmpty())
        assertEquals(listOf("note-2"), calls.last())
        assertEquals(
            listOf(listOf("note-1"), listOf("note-1"), listOf("note-2")),
            calls
        )

        job.cancel()
    }

    @Test
    fun `given uiState already populated with currentUserProfile when feedState is collected then its first emission reflects that populated field before any relay data arrives`() = runTest {
        val profile = UserProfile(pubkey = ownerPubkey, name = "Test User")
        val uiState = MutableStateFlow(FeedState(isLoading = true, currentUserProfile = profile))
        // A MutableSharedFlow with no replay and nothing ever emitted into it models "no relay
        // data has arrived yet" — notesFlow's upstream never settles.
        val coordinator = subject(
            scope = backgroundScope,
            eventRepository = ObservableFeedEventRepository(MutableSharedFlow()),
            uiState = uiState
        )

        // Regression guard for the stateIn-not-shareIn cold-start fix: feedState's first *merged*
        // emission must not block forever waiting on notesFlow to produce a value. Deliberately
        // NOT `coordinator.feedState.first()`: a StateFlow built via `WhileSubscribed`-backed
        // `stateIn` immediately replays its CURRENT value (the static `initialValue` passed to
        // `stateIn`) to any new collector the instant it subscribes — `.first()` returns that
        // placeholder before the sharing machinery gets a chance to run the combine at all.
        // Subscribing via `launch { collect {} }` + `advanceUntilIdle()` (this codebase's own
        // established pattern, e.g. FeedEngagementSchedulingCoordinatorTest) is necessary but not
        // sufficient here: feedState's own combine(computedFeedFlow, uiState) isn't itself
        // `flowOn`'d, but subscribing to it transitively starts computedFeedFlow's WhileSubscribed
        // sharing, whose upstream is `.flowOn(Dispatchers.Default)` (moved verbatim from
        // pre-extraction FeedViewModel.kt — see awaitRealDispatch()'s doc comment) — that real
        // thread-pool hop must actually get scheduled and run once before feedState's combine
        // lambda is invoked even a single time, which a virtual-time-only advanceUntilIdle() can't
        // force. A real wait (awaitRealDispatch()) between the two advanceUntilIdle() calls lets it
        // happen before reading feedState.value.
        val job = launch { coordinator.feedState.collect {} }
        advanceUntilIdle()
        awaitRealDispatch()
        advanceUntilIdle()
        val first = coordinator.feedState.value
        job.cancel()

        assertEquals(profile, first.currentUserProfile)
    }

    @Test
    fun `given a future dated note in notesFlow when computedFeedFlow computes the visible set then it is excluded while a past dated note is kept`() = runTest {
        // The merge stage combines the notes flow, the active filters flow, and the recheck
        // ticker; combine() cannot produce a single snapshot until every one of its sources has
        // emitted at least once, so a computed snapshot arriving here at all is itself proof the
        // ticker is a live, subscribed source of this flow rather than a dangling call wired to
        // nothing.
        val notesChannel = MutableSharedFlow<FeedNotesResult>(replay = 1)
        val calls = mutableListOf<List<String>>()
        val coordinator = subject(
            scope = backgroundScope,
            eventRepository = ObservableFeedEventRepository(notesChannel),
            onVisibleNotesComputed = { notes -> calls.add(notes.map { it.event.id }) }
        )

        val job = launch { coordinator.computedFeedFlow.collect {} }
        advanceUntilIdle()

        val nowSecs = System.currentTimeMillis() / 1000L
        val pastNote = noteView("past-note", createdAt = nowSecs - 3600L)
        val futureNote = noteView("future-note", createdAt = nowSecs + 3600L)
        val pastNoteWithFutureRepost = noteView(
            "past-note-future-repost",
            createdAt = nowSecs - 3600L,
            repostedAt = nowSecs + 3600L
        )
        notesChannel.emit(FeedNotesResult(notes = listOf(pastNote, futureNote, pastNoteWithFutureRepost)))
        awaitRealDispatch()
        advanceUntilIdle()

        assertEquals(listOf("past-note"), calls.last())
        assertEquals(listOf("past-note"), coordinator.computedFeedFlow.value.events.map { it.id })

        job.cancel()
    }

    @Test
    fun `given an empty filter list when mergeFilters is called then it falls back to DefaultFeedFilters DEFAULT`() = runTest {
        // scope = backgroundScope, not `this` — see the first test's comment: merely constructing
        // FeedStateMergeCoordinator eagerly launches its shareIn/stateIn sharing coroutines, which
        // never complete on their own even with zero subscribers.
        val coordinator = subject(backgroundScope)

        val merged = coordinator.mergeFilters(emptyList())

        assertEquals(DefaultFeedFilters.DEFAULT, merged)
    }
}
