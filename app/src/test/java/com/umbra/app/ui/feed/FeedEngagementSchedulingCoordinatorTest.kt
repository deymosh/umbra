package com.umbra.app.ui.feed

import com.umbra.app.domain.feed.DefaultFeedFilters
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.BuildEngagementFiltersUseCase
import com.umbra.app.domain.usecase.BuildHydrationAuthorSetUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationFiltersUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeUserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [FeedEngagementSchedulingCoordinator], extracted from
 * [FeedViewModel] — the debounce/cooldown dedup contracts, the bidirectional
 * `internal var` state shared with [FeedViewModel]'s facade-side
 * `sweepFollowedAuthorProfilesForDiscovery`, and [FeedEngagementSchedulingCoordinator.cancelScheduledWork]'s
 * cleanup are exactly what this kind of extraction most risks breaking silently: state that used
 * to live on a single class implicitly staying in sync can drift once it's split across two.
 */
class FeedEngagementSchedulingCoordinatorTest {

    /** Constructs a [FeedEngagementSchedulingCoordinator] against [scope] with permissive
     * defaults — a fresh [FakeEventRepository]/[FakeUserRepository] and an unscoped
     * [DefaultFeedFilters.DEFAULT] (so scopeToFollows-gated paths are exercised only when a test
     * explicitly overrides [activeFeedFilter]). */
    private fun subject(
        scope: CoroutineScope,
        eventRepository: EventRepository = FakeEventRepository(),
        userRepository: UserRepository = FakeUserRepository(),
        activeFeedFilter: () -> FeedFilter = { DefaultFeedFilters.DEFAULT }
    ): FeedEngagementSchedulingCoordinator = FeedEngagementSchedulingCoordinator(
        eventRepository = eventRepository,
        userRepository = userRepository,
        buildProfileHydrationRequestsUseCase = BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase()),
        buildHydrationAuthorSetUseCase = BuildHydrationAuthorSetUseCase(),
        buildEngagementFiltersUseCase = BuildEngagementFiltersUseCase(),
        scope = scope,
        activeFeedFilter = activeFeedFilter
    )

    /** Delegates every [EventRepository] method to a fresh [FakeEventRepository] except
     * [setChannelOverlay], which the shared fake treats as a no-op with no call tracking —
     * exactly the signal [scheduleEngagementSubscription]'s dedup test needs to observe. */
    private class TrackingEventRepository(
        private val delegate: EventRepository = FakeEventRepository()
    ) : EventRepository by delegate {
        val setChannelOverlayCalls = mutableListOf<Pair<String, List<EventFilter>>>()

        override fun setChannelOverlay(channelId: String, overlayFilters: List<EventFilter>) {
            setChannelOverlayCalls += channelId to overlayFilters
        }
    }

    @Test
    fun `given scheduleEngagementSubscription called twice within the min interval with the same event ids when the second call runs then it is a no-op`() = runTest {
        val eventRepository = TrackingEventRepository()
        val coordinator = subject(scope = this, eventRepository = eventRepository)
        val eventIds = listOf("a".repeat(64), "b".repeat(64))

        // Both calls happen within the same (real-time, near-instant) test execution window, well
        // under ENGAGEMENT_REFRESH_MIN_INTERVAL_MS (12s) — the second call must be a no-op purely
        // from the interval guard, regardless of the event-id set matching.
        coordinator.scheduleEngagementSubscription(eventIds, oldestEventCreatedAt = 1L)
        coordinator.scheduleEngagementSubscription(eventIds, oldestEventCreatedAt = 1L)
        advanceTimeBy(400L)
        advanceUntilIdle()

        assertEquals(1, eventRepository.setChannelOverlayCalls.size)
    }

    @Test
    fun `given an empty author set when scheduleProfileHydration runs then no subscribeChannel call occurs`() = runTest {
        val eventRepository = FakeEventRepository()
        val coordinator = subject(scope = this, eventRepository = eventRepository)

        coordinator.scheduleProfileHydration(emptySet())
        advanceTimeBy(1_100L)
        advanceUntilIdle()

        assertTrue(eventRepository.subscriptions.isEmpty())
    }

    @Test
    fun `given outboxSweepCursor and recentlyVisibleAuthors when set directly then they are readable as plain mutable fields`() = runTest {
        val coordinator = subject(this)
        val authors = setOf("c".repeat(64))
        val startedAtMs = 12_345L

        coordinator.outboxSweepCursor = authors
        coordinator.recentlyVisibleAuthors = authors
        coordinator.outboxSweepStartedAtMs = startedAtMs

        assertEquals(authors, coordinator.outboxSweepCursor)
        assertEquals(authors, coordinator.recentlyVisibleAuthors)
        assertEquals(startedAtMs, coordinator.outboxSweepStartedAtMs)
    }

    @Test
    fun `given an in-flight job started by scheduleProfileHydration when cancelScheduledWork runs then the job is no longer active`() = runTest {
        val coordinator = subject(this)

        coordinator.scheduleProfileHydration(setOf("d".repeat(64)))
        val job = coordinator.profileHydrationJob
        assertNotNull(job)
        assertTrue(job!!.isActive)

        coordinator.cancelScheduledWork()

        assertFalse(job.isActive)
    }
}
