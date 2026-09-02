@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.umbra.app.ui.feed

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.model.FeedNotesResult
import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.model.PendingRepost
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.isTimestampFromFuture
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.feed.mergeActiveFeedFilters
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.ui.common.futureEventRecheckTicker
import com.umbra.app.ui.common.toImmutableSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers

/**
 * Snapshot of the Room-sourced feed computation (visible notes + counts + reposts), before
 * merging with [FeedState]'s UI overlay (see [FeedStateMergeCoordinator.feedState]). Moved
 * verbatim from [FeedViewModel] — exists solely as the payload
 * [FeedStateMergeCoordinator.computedFeedFlow]'s `distinctUntilChanged` gate compares via
 * [stableFingerprint].
 */
internal data class ComputedFeedSnapshot(
    val events: List<Event> = emptyList(),
    val profiles: Map<String, UserProfile> = emptyMap(),
    val reactionCounts: Map<String, Int> = emptyMap(),
    val replyCounts: Map<String, Int> = emptyMap(),
    val repostCounts: Map<String, Int> = emptyMap(),
    /** Event id -> reposter pubkey, for the notes in [events] that arrived via a NIP-18 repost. */
    val repostedByPubkeys: Map<String, String> = emptyMap(),
    /** Event id -> the repost event's own created_at, for the notes in [events] that arrived via a NIP-18 repost. */
    val repostedAtByEvent: Map<String, Long> = emptyMap(),
    /** Event id -> the repost event itself, for the notes in [events] that arrived via a NIP-18 repost. */
    val repostEventByEvent: Map<String, Event> = emptyMap(),
    /** Reposts known but whose target hasn't resolved yet — rendered as a PendingRepostCard. */
    val pendingReposts: List<PendingRepost> = emptyList(),
    val oldestAt: Long? = null
)

// Order-independent so two structurally-equal maps fingerprint the same regardless of
// iteration order — combined via XOR (commutative) rather than a sequential fold.
private fun Map<String, Int>.valueFingerprint(): Int =
    entries.fold(0) { acc, (key, value) -> acc xor (key.hashCode() * 31 + value) }

private fun Map<String, String>.stringValueFingerprint(): Int =
    entries.fold(0) { acc, (key, value) -> acc xor (key.hashCode() * 31 + value.hashCode()) }

private fun Map<String, Long>.longValueFingerprint(): Int =
    entries.fold(0) { acc, (key, value) -> acc xor (key.hashCode() * 31 + value.hashCode()) }

private fun Map<String, Event>.eventValueFingerprint(): Int =
    entries.fold(0) { acc, (key, value) -> acc xor (key.hashCode() * 31 + value.id.hashCode()) }

private fun ComputedFeedSnapshot.stableFingerprint(): Int {
    var hash = 17
    events.asSequence().take(200).forEach { event ->
        hash = 31 * hash + event.id.hashCode()
    }
    // Fold the counts themselves, not just map sizes — a reaction/reply/repost count changing
    // on an event already covered by the map (size unchanged) must still invalidate the
    // fingerprint, or distinctUntilChanged silently drops a real count update.
    hash = 31 * hash + reactionCounts.valueFingerprint()
    hash = 31 * hash + replyCounts.valueFingerprint()
    hash = 31 * hash + repostCounts.valueFingerprint()
    hash = 31 * hash + repostedByPubkeys.stringValueFingerprint()
    hash = 31 * hash + repostedAtByEvent.longValueFingerprint()
    hash = 31 * hash + repostEventByEvent.eventValueFingerprint()
    // A pending repost transitioning to resolved (or a new one appearing) must also invalidate
    // the fingerprint — it changes what's rendered even if `events`' first 200 ids are unchanged.
    pendingReposts.asSequence().sortedBy { it.repostId }.forEach { pending ->
        hash = 31 * hash + pending.repostId.hashCode()
        hash = 31 * hash + pending.targetId.hashCode()
    }
    // Author profiles don't change the event/count identity above, but async NIP-05
    // verification (NotAvailable -> Pending -> Verified/Failed) lands as a later Room
    // emission for the same set of events/counts. Fold each profile's verification state
    // in so that update isn't mistaken for a no-op and dropped by distinctUntilChanged.
    profiles.asSequence().sortedBy { it.key }.forEach { (pubkey, profile) ->
        hash = 31 * hash + pubkey.hashCode()
        hash = 31 * hash + profile.nip05VerificationState.hashCode()
    }
    return hash
}

/**
 * State-merge collaborator extracted from [FeedViewModel]. Constructor shape and
 * manual-instantiation style follow [RelayIssueBannerCoordinator]/
 * [FeedEngagementSchedulingCoordinator]'s precedent: a package-`internal
 * class`, manually constructed by the facade (not Hilt-injected).
 *
 * [uiState]/[displayLimit] are the facade's own [MutableStateFlow] instances, passed by direct
 * reference (never duplicated) — mirrors [RelayIssueBannerCoordinator]'s `uiState` parameter.
 * [onVisibleNotesComputed] is the cross-coordinator coupling callback: [computedFeedFlow]'s
 * combine block invokes it once per raw combine emission in place of a direct cross-class call
 * into [FeedEngagementSchedulingCoordinator] — wired by [FeedViewModel] to
 * `feedEngagementSchedulingCoordinator::schedulePendingRelayWork`.
 *
 * [followedPubkeysFlow] is `internal`, not `private` — three facade functions
 * (`observeFollowedAuthorOutboxDiscovery` x2, `observeActiveFeedFilterChanges`) read it directly
 * outside this cluster's own combine chain, a cross-boundary dependency worth calling out
 * explicitly since it isn't obvious from this coordinator's public surface alone.
 */
internal class FeedStateMergeCoordinator(
    private val eventRepository: EventRepository,
    private val feedRepository: FeedRepository,
    private val muteListRepository: MuteListRepository,
    private val contactListRepository: ContactListRepository,
    private val userPreferences: UserPreferences,
    private val scope: CoroutineScope,
    private val displayLimit: MutableStateFlow<Int>,
    private val uiState: MutableStateFlow<FeedState>,
    private val onVisibleNotesComputed: (List<NoteView>) -> Unit
) {

    // Active filters flow (may contain multiple active filters, or none — the user is allowed
    // to have zero active filters, in which case the feed shows nothing; no synthetic fallback).
    private val activeFiltersFlow = feedRepository.getActiveFilters()
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // NIP-51 published mute list (kind 10000) for the logged-in user — a global, network-synced
    // block layer applied on top of each feed filter's local mutedPubkeys.
    private val syncedMutedPubkeysFlow: Flow<Set<String>> = userPreferences.getPublicKeyFlow()
        .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
        .distinctUntilChanged()
        .flatMapLatest { ownerPubkey ->
            if (ownerPubkey == null) flowOf(null) else muteListRepository.getMuteList(ownerPubkey)
        }
        .map { it?.mutedPubkeys.orEmpty() }
        .distinctUntilChanged()

    // NIP-02 published contact list (kind 3) for the logged-in user — the authors set that
    // any scopeToFollows filter restricts both the Room query and the relay REQ to.
    internal val followedPubkeysFlow: Flow<Set<String>> = userPreferences.getPublicKeyFlow()
        .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
        .distinctUntilChanged()
        .flatMapLatest { ownerPubkey ->
            if (ownerPubkey == null) flowOf(null) else contactListRepository.getContactList(ownerPubkey)
        }
        .map { it?.followedPubkeys.orEmpty() }
        .distinctUntilChanged()

    /**
     * Live Room flow: kind-1 notes joined with author profiles and engagement counts.
     * For B: we observe an inclusive feed (not limited to a single active filter) and
     * compute which active filters match each event later in the state combine.
     */
    private val notesFlow: SharedFlow<FeedNotesResult> = combine(
        displayLimit,
        userPreferences.getPublicKeyFlow(),
        activeFiltersFlow,
        syncedMutedPubkeysFlow,
        followedPubkeysFlow
    ) { limit, currentPubkeyRaw, activeFilters, syncedMutedPubkeys, followedPubkeys ->
        val currentUserPubkey = currentPubkeyRaw?.takeIf { it.length == 64 }
        val currentNpub = currentUserPubkey?.let {
            runCatching { Bech32Encoder.encodeNpub(it).lowercase() }.getOrNull()
        }
        val mergedFilter = mergeFilters(activeFilters)
        eventRepository.observeFeedNotes(
            since = 0L,
            limit = limit,
            authors = if (mergedFilter.scopeToFollows) followedPubkeys else emptySet(),
            mutedPubkeys = mergedFilter.mutedPubkeys + syncedMutedPubkeys,
            excludedHashtagsLower = mergedFilter.excludedHashtags.map { it.lowercase() }.toSet(),
            hideNsfw = mergedFilter.hideNsfw,
            currentNpub = currentNpub,
            currentUserPubkey = currentUserPubkey,
            desiredTagsLower = emptySet()
        )
    }
        .flatMapLatest { it }
        .flowOn(Dispatchers.IO)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // stateIn (not shareIn): feedState below combines this with uiState, and combine() can't
    // produce anything until *every* source has emitted at least once. notesFlow's own upstream
    // combine (public key/active filters/muted/followed-authors flows) can take a moment to
    // settle on a cold start, which used to stall feedState's first emission entirely — including
    // parts of uiState that were already ready (e.g. currentUserProfile, populated straight from
    // the encrypted DB independent of any relay activity, see FeedViewModel.observeCurrentUserProfile).
    // stateIn gives this an immediate default (empty) value so feedState can emit right away using
    // whatever uiState already has, instead of waiting on the feed computation to catch up too.
    val computedFeedFlow: StateFlow<ComputedFeedSnapshot> = combine(
        notesFlow,
        activeFiltersFlow,
        futureEventRecheckTicker()
    ) { result, filters, _ ->
        // Zero active filters means nothing to filter *against*, not "hide everything" — notes
        // itself already reflects mergeFilters' own fallback (DefaultFeedFilters.DEFAULT) at the
        // query/relay-REQ level, so this stage should show what that fallback already fetched
        // instead of vacuously failing every note's filters.any{} check.
        val visibleNotes = result.notes.filter { note ->
            // Empty override sets: the hashtag/tag-name/content-prefix hygiene baseline
            // isUsefulClientNote() defaults to is intentionally NOT applied here — matchesFilter()
            // below already re-checks the exact same exclusions against the user's own (fully
            // editable, defaults-but-overridable) active FeedFilter(s), so applying the hardcoded
            // baseline too would make it impossible for the user to actually disable a default
            // exclusion. Only the structural kind/reply/"/"-in-tag-name checks apply here.
            note.event.isTopLevelFeedNote(
                excludedHashtags = emptySet(),
                excludedTagNamePrefixes = emptySet(),
                excludedContentPrefixes = emptySet()
            ) &&
                !note.event.isFromFuture() &&
                (note.repostedAt == null || !isTimestampFromFuture(note.repostedAt)) &&
                (filters.isEmpty() || filters.any { filter -> matchesFilter(note.event, filter) })
        }
        val visiblePendingReposts = result.pendingReposts.filterNot { isTimestampFromFuture(it.repostedAt) }

        // Schedule pending relay work (hydration/engagement) only when visible set changes.
        // This used to be a direct schedulePendingRelayWork(visibleNotes) call into
        // FeedViewModel's own scheduling functions; now routed through the constructor-supplied
        // callback so this class has no compile-time dependency on
        // FeedEngagementSchedulingCoordinator.
        onVisibleNotesComputed(visibleNotes)

        ComputedFeedSnapshot(
            events = visibleNotes.map { it.event },
            profiles = (
                visibleNotes.mapNotNull { n -> n.authorProfile?.let { n.event.pubkey.lowercase() to it } } +
                    visibleNotes.mapNotNull { n ->
                        val pubkey = n.repostedByPubkey ?: return@mapNotNull null
                        n.repostedByProfile?.let { pubkey.lowercase() to it }
                    }
                ).toMap(),
            reactionCounts = visibleNotes.associate { it.event.id to it.reactionCount },
            replyCounts = visibleNotes.associate { it.event.id to it.replyCount },
            repostCounts = visibleNotes.associate { it.event.id to it.repostCount },
            repostedByPubkeys = visibleNotes.mapNotNull { n ->
                n.repostedByPubkey?.let { n.event.id to it }
            }.toMap(),
            repostedAtByEvent = visibleNotes.mapNotNull { n ->
                n.repostedAt?.let { n.event.id to it }
            }.toMap(),
            repostEventByEvent = visibleNotes.mapNotNull { n ->
                n.repostEvent?.let { n.event.id to it }
            }.toMap(),
            // Not content-filterable against `filters` the way visibleNotes is above — there's no
            // target event yet to check isTopLevelFeedNote()/matchesFilter() against.
            pendingReposts = visiblePendingReposts,
            oldestAt = visibleNotes.minOfOrNull { it.event.createdAt }
        )
    }
        .conflate()
        .distinctUntilChanged { old, new ->
            old.stableFingerprint() == new.stableFingerprint()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ComputedFeedSnapshot())

    /** Public feed state derived by merging computed feed snapshot with UI overlay. */
    val feedState: StateFlow<FeedState> = combine(computedFeedFlow, uiState) { computed, ui ->
        val shouldShowInitialLoading = shouldShowFeedInitialLoading(ui, computed.events)
        val stillLoading = ui.isLoadingMore &&
            (ui.lastOlderAnchor == null || computed.oldestAt == null || computed.oldestAt >= ui.lastOlderAnchor)

        ui.copy(
            events = computed.events,
            profiles = (ui.quotedAuthorProfiles.toMap() + computed.profiles).toImmutableSnapshot(),
            reactionCounts = computed.reactionCounts.toImmutableSnapshot(),
            replyCounts = computed.replyCounts.toImmutableSnapshot(),
            repostCounts = computed.repostCounts.toImmutableSnapshot(),
            repostedByPubkeys = computed.repostedByPubkeys.toImmutableSnapshot(),
            repostedAtByEvent = computed.repostedAtByEvent.toImmutableSnapshot(),
            repostEventByEvent = computed.repostEventByEvent.toImmutableSnapshot(),
            pendingReposts = computed.pendingReposts.toImmutableSnapshot(),
            isLoading = shouldShowInitialLoading,
            isLoadingMore = stillLoading,
            lastOlderAnchor = if (!stillLoading && computed.oldestAt != null) computed.oldestAt else ui.lastOlderAnchor
        )
    }.stateIn(
        scope         = scope,
        started       = SharingStarted.WhileSubscribed(5_000),
        initialValue  = FeedState(isLoading = true)
    )

    /**
     * Determine whether [event] matches [filter] — gates whether the event is included in the
     * feed at all (an event needs to match at least one active filter to be visible).
     */
    private fun matchesFilter(event: Event, filter: FeedFilter): Boolean {
        val evtPub = event.pubkey.lowercase()

        // Muted authors in the filter exclude event
        if (filter.mutedPubkeys.any { it.equals(evtPub, ignoreCase = true) }) return false

        // NSFW hiding
        if (filter.hideNsfw && event.hasAnyHashtag(setOf("nsfw"))) return false

        // Excluded tags: match by tag *name* (equals or startsWith), not by value
        if (filter.excludedTags.isNotEmpty() && event.hasAnyTagNamePrefix(filter.excludedTags)) return false

        // Excluded hashtags (explicit #t tags)
        if (filter.excludedHashtags.isNotEmpty() && event.hasAnyHashtag(filter.excludedHashtags)) return false

        // Excluded content prefixes (e.g. long-form-post proxy notes)
        if (filter.excludedContentPrefixes.any { event.content.startsWith(it, ignoreCase = true) }) return false

        return true
    }

    // internal (not private): FeedViewModel's facade still calls this directly from
    // initializeEventStream() and observeActiveFeedFilterChanges() — filters may legitimately be
    // empty (the user can have zero active filters) — see mergeActiveFeedFilters' own doc comment
    // for the DEFAULT fallback this relies on. Shared with NostrSessionManager so the app-level
    // baseline subscription can't compute a different "current feed filter" than the feed screen
    // itself.
    internal fun mergeFilters(filters: List<FeedFilter>): FeedFilter = mergeActiveFeedFilters(filters)
}
