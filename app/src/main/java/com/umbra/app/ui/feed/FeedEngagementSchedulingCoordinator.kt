package com.umbra.app.ui.feed

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrValidation
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.BuildEngagementFiltersUseCase
import com.umbra.app.domain.usecase.BuildHydrationAuthorSetUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import com.umbra.app.util.logging.UmbraLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Promoted out of FeedViewModel's companion object — a `private const val` in a
// Kotlin companion object is class-private, so FeedEngagementSchedulingCoordinator (a sibling
// file/class) couldn't otherwise see it. Same fix RelayIssueBannerCoordinator.kt and
// EventChannelRouting.kt already applied once each for their own facades.
internal const val ENGAGEMENT_REFRESH_MIN_INTERVAL_MS = 12_000L
internal const val PROFILE_HYDRATION_DEBOUNCE_MS = 1_000L
internal const val PROFILE_HYDRATION_MIN_INTERVAL_MS = 30_000L

// Matches LOAD_OLDER_TIMEOUT_MS-style Tor round-trip slack; 12s was cutting off slower relays'
// responses to the (now correctly-sized, see BuildProfileHydrationFiltersUseCase) multi-kind
// hydration REQ before they could return every kind for every author.
internal const val PROFILE_HYDRATION_CHANNEL_CLOSE_MS = 15_000L

// Cap on how many visible-note authors can jump the sweep queue per schedulePendingRelayWork
// call — see scheduleOutboxDiscoveryAcceleration(). Small: this is a priority nudge for authors
// whose note is already on screen, not a second bulk sweep.
internal const val OUTBOX_ACCELERATION_MAX_AUTHORS = 40

// CHANNEL_METADATA_HYDRATION/CHANNEL_PROFILE_WATCH are read by both this coordinator (moved
// functions) and FeedViewModel's own onCleared() facade cleanup — promoted here (not left as a
// FeedViewModel companion private const) so both sides see the identical channel id.
internal const val CHANNEL_METADATA_HYDRATION = NostrChannels.FEED_PROFILES_ONDEMAND
internal const val CHANNEL_PROFILE_WATCH = NostrChannels.FEED_PROFILES

// CHANNEL_FEED is read by both this coordinator's scheduleEngagementSubscription and several
// facade-side functions (loadOlderFeed, onCleared) that stay on FeedViewModel — same reasoning as
// CHANNEL_METADATA_HYDRATION/CHANNEL_PROFILE_WATCH above. Promoted here because FeedViewModel's
// companion `private const val CHANNEL_FEED` would otherwise be invisible to this file, a
// compile-blocking gap.
internal const val CHANNEL_FEED = NostrChannels.FEED_NOTES

// Used only inside this coordinator's scheduleEngagementSubscription — file-private is enough
// (no facade call site references it). Same value as the pre-extraction companion constant.
private const val ENGAGEMENT_SINCE_SECONDS = 48 * 60 * 60L

/**
 * Engagement/hydration scheduling collaborator extracted from [FeedViewModel]. Constructor shape
 * and manual-instantiation style follow [RelayIssueBannerCoordinator]'s precedent: a
 * package-`internal class`, manually constructed by the facade (not Hilt-injected), decides
 * *when* to fire a relay REQ from currently-visible feed state — the actual REQ dispatch stays in
 * [EventRepository].
 *
 * [outboxSweepCursor]/[outboxSweepStartedAtMs]/[recentlyVisibleAuthors] are `internal var`
 * (package-visible), not `private`, because they are genuinely written by both this coordinator
 * ([schedulePendingRelayWork]/[scheduleOutboxDiscoveryAcceleration]) and read/written by
 * [FeedViewModel]'s facade-side `sweepFollowedAuthorProfilesForDiscovery` — the same qualifier
 * precedent used elsewhere for `wouldExceedAdvertisedSubscriptionLimit`/`canApplyChannel` when a
 * facade function still needed direct access to a moved collaborator's state.
 *
 * [engagementRefreshJob]/[profileHydrationJob] are also `internal`, not `private` — widened
 * purely for direct test observability (mirrors `ProfileObserversCoordinator.followsHydrationJob`'s
 * precedent), not because any facade code reads them.
 */
internal class FeedEngagementSchedulingCoordinator(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase,
    private val buildHydrationAuthorSetUseCase: BuildHydrationAuthorSetUseCase,
    private val buildEngagementFiltersUseCase: BuildEngagementFiltersUseCase,
    private val scope: CoroutineScope,
    private val activeFeedFilter: () -> FeedFilter
) {
    private val logger = UmbraLog.tag("FeedEngagementSchedulingCoordinator")

    internal var engagementRefreshJob: Job? = null
    private var lastEngagementSubscriptionKey: String? = null
    private var lastEngagementSubscriptionAtMs: Long = 0L
    internal var profileHydrationJob: Job? = null
    private var profileHydrationChannelCloseJob: Job? = null
    private var profileWatchJob: Job? = null
    private var requestedProfileAuthors: Set<String> = emptySet()
    // Authors already hydrated among currently-visible notes, being watched on CHANNEL_PROFILE_WATCH
    // (see scheduleProfileWatch) for future updates — distinct from requestedProfileAuthors, which
    // tracks authors still awaiting their first (one-shot) fetch on CHANNEL_METADATA_HYDRATION.
    private var requestedWatchedProfileAuthors: Set<String> = emptySet()
    private var lastProfileHydrationAtMs: Long = 0L
    private var lastRelayWorkFingerprint: Int = 0
    private var lastRelayWorkCount: Int = 0

    // Followed authors already covered by the proactive outbox metadata sweep this session — see
    // FeedViewModel.sweepFollowedAuthorProfilesForDiscovery(). Advances through the whole follow
    // list in OUTBOX_SWEEP_BATCH_SIZE-sized batches on each periodic discovery retry tick, rather
    // than only hydrating whoever's notes already happen to be rendered in the feed.
    internal var outboxSweepCursor: Set<String> = emptySet()

    // Wall-clock start of this session's outbox sweep — set once, on the very first sweep — purely
    // for the "time since follow list settled" instrumentation in
    // FeedViewModel.sweepFollowedAuthorProfilesForDiscovery.
    internal var outboxSweepStartedAtMs: Long = 0L

    // Authors of currently-visible notes, refreshed by schedulePendingRelayWork — used only to
    // prioritize the periodic sweep's batch order (see prioritizeOutboxSweepOrder), never to gate
    // a request.
    internal var recentlyVisibleAuthors: Set<String> = emptySet()

    // Layers engagement filters onto CHANNEL_FEED as an overlay (setChannelOverlay) rather than a
    // separate subscribeChannel() — the followed-authors note filter and this on-screen-notes
    // engagement filter are sent together as one subscription, so scrolling doesn't spend a second
    // relay subscription slot on top of the standing feed one.
    //
    // Driven by the viewport (prefetchViewportImages, scroll-position-driven — see FeedScreen's
    // LaunchedEffect over its list state), not by every note notesFlow has ever loaded: the feed's
    // loaded note count only grows via loadOlderEvents' pagination, so capping by load order alone
    // (as this used to, via schedulePendingRelayWork's full `notes` list) would stop covering older
    // notes the moment more than the cap are loaded — even while they're the ones on screen. Same
    // fix as ProfileViewModel.scheduleProfileEngagementSubscription; see its doc comment.
    internal fun scheduleEngagementSubscription(eventIds: List<String> = emptyList(), oldestEventCreatedAt: Long? = null) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastEngagementSubscriptionAtMs < ENGAGEMENT_REFRESH_MIN_INTERVAL_MS) return

        val now = nowMs / 1000

        val subscriptionKey = if (eventIds.isEmpty()) {
            "empty"
        } else {
            eventIds.joinToString(separator = ",")
        }

        // No-op when target set did not change; prevents channel churn and UI jank.
        if (subscriptionKey == lastEngagementSubscriptionKey) {
            return
        }

        lastEngagementSubscriptionKey = subscriptionKey
        lastEngagementSubscriptionAtMs = nowMs

        engagementRefreshJob?.cancel()
        engagementRefreshJob = scope.launch {
            delay(350)

            if (eventIds.isEmpty()) {
                eventRepository.setChannelOverlay(CHANNEL_FEED, emptyList())
                return@launch
            }

            // Validate all event IDs to hex-64 format before building filters
            val validEventIds = NostrValidation.validate64HexSet(eventIds)
            if (validEventIds.isEmpty()) {
                logger.d { "No valid 64-hex event IDs for engagement filter" }
                return@launch
            }

            // ENGAGEMENT_SINCE_SECONDS is a recency default, not a ceiling: interactions on an
            // older note that's actually on screen are just as real as ones on a recent note, so
            // the window always extends back at least as far as the oldest targeted event.
            val since = minOf(now - ENGAGEMENT_SINCE_SECONDS, oldestEventCreatedAt ?: Long.MAX_VALUE)
            val chunkedFilters = buildEngagementFiltersUseCase(
                eventIds = validEventIds,
                limit = 400,
                since = since
            )

            eventRepository.setChannelOverlay(CHANNEL_FEED, chunkedFilters)
        }
    }

    /**
     * Called from [FeedStateMergeCoordinator.computedFeedFlow]'s combine block after every Room
     * emission (via the `onVisibleNotesComputed` callback FeedViewModel wires between the two
     * coordinators).
     *
     * Queues profile hydration for any note whose [NoteView.authorProfile] is still null (i.e.
     * the author's kind-0 event has not yet been cached locally), and extends the standing
     * profile watch (see [scheduleProfileWatch]) to already-hydrated authors so a later update
     * from them is still picked up live. Engagement REQs are scheduled separately, from
     * FeedViewModel's prefetchViewportImages — see [scheduleEngagementSubscription]'s doc comment
     * for why.
     */
    internal fun schedulePendingRelayWork(notes: List<NoteView>) {
        var fingerprint = 1
        notes.asSequence().map { it.event.id }.take(120).forEach { id ->
            fingerprint = 31 * fingerprint + id.hashCode()
        }
        if (fingerprint == lastRelayWorkFingerprint && notes.size == lastRelayWorkCount) {
            return
        }
        lastRelayWorkFingerprint = fingerprint
        lastRelayWorkCount = notes.size

        val authorsToHydrate = notes
            .asSequence()
            .filter { it.authorProfile == null }
            .map { it.event.pubkey.lowercase() }
            .filter { it.length == 64 && it !in requestedProfileAuthors }
            .distinct()
            .take(80)
            .toSet()

        if (authorsToHydrate.isNotEmpty()) {
            scheduleProfileHydration(authorsToHydrate)
        }

        val authorsToWatch = notes
            .asSequence()
            .filter { it.authorProfile != null }
            .map { it.event.pubkey.lowercase() }
            .filter { it.length == 64 }
            .distinct()
            .take(80)
            .toSet()

        if (authorsToWatch.isNotEmpty()) {
            scheduleProfileWatch(authorsToWatch)
        }

        recentlyVisibleAuthors = notes.asSequence()
            .map { it.event.pubkey.lowercase() }
            .filter { it.length == 64 }
            .toHashSet()
        scheduleOutboxDiscoveryAcceleration(notes)
    }

    /**
     * Requests kind:10002 (outbox relay list) out of band for an author whose note just became
     * visible but whom FeedViewModel's sweepFollowedAuthorProfilesForDiscovery periodic batch
     * sweep hasn't reached yet — without this, a large follow list means an author near the back
     * of the sweep queue only gets their outbox relays discovered minutes after their note is
     * already on screen (see OUTBOX_DISCOVERY_RETRY_INTERVAL_MS's comment, still on the facade).
     * Folds accelerated authors into outboxSweepCursor so the periodic sweep doesn't redundantly
     * re-request them later.
     */
    private fun scheduleOutboxDiscoveryAcceleration(notes: List<NoteView>) {
        if (!activeFeedFilter().scopeToFollows) return
        val candidates = notes.asSequence()
            .map { it.event.pubkey.lowercase() }
            .filter { it.length == 64 }
            .filter { it !in outboxSweepCursor }
            .filter { !eventRepository.isAuthorOutboxKnown(it) }
            .distinct()
            .take(OUTBOX_ACCELERATION_MAX_AUTHORS)
            .toSet()
        if (candidates.isEmpty()) return

        scope.launch {
            val nonFresh = filterNonFreshPubkeys(candidates)
            outboxSweepCursor = outboxSweepCursor + candidates
            if (nonFresh.isEmpty()) return@launch

            logger.d {
                "Outbox acceleration: requesting ${nonFresh.size} authors ahead of the " +
                    "periodic sweep (visible note with no known outbox relay yet)"
            }
            eventRepository.subscribeChannel(
                NostrChannels.FEED_OUTBOX_SWEEP,
                buildProfileHydrationRequestsUseCase(
                    authors = nonFresh,
                    chunkSize = nonFresh.size.coerceAtLeast(1),
                    perAuthorLimit = 60,
                    restrictToKinds = setOf(Event.KIND_RELAY_LIST_METADATA)
                )
            )
        }
    }

    // internal (not private): widened purely for direct test observability. The
    // "empty author set is a no-op" and "cancelScheduledWork cancels an in-flight job" tests need
    // to invoke this directly rather than only indirectly via schedulePendingRelayWork, which
    // already screens out empty author sets before ever reaching here.
    internal fun scheduleProfileHydration(authors: Set<String>) {
        profileHydrationJob?.cancel()
        profileHydrationJob = scope.launch {
            delay(PROFILE_HYDRATION_DEBOUNCE_MS)

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastProfileHydrationAtMs < PROFILE_HYDRATION_MIN_INTERVAL_MS) {
                return@launch
            }

            if (authors.isEmpty()) {
                return@launch
            }

            val mergedAuthors = buildHydrationAuthorSetUseCase(
                existing = requestedProfileAuthors,
                incoming = authors,
                maxAuthors = 60
            )
            val nonFreshAuthors = filterNonFreshPubkeys(mergedAuthors)
            if (nonFreshAuthors.isEmpty() || nonFreshAuthors == requestedProfileAuthors) {
                return@launch
            }
            requestedProfileAuthors = nonFreshAuthors
            lastProfileHydrationAtMs = nowMs

            logger.d {
                "Profile hydration: requesting ${nonFreshAuthors.size} authors, " +
                    "sample=${nonFreshAuthors.take(3).map { it.take(8) + "..." }}"
            }

            eventRepository.subscribeChannel(
                CHANNEL_METADATA_HYDRATION,
                buildProfileHydrationRequestsUseCase(
                    authors = nonFreshAuthors,
                    chunkSize = 60,
                    perAuthorLimit = 60
                )
            )

            // Keep on-demand profile hydration ephemeral: close the REQ as soon as every relay
            // it was sent to reports EOSE, falling back to the fixed delay as a backstop for
            // relays that never send it.
            profileHydrationChannelCloseJob?.cancel()
            profileHydrationChannelCloseJob = scope.launch {
                eventRepository.awaitChannelEoseOrTimeout(CHANNEL_METADATA_HYDRATION, PROFILE_HYDRATION_CHANNEL_CLOSE_MS)
                eventRepository.clearChannel(CHANNEL_METADATA_HYDRATION)
            }
        }
    }

    // internal (not private): also called by FeedViewModel's facade-side
    // sweepFollowedAuthorProfilesForDiscovery — a cross-boundary call site not obvious from this
    // coordinator's own function boundaries alone.
    internal suspend fun filterNonFreshPubkeys(pubkeys: Set<String>): Set<String> {
        return pubkeys.filterTo(mutableSetOf()) { pubkey ->
            !userRepository.isProfileFresh(pubkey)
        }
    }

    /**
     * Extends CHANNEL_PROFILE_WATCH — a standing (never EOSE-closed) subscription — to cover
     * [authors], authors whose profile is already hydrated among currently-visible notes. Unlike
     * [scheduleProfileHydration], this isn't about fetching their current profile (already have
     * it) but about catching a *later* update live, so there's no freshness check and no
     * EOSE-driven close: the whole point is staying open past the initial fetch.
     *
     * Passes `since = now` on every (re)subscribe so relays only push events newer than this
     * moment for the *whole* merged author set, not the latest already-known event for authors
     * that were already being watched before this call — without it, merging in one new author
     * would make relays resend state for every other watched author too.
     */
    private fun scheduleProfileWatch(authors: Set<String>) {
        profileWatchJob?.cancel()
        profileWatchJob = scope.launch {
            delay(PROFILE_HYDRATION_DEBOUNCE_MS)

            val mergedAuthors = buildHydrationAuthorSetUseCase(
                existing = requestedWatchedProfileAuthors,
                incoming = authors,
                maxAuthors = 60
            )
            if (mergedAuthors.isEmpty() || mergedAuthors == requestedWatchedProfileAuthors) {
                return@launch
            }
            requestedWatchedProfileAuthors = mergedAuthors

            eventRepository.subscribeChannel(
                CHANNEL_PROFILE_WATCH,
                buildProfileHydrationRequestsUseCase(
                    authors = mergedAuthors,
                    chunkSize = 60,
                    perAuthorLimit = 5,
                    since = System.currentTimeMillis() / 1000L
                )
            )
        }
    }

    internal fun resetRequestedProfileAuthors() {
        requestedProfileAuthors = emptySet()
        lastProfileHydrationAtMs = 0L
        requestedWatchedProfileAuthors = emptySet()
        eventRepository.clearChannel(CHANNEL_PROFILE_WATCH)
    }

    /** Mirrors `EventIngestCache.cancelPendingSnapshotEmit()`'s precedent. */
    internal fun cancelScheduledWork() {
        lastEngagementSubscriptionKey = null
        lastEngagementSubscriptionAtMs = 0L
        engagementRefreshJob?.cancel()
        profileHydrationJob?.cancel()
        profileHydrationChannelCloseJob?.cancel()
        profileWatchJob?.cancel()
    }
}
