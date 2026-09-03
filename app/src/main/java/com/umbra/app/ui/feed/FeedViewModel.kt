@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.umbra.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.R
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.feed.DefaultFeedFilters
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.model.PendingRepost
import com.umbra.app.domain.preferences.DeveloperFlag
import com.umbra.app.domain.preferences.DeveloperPreferences
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.TorProxyConfig
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.ReactionEmojiRepository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.media.MediaDataSourceProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.domain.usecase.CheckTorStatusUseCase
import com.umbra.app.domain.tor.TorRuntimeController
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.PublishAuthEventUseCase
import com.umbra.app.domain.usecase.DeleteNoteUseCase
import com.umbra.app.domain.usecase.RemoveDeletedNoteFromCacheUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import com.umbra.app.domain.usecase.BuildHydrationAuthorSetUseCase
import com.umbra.app.domain.usecase.BuildEngagementFiltersUseCase
import com.umbra.app.domain.usecase.BuildEventShareUrlUseCase
import com.umbra.app.ui.common.ImmutableListSnapshot
import com.umbra.app.ui.common.ImmutableMapSnapshot
import com.umbra.app.ui.common.InteractionActionsCoordinator
import com.umbra.app.ui.common.collectViewportEventIds
import com.umbra.app.ui.common.collectViewportOldestCreatedAt
import com.umbra.app.ui.common.collectViewportHttpPrefetchUrls
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.ui.common.collectViewportImagePrefetchUrls
import com.umbra.app.ui.common.mergeBounded
import com.umbra.app.ui.common.requestViewportMentionedProfiles
import com.umbra.app.ui.common.resolveViewportQuotedEvents
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.ImagePrefetcher
import com.umbra.app.util.UrlPrefetcher
import com.umbra.app.util.logging.UmbraLog
import androidx.compose.runtime.Immutable
import javax.inject.Inject
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.CachePolicy

/**
 * UI state for event interactions
 */
data class EventInteraction(
    val eventId: String,
    val liked: Boolean = false,
    val shared: Boolean = false,
    val replied: Boolean = false
)

/**
 * UI state for the feed screen
 */
@Immutable
data class FeedState(
    val events: List<Event> = emptyList(),
    val profiles: ImmutableMapSnapshot<String, UserProfile> = ImmutableMapSnapshot(),
    val currentUserPubkey: String? = null,
    val currentUserProfile: UserProfile? = null,
    val replyCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    val reactionCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    val repostCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    /** Event id -> reposter pubkey, for notes that arrived via a NIP-18 repost (see EventCard's repost banner). */
    val repostedByPubkeys: ImmutableMapSnapshot<String, String> = ImmutableMapSnapshot(),
    /** Event id -> the repost event's own created_at, for the repost banner's relative-time label. */
    val repostedAtByEvent: ImmutableMapSnapshot<String, Long> = ImmutableMapSnapshot(),
    /** Event id -> the repost event itself, for the repost banner's overflow menu (same actions as a normal note's menu). */
    val repostEventByEvent: ImmutableMapSnapshot<String, Event> = ImmutableMapSnapshot(),
    /** Reposts known but whose target hasn't resolved yet — rendered as a PendingRepostCard. */
    val pendingReposts: ImmutableListSnapshot<PendingRepost> = ImmutableListSnapshot(),
    val isLoading: Boolean = true,
    val errorMessage: UiMessage? = null,
    // Set only when errorMessage came from a specific relay's RelayIssue (see observeRelayIssues)
    // — lets the banner offer a "jump to this relay" action. Every other errorMessage setter must
    // pass errorRelayId = null explicitly (copy() otherwise leaves it at its previous value),
    // since a stale id here would make an unrelated banner (e.g. "note content empty") wrongly
    // navigate to whatever relay's issue last showed.
    val errorRelayId: String? = null,
    val showFeedErrorBanner: Boolean = false,
    val verboseRelayBanners: Boolean = false,
    val isConnected: Boolean = false,
    val relayCount: Int = 0,
    val torExitIp: String? = null,
    val torExitCountry: String? = null,
    val isTorConnected: Boolean = false,
    val torStatus: String? = null,
    val interactions: ImmutableMapSnapshot<String, EventInteraction> = ImmutableMapSnapshot(),
    val isLoadingMore: Boolean = false,
    val lastOlderAnchor: Long? = null,
    // True once loadOlderFeed() has paged back to LOAD_OLDER_MAX_LOOKBACK_SECS with nothing
    // further found — drives a "no more notes found" row instead of retrying forever.
    val olderNotesExhausted: Boolean = false,
    val pinnedEventIds: Set<String> = emptySet(),
    // Quoted events resolved via viewport prefetch (see prefetchViewportImages) that aren't
    // already part of `events` — e.g. a note quotes something outside the feed's own visible
    // list. Merged into eventsById in FeedScreen so EventCard can render them inline instead of
    // falling back to the raw reference chip. Kept separate from `computedFeedFlow`'s own
    // recompute (unlike `profiles`/`events`) since nothing else already tracks this data to
    // recompute it from — it must accumulate across viewport ticks instead of being replaced.
    val resolvedQuotedEvents: ImmutableMapSnapshot<String, Event> = ImmutableMapSnapshot(),
    // Profiles for resolvedQuotedEvents' authors, fetched alongside them in
    // prefetchViewportImages — `profiles` above is entirely re-derived from computedFeedFlow's
    // visible top-level notes on every emission (see feedState's combine block), so a quoted
    // note's author (who may not have any visible top-level note of their own) would never be
    // in there and would get overwritten right back out even if merged in directly. Kept
    // separate for the same reason as resolvedQuotedEvents: it must accumulate across viewport
    // ticks, not be replaced.
    val quotedAuthorProfiles: ImmutableMapSnapshot<String, UserProfile> = ImmutableMapSnapshot()
)

// ComputedFeedSnapshot and its *Fingerprint()/stableFingerprint() helpers moved to
// FeedStateMergeCoordinator.kt — they exist solely for computedFeedFlow's
// distinctUntilChanged gate, which moved with them.

internal fun shouldShowFeedInitialLoading(uiState: FeedState, computedEvents: List<Event>): Boolean {
    return uiState.isLoading && computedEvents.isEmpty()
}

internal data class LoadOlderFeedDecision(val shouldFetch: Boolean, val isExhausted: Boolean)

/**
 * Decides whether loadOlderFeed() should actually fire a fetch, given the oldest cached event's
 * timestamp ([oldest]) and the anchor from the previous attempt ([lastAnchor]).
 *
 * Regression guard: a naive "skip if the anchor didn't move since last time" check (what this
 * file used to do) blocks *forever* the first time a page fetch completes without moving the
 * anchor (relay returns nothing new, or only duplicates already cached — plausible under Tor and
 * partial EOSE) — every later scroll-triggered call sees the same stuck anchor and no-ops
 * permanently, which is indistinguishable from "load more" being broken. Using a cooldown instead
 * of a hard block means a stuck anchor is retried once [sameAnchorCooldownMs] has passed, so a
 * relay that simply hadn't caught up yet (or a temporary gap) gets asked again instead of being
 * given up on. A *moved* anchor still gets a short [differentAnchorCooldownMs] to avoid firing on
 * every intermediate recomposition while new content streams in.
 *
 * [isExhausted] is a separate, simpler signal: once [oldest] has already been pushed back to (or
 * past) [maxLookbackSecs] before now, further paging is pointless regardless of the cooldown —
 * that's the floor loadOlderEvents' own windowed paging is willing to search, so reaching it means
 * "nothing more within the window we search," not "this feed has no older notes at all." Once
 * true, [shouldFetch] is forced false too so the retry loop stops firing REQs.
 */
internal fun decideLoadOlderFeed(
    oldest: Long,
    lastAnchor: Long?,
    lastLoadMoreAtMs: Long,
    nowMs: Long,
    sameAnchorCooldownMs: Long,
    differentAnchorCooldownMs: Long,
    maxLookbackSecs: Long
): LoadOlderFeedDecision {
    val sameAnchor = lastAnchor == oldest
    val cooldown = if (sameAnchor) sameAnchorCooldownMs else differentAnchorCooldownMs
    val isExhausted = oldest <= (nowMs / 1000L) - maxLookbackSecs
    val shouldFetch = !isExhausted && nowMs - lastLoadMoreAtMs >= cooldown
    return LoadOlderFeedDecision(shouldFetch = shouldFetch, isExhausted = isExhausted)
}

/**
 * Orders [remaining] (the followed authors not yet covered by this session's outbox-relay-list
 * sweep — see sweepFollowedAuthorProfilesForDiscovery) so authors in [recentlyActiveAuthors] are
 * discovered first, before [OUTBOX_SWEEP_BATCH_SIZE] truncates the batch.
 *
 * The periodic sweep alone makes forward progress through the whole follow list, but in raw
 * `Set` iteration order — an author whose notes are already visible in the feed is no more likely
 * to be covered soon than one who never posts. Prioritizing by recent activity targets the actual
 * user-visible symptom (a note that's already on screen, from an author whose outbox relay isn't
 * known yet) instead of treating every follow as equally urgent.
 */
internal fun prioritizeOutboxSweepOrder(
    remaining: Set<String>,
    recentlyActiveAuthors: Set<String>
): List<String> {
    if (recentlyActiveAuthors.isEmpty()) return remaining.toList()
    val (active, inactive) = remaining.partition { it in recentlyActiveAuthors }
    return active + inactive
}

/**
 * ViewModel for feed screen
 * Manages event streaming from relays with reactive filtering
 */
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val feedRepository: FeedRepository,
    private val reactionEmojiRepository: ReactionEmojiRepository,
    private val contactListRepository: ContactListRepository,
    private val muteListRepository: MuteListRepository,
    private val pinListRepository: PinListRepository,
    private val relayRepository: RelayRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val developerPreferences: DeveloperPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val mediaDataSourceProvider: MediaDataSourceProvider,
    private val videoCacheDataSourceProvider: VideoCacheDataSourceProvider,
    private val imageLoader: ImageLoader,
    private val imagePrefetcher: ImagePrefetcher,
    private val urlPrefetcher: UrlPrefetcher,
    private val publishSignedEventUseCase: PublishSignedEventUseCase,
    private val checkTorStatusUseCase: CheckTorStatusUseCase,
    private val torRuntimeController: TorRuntimeController,
    private val deleteNoteUseCase: DeleteNoteUseCase,
    private val removeDeletedNoteFromCacheUseCase: RemoveDeletedNoteFromCacheUseCase,
    private val publishAuthEventUseCase: PublishAuthEventUseCase,
    private val buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase,
    private val buildHydrationAuthorSetUseCase: BuildHydrationAuthorSetUseCase,
    private val buildEngagementFiltersUseCase: BuildEngagementFiltersUseCase,
    private val trackReferencedAuthorUseCase: TrackReferencedAuthorUseCase,
    private val buildEventShareUrlUseCase: BuildEventShareUrlUseCase
) : ViewModel() {
    val mediaCacheDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()

    val reactionEmojis: StateFlow<List<ReactionEmoji>> = reactionEmojiRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        private const val TAG = "UmbraFeedVM"
        // CHANNEL_FEED/CHANNEL_METADATA_HYDRATION/CHANNEL_PROFILE_WATCH moved to
        // FeedEngagementSchedulingCoordinator.kt as top-level `internal const val` —
        // read from both this facade (loadOlderFeed/onCleared) and the coordinator's moved
        // functions, so a class-private companion constant here would no longer be visible to it.
        private const val CHANNEL_SELF_PROFILE = NostrChannels.OUTBOX_PROFILE
        private const val FEED_SINCE_SECONDS = 24 * 60 * 60L
        private const val NOTIF_SINCE_SECONDS = 7 * 24 * 60 * 60L
        private const val LOAD_OLDER_TIMEOUT_MS = 18_000L
        // Same cooldown-retry shape as ProfileViewModel's PROFILE_LOAD_OLDER_*_COOLDOWN_MS —
        // see decideLoadOlderFeed's doc comment for why a same-anchor retry must eventually
        // happen instead of blocking forever.
        private const val LOAD_OLDER_DIFFERENT_ANCHOR_COOLDOWN_MS = 1_200L
        private const val LOAD_OLDER_SAME_ANCHOR_COOLDOWN_MS = 8_000L
        // How far back loadOlderFeed() is willing to page before giving up and showing "no more
        // notes found" instead of retrying forever — see decideLoadOlderFeed's isExhausted.
        private const val LOAD_OLDER_MAX_LOOKBACK_SECS = 2L * 365L * 24 * 60 * 60L
        // The proactive follow-list-wide metadata sweep (see FEED_OUTBOX_SWEEP) re-runs on this
        // interval so it keeps making forward progress through the whole follow list over a long
        // session, not just once at startup. Tightened from 60s: at the old 60s/60-author pace, a
        // follow list of a few hundred authors could take 5+ minutes before an author near the
        // back of the queue got their NIP-65 relay list requested at all — their notes only
        // arrived once some other already-connected relay happened to also carry them. This is a
        // starting point, not an empirically-derived relay-tolerance ceiling: the REQ this drives
        // is restricted to kind:10002 only (small events), so it's cheap per tick, but back off if
        // relay rate-limit/throttle signals increase after this change.
        private const val OUTBOX_DISCOVERY_RETRY_INTERVAL_MS = 20_000L
        // Batch size for the proactive follow-list-wide metadata sweep (see FEED_OUTBOX_SWEEP).
        // See OUTBOX_DISCOVERY_RETRY_INTERVAL_MS's comment — raised alongside the interval so a
        // large follow list finishes outbox discovery in roughly a minute instead of several.
        private const val OUTBOX_SWEEP_BATCH_SIZE = 150
        // OUTBOX_ACCELERATION_MAX_AUTHORS moved to FeedEngagementSchedulingCoordinator.kt — only
        // its (moved) scheduleOutboxDiscoveryAcceleration reads it.
    }

    private val logger = UmbraLog.tag(TAG)

    private var lastLoadOlderAtMs: Long = 0L
    // Latest relay list snapshot, kept for resolving a RelayIssue's relayUrl to a Relay.id when a
    // banner is shown — see observeRelayIssues()/errorRelayId. Updated as a side effect of the
    // relayCount combine() in initializeEventStream() rather than a dedicated collector, since
    // that flow already collects relayRepository.getAllRelays().
    private var latestRelays: List<Relay> = emptyList()
    private var activeFilterJob: Job? = null
    // engagementRefreshJob/lastEngagementSubscriptionKey/lastEngagementSubscriptionAtMs,
    // profileHydrationJob/profileHydrationChannelCloseJob/profileWatchJob,
    // requestedProfileAuthors/requestedWatchedProfileAuthors/lastProfileHydrationAtMs, and
    // lastRelayWorkFingerprint/lastRelayWorkCount all moved into
    // FeedEngagementSchedulingCoordinator — each is read/written only inside the
    // 7 functions that moved with them.
    private var activeFeedFilter: FeedFilter = DefaultFeedFilters.DEFAULT
    // outboxSweepCursor/outboxSweepStartedAtMs/recentlyVisibleAuthors moved to
    // FeedEngagementSchedulingCoordinator as `internal var` properties — they are
    // genuinely written by both the coordinator's moved functions and this facade's
    // sweepFollowedAuthorProfilesForDiscovery below, so they're read/written there as
    // feedEngagementSchedulingCoordinator.outboxSweepCursor etc. rather than duplicated.

    private var loadOlderTimeoutJob: Job? = null
    private var searchJob: Job? = null
    private var viewportPrefetchJob: Job? = null
    private val feedViewportPrefetchScope = "feed-viewport"
    private val feedViewportUrlPrefetchScope = "feed-url-viewport"
    private val _searchResults = MutableStateFlow<List<Event>>(emptyList())
    val searchResults: StateFlow<List<Event>> = _searchResults.asStateFlow()

    // ── SSoT: Room is the single source of truth for events/profiles/counts ──

    /** Increasing this value causes notesFlow to re-subscribe and return more rows. */
    private val _displayLimit = MutableStateFlow(300)

    /** Overlay state — everything that does not come from the Room join. */
    private val _uiState = MutableStateFlow(FeedState(isLoading = true))

    // Manually constructed (not Hilt-injected), following EventChannelRouting's precedent —
    // shares this ViewModel's own _uiState instance and viewModelScope by reference/lambda rather
    // than owning independent copies.
    private val relayIssueBannerCoordinator = RelayIssueBannerCoordinator(
        eventRepository = eventRepository,
        userPreferences = userPreferences,
        amberSignerGateway = amberSignerGateway,
        publishAuthEventUseCase = publishAuthEventUseCase,
        uiState = _uiState,
        scope = viewModelScope,
        latestRelays = { latestRelays }
    )

    // Manually constructed — must be declared before feedStateMergeCoordinator below, whose
    // constructor wires this coordinator's schedulePendingRelayWork method reference as its
    // onVisibleNotesComputed callback. activeFeedFilter is threaded as a getter lambda (a plain
    // var mutated elsewhere in this file), matching latestRelays above.
    private val feedEngagementSchedulingCoordinator = FeedEngagementSchedulingCoordinator(
        eventRepository = eventRepository,
        userRepository = userRepository,
        buildProfileHydrationRequestsUseCase = buildProfileHydrationRequestsUseCase,
        buildHydrationAuthorSetUseCase = buildHydrationAuthorSetUseCase,
        buildEngagementFiltersUseCase = buildEngagementFiltersUseCase,
        scope = viewModelScope,
        activeFeedFilter = { activeFeedFilter }
    )

    // Manually constructed — owns the notesFlow/computedFeedFlow/feedState combine
    // chain (see FeedStateMergeCoordinator.kt for the stateIn-not-shareIn cold-start-fix doc
    // comment, carried forward verbatim). _displayLimit/_uiState are shared by direct reference
    // (never duplicated), matching RelayIssueBannerCoordinator's uiState precedent above.
    // onVisibleNotesComputed wires the cross-coordinator coupling callback directly to
    // feedEngagementSchedulingCoordinator's own method, replacing the direct cross-class call
    // that was previously a temporary compile-verify stand-in during this extraction.
    private val feedStateMergeCoordinator = FeedStateMergeCoordinator(
        eventRepository = eventRepository,
        feedRepository = feedRepository,
        muteListRepository = muteListRepository,
        contactListRepository = contactListRepository,
        userPreferences = userPreferences,
        scope = viewModelScope,
        displayLimit = _displayLimit,
        uiState = _uiState,
        onVisibleNotesComputed = feedEngagementSchedulingCoordinator::schedulePendingRelayWork
    )

    // Manually constructed — its own instance, separate from ProfileViewModel's. Owns the
    // sign/publish/mute/pin plumbing shared by likeEvent/repostEvent/muteUser/togglePin/
    // deleteEvent/shareEvent/publishSignedEvent/getEventJson/canSignEvents below; each of those
    // methods keeps its own canSignWithAmber() guard and mutate-after-confirm ordering inline.
    private val interactionActionsCoordinator = InteractionActionsCoordinator(
        userPreferences = userPreferences,
        muteListRepository = muteListRepository,
        pinListRepository = pinListRepository,
        feedRepository = feedRepository,
        amberSignerGateway = amberSignerGateway,
        publishSignedEventUseCase = publishSignedEventUseCase,
        deleteNoteUseCase = deleteNoteUseCase,
        removeDeletedNoteFromCacheUseCase = removeDeletedNoteFromCacheUseCase,
        buildEventShareUrlUseCase = buildEventShareUrlUseCase,
        scope = viewModelScope
    )

    /** Public user repository exposed for components that need to resolve profiles */
    val userRepositoryPublic: UserRepository
        get() = this.userRepository

    /**
     * Public feed state — delegates to [feedStateMergeCoordinator]. Declared type/name unchanged
     * from before extraction (a `get()` delegate, not a stored `val`), so this remains a
     * drop-in replacement for callers with no change to its public contract.
     */
    val feedState: StateFlow<FeedState>
        get() = feedStateMergeCoordinator.feedState

    val mediaDataSourceFactory get() = mediaDataSourceProvider.getDataSourceFactory()

    init {
        observeUserSessionState()
        observeCurrentUserProfile()
        initializeEventStream()
        relayIssueBannerCoordinator.observeRelayIssues()
        fetchTorExitInfo()
        observeTorRuntimeState()
        observePinnedEventsForCurrentSession()
        observeFollowedAuthorOutboxDiscovery()
        observeDeveloperOptions()
    }

    private fun observeDeveloperOptions() {
        viewModelScope.launch {
            developerPreferences.observeEnabledFlags()
                .collect { flags ->
                    _uiState.update {
                        it.copy(
                            showFeedErrorBanner = flags.contains(DeveloperFlag.ENABLE_FEED_ERROR_BANNER),
                            verboseRelayBanners = flags.contains(DeveloperFlag.SHOW_ALL_RELAY_BANNERS)
                        )
                    }
                }
        }
    }

    // Room-backed and keyed on the session pubkey, so the top bar/drawer always converge on the
    // latest saved profile (including async NIP-05 verification results) instead of depending on
    // userRepository.profileFlow's no-replay broadcast landing while this was actively collecting.
    private fun observeCurrentUserProfile() {
        viewModelScope.launch {
            _uiState.map { it.currentUserPubkey }
                .distinctUntilChanged()
                .flatMapLatest { pubkey ->
                    if (pubkey.isNullOrBlank()) flowOf(null) else userRepository.observeProfile(pubkey)
                }
                .collect { profile ->
                    _uiState.update { it.copy(currentUserProfile = profile) }
                }
        }
    }

    /**
     * Proactively hydrates metadata (including NIP-65 relay lists) for the whole follow list, a
     * rotating batch at a time — see FEED_OUTBOX_SWEEP. Unlike schedulePendingRelayWork's
     * on-demand hydration (only authors whose notes are already rendered in the feed), this makes
     * forward progress through every follow over successive ticks, covering authors whose notes
     * may never scroll into view during a session. This is also the only outbox-relay-coverage
     * mechanism this ViewModel runs: once a follow's kind:10002 arrives from a relay, it flows
     * through EventRepositoryImpl straight into UserRepositoryImpl.saveRelayList(), which adds any
     * new outbox relays as "discovered" relays right there — no separate discovery pass needed.
     */
    private fun observeFollowedAuthorOutboxDiscovery() {
        viewModelScope.launch {
            feedStateMergeCoordinator.followedPubkeysFlow
                .debounce(5_000L)
                .collect { followedPubkeys ->
                    // Kick off hydration for the first batch immediately — no reason to wait a
                    // full OUTBOX_DISCOVERY_RETRY_INTERVAL_MS before the very first sweep.
                    sweepFollowedAuthorProfilesForDiscovery(followedPubkeys)
                }
        }
        viewModelScope.launch {
            while (true) {
                delay(OUTBOX_DISCOVERY_RETRY_INTERVAL_MS)
                sweepFollowedAuthorProfilesForDiscovery(feedStateMergeCoordinator.followedPubkeysFlow.first())
            }
        }
    }

    private suspend fun sweepFollowedAuthorProfilesForDiscovery(followedPubkeys: Set<String>) {
        if (!activeFeedFilter.scopeToFollows) return
        val remaining = followedPubkeys - feedEngagementSchedulingCoordinator.outboxSweepCursor
        if (remaining.isEmpty()) return

        if (feedEngagementSchedulingCoordinator.outboxSweepStartedAtMs == 0L) {
            feedEngagementSchedulingCoordinator.outboxSweepStartedAtMs = System.currentTimeMillis()
        }

        val batch = prioritizeOutboxSweepOrder(remaining, feedEngagementSchedulingCoordinator.recentlyVisibleAuthors)
            .take(OUTBOX_SWEEP_BATCH_SIZE)
            .toSet()
        feedEngagementSchedulingCoordinator.outboxSweepCursor = feedEngagementSchedulingCoordinator.outboxSweepCursor + batch
        val nonFresh = feedEngagementSchedulingCoordinator.filterNonFreshPubkeys(batch)
        if (nonFresh.isEmpty()) return

        logger.d {
            val elapsedMs = System.currentTimeMillis() - feedEngagementSchedulingCoordinator.outboxSweepStartedAtMs
            "Outbox sweep: hydrating ${nonFresh.size} more followed authors " +
                "(${feedEngagementSchedulingCoordinator.outboxSweepCursor.size}/${followedPubkeys.size} swept this session, " +
                "${elapsedMs}ms since sweep started)"
        }
        eventRepository.subscribeChannel(
            NostrChannels.FEED_OUTBOX_SWEEP,
            buildProfileHydrationRequestsUseCase(
                authors = nonFresh,
                chunkSize = OUTBOX_SWEEP_BATCH_SIZE,
                perAuthorLimit = 60,
                // This sweep's whole job is outbox *relay-list* discovery (see FEED_OUTBOX_SWEEP's
                // doc comment) — restricting to kind:10002 avoids re-requesting the full profile
                // metadata set (0/3/10000/10050) that FEED_PROFILES_ONDEMAND/PROFILE_LOOKUP-style
                // fetches already cover reactively once an author's notes are actually visible.
                restrictToKinds = setOf(Event.KIND_RELAY_LIST_METADATA)
            )
        )
    }

    private fun observePinnedEventsForCurrentSession() {
        viewModelScope.launch {
            userPreferences.getPublicKeyFlow()
                .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
                .distinctUntilChanged()
                .flatMapLatest { ownerPubkey ->
                    if (ownerPubkey == null) flowOf(null) else pinListRepository.getPinList(ownerPubkey)
                }
                .collect { pinList ->
                    _uiState.update { it.copy(pinnedEventIds = pinList?.pinnedEventIds.orEmpty()) }
                }
        }
    }

    private fun observeUserSessionState() {
        viewModelScope.launch {
            userPreferences.getPublicKeyFlow()
                .collect { sessionPubkey ->
                    val normalizedPubkey = sessionPubkey?.takeIf { it.length == 64 }
                    val namespace = normalizedPubkey?.take(12)?.lowercase() ?: "anon"
                    eventRepository.setSubscriptionNamespace(namespace)

                    // currentUserProfile itself is populated reactively by observeCurrentUserProfile()
                    // once currentUserPubkey below changes — this only needs to set the pubkey.
                    _uiState.update { it.copy(currentUserPubkey = normalizedPubkey) }

                    eventRepository.activateUserSession(normalizedPubkey, activeFeedFilter)
                }
        }
    }

    private fun observeTorRuntimeState() {
        viewModelScope.launch {
            torRuntimeController.state.collect { state ->
                val ready = state.ready
                _uiState.update { ui ->
                    ui.copy(isTorConnected = ready, torStatus = state.status.name)
                }
                if (ready) {
                    // When Tor becomes ready, attempt to enrich exit info
                    fetchTorExitInfo()
                }
            }
        }
    }

    fun refreshTorStatus() {
        fetchTorExitInfo()
    }

    private fun initializeEventStream() {
        viewModelScope.launch {
            try {
                // Get relays
                combine(
                    relayRepository.getAllRelays(),
                    eventRepository.observeConnectedRelayUrls()
                ) { relays, connectedRelayUrls ->
                    // Normalized comparison — connectedRelayUrls is keyed by whatever relayUrl
                    // string was captured at connect time (UmbraNostrClient), which can differ
                    // from the Room-sourced relay.url in case/trailing-slash/whitespace. An
                    // exact-string match here would undercount connected relays.
                    latestRelays = relays
                    val normalizedConnected = connectedRelayUrls.mapTo(HashSet()) { normalizeRelayUrl(it) }
                    // isReadActive/isWriteActive now exclusively reflect a genuine kind:10002
                    // declaration (see EventRepositoryImpl.canApplyChannelToRelay's doc comment) —
                    // a discovered relay never carries one of its own despite being just as
                    // connected and in active use for feed/inbox reads, so it must be counted via
                    // isDiscovered too or a connected discovered-only pool (e.g. right after first
                    // login, before any kind:10002 exists) shows 0 relays in the feed top bar.
                    val enabledRelays = relays.filter { it.isEnabled && (it.isReadActive || it.isWriteActive || it.isDiscovered) }
                    val connectedEnabledCount = enabledRelays.count { relay ->
                        normalizedConnected.contains(normalizeRelayUrl(relay.url))
                    }
                    connectedEnabledCount
                }
                    .onEach { connectedEnabledCount ->
                        _uiState.update {
                            it.copy(
                                relayCount = connectedEnabledCount,
                                isConnected = connectedEnabledCount > 0,
                                isLoading = false,
                                errorMessage = null,
                                errorRelayId = null
                            )
                        }
                    }
                    .launchIn(this)

                // Get active feed filters (initial snapshot + live updates) and merge
                val activeFilters = feedRepository.getActiveFilters().first()
                val merged = feedStateMergeCoordinator.mergeFilters(activeFilters)
                activeFeedFilter = merged

                // Hand off channel management to the data layer
                eventRepository.activateUserSession(_uiState.value.currentUserPubkey, merged)
                observeActiveFeedFilterChanges()

                // Subscribe to events (all kinds — ViewModel filters what it stores). .conflate()
                // is safe here specifically because every event already passed through
                // EventRepositoryImpl.subscribeToEvents()'s own durable-persistence transform
                // upstream of this point — this collection only drives a cosmetic "clear the
                // loading banner" update below, so dropping intermediate values under a burst is
                // fine; nothing here is the last chance an event gets saved.
                eventRepository.subscribeToEvents(emptyList())
                    .conflate()
                    .distinctUntilChanged { old, new ->
                        old.id == new.id &&
                            old.kind == new.kind &&
                            old.pubkey == new.pubkey &&
                            old.createdAt == new.createdAt
                    }
                        .onEach {
                        // EventRepository already persists everything (profiles included — see
                        // its subscribeToEvents()) regardless of whether this ViewModel is alive
                        // to observe it; Room's notesFlow JOIN re-emits automatically. This
                        // collection only exists to clear the initial loading/error banner once
                        // the stream is confirmed live.
                        _uiState.update { state ->
                            val clearNetworkBanner = relayIssueBannerCoordinator.shouldClearNetworkBanner(state)
                            if (!clearNetworkBanner && !state.isLoading) return@update state
                            state.copy(
                                isLoading    = false,
                                errorMessage = if (clearNetworkBanner) null else state.errorMessage,
                                errorRelayId = if (clearNetworkBanner) null else state.errorRelayId
                            )
                        }
                    }
                    .flowOn(Dispatchers.Default)
                    .launchIn(this)

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = UiMessage.ResWithArgs(
                            R.string.error_feed_initialization,
                            e.message ?: ""
                        ),
                        errorRelayId = null,
                        isLoading = false,
                        isConnected = false
                    )
                }
            }
        }
    }

    // scheduleEngagementSubscription/schedulePendingRelayWork/scheduleOutboxDiscoveryAcceleration/
    // scheduleProfileHydration/filterNonFreshPubkeys/scheduleProfileWatch/
    // resetRequestedProfileAuthors all moved to FeedEngagementSchedulingCoordinator.kt —
    // see feedEngagementSchedulingCoordinator's declaration above for the wiring.

    private fun observeActiveFeedFilterChanges() {
        activeFilterJob?.cancel()
        activeFilterJob = viewModelScope.launch {
            // Re-run whenever the active filters change OR the follow list changes — both can
            // affect which authors the follows-scoped relay REQ should ask for.
            combine(
                feedRepository.getActiveFilters().distinctUntilChanged(),
                feedStateMergeCoordinator.followedPubkeysFlow
            ) { filters, followedPubkeys -> filters to followedPubkeys }
                .collect { (filters, followedPubkeys) ->
                    val merged = feedStateMergeCoordinator.mergeFilters(filters)
                    activeFeedFilter = merged
                    feedEngagementSchedulingCoordinator.resetRequestedProfileAuthors()
                    configureReqChannels(merged, followedPubkeys)
                }
        }
    }

    // matchesFilter/mergeFilters moved to FeedStateMergeCoordinator.kt —
    // mergeFilters is exposed there as an internal method this facade still calls directly (see
    // above and initializeEventStream()).

    private fun configureReqChannels(feedFilter: FeedFilter, followedPubkeys: Set<String> = emptySet()) {
        val pubkey = userPreferences.getPublicKey()
        val authors = if (feedFilter.scopeToFollows) followedPubkeys else emptySet()
        eventRepository.activateUserSession(pubkey, feedFilter, authors)
    }

    /**
     * Reacts to an event (NIP-25). [content] defaults to "+"; pass a Unicode emoji or a
     * ":shortcode:" (with the matching [emoji]) for a custom reaction.
     */
    fun likeEvent(event: Event, content: String = "+", emoji: CustomEmoji? = null): Boolean {
        if (!userPreferences.canSignWithAmber()) {
            _uiState.update {
                it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_react), errorRelayId = null) }
            return false
        }
        val eventId = event.id
        val currentInteraction = _uiState.value.interactions[eventId] ?: EventInteraction(eventId)
        val newInteraction = currentInteraction.copy(liked = !currentInteraction.liked)
        val eventJson = NostrEventBuilder.reaction(event, content, emoji)
        interactionActionsCoordinator.requestSignAndPublish(eventJson, userPreferences.getPublicKey(), onSigned = {
            _uiState.update { state ->
                state.copy(interactions = state.interactions + (eventId to newInteraction))
            }
        })
        return true
    }

    fun addReactionEmoji(emoji: ReactionEmoji) {
        viewModelScope.launch { reactionEmojiRepository.add(emoji) }
    }

    fun removeReactionEmoji(key: String) {
        viewModelScope.launch { reactionEmojiRepository.remove(key) }
    }

    fun repostEvent(event: Event) {
        if (!userPreferences.canSignWithAmber()) {
            _uiState.update {
                it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_repost), errorRelayId = null) }
            return
        }
        val eventId = event.id
        val currentInteraction = _uiState.value.interactions[eventId] ?: EventInteraction(eventId)
        val newInteraction = currentInteraction.copy(shared = true)
        val eventJson = NostrEventBuilder.repost(event)
        interactionActionsCoordinator.requestSignAndPublish(eventJson, userPreferences.getPublicKey(), onSigned = {
            _uiState.update { state ->
                state.copy(interactions = state.interactions + (eventId to newInteraction))
            }
        })
    }

    private val _shareUrlEffect = MutableSharedFlow<String>()
    val shareUrlEffect: SharedFlow<String> = _shareUrlEffect.asSharedFlow()

    fun shareEvent(event: Event) {
        viewModelScope.launch {
            _shareUrlEffect.emit(interactionActionsCoordinator.buildShareUrl(event.id))
        }
    }

    fun getEventJson(event: Event): String {
        return interactionActionsCoordinator.getEventJson(event)
    }

    /**
     * Publish a signed event to relays
     */
    fun publishSignedEvent(signedEventJson: String) {
        interactionActionsCoordinator.publishSignedEvent(signedEventJson, onFailure = {
            _uiState.update {
                it.copy(errorMessage = UiMessage.Res(R.string.error_publish_failed), errorRelayId = null) }
        })
    }

    /**
     * Reply to an event (NIP-10)
     */
    fun replyToEvent(event: Event) {
        logger.d { "Reply to event: ${event.id.take(8)}" }

        viewModelScope.launch {
            try {
                _uiState.update { state ->
                    val interaction = state.interactions[event.id] ?: EventInteraction(event.id)

                    state.copy(
                        interactions = state.interactions + (event.id to interaction.copy(replied = true))
                    )
                }
            } catch (e: Exception) {
                logger.d { "Error replying to event: ${scrubThrowableMessageForLogs(e)}" }
            }
        }
    }

    fun deleteEvent(event: Event) {
        val currentUserPubkey = userPreferences.getPublicKey()?.lowercase() ?: return
        interactionActionsCoordinator.deleteEvent(
            event = event,
            currentUserHex = currentUserPubkey,
            onCacheRemoveFailure = {
                _uiState.update { state -> state.copy(errorMessage = UiMessage.Res(R.string.error_delete_note_failed), errorRelayId = null) }
            }
        )
    }

    /**
     * Mute is a global, NIP-51-published action (kind 10000) so it's synced across the owner's
     * clients and applied to the feed regardless of which filter is active (see
     * syncedMutedPubkeysFlow). We also mirror it into the active filter's local mutedPubkeys,
     * matching requestSignAndPublish's convention of committing state only after Amber confirms
     * the signature (via its onSigned callback).
     */
    fun muteUser(pubkey: String) {
        if (!userPreferences.canSignWithAmber()) {
            _uiState.update {
                it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish), errorRelayId = null)
            }
            return
        }
        val target = pubkey.lowercase()
        viewModelScope.launch {
            if (target in muteListRepository.getCurrentMutedPubkeys()) return@launch

            interactionActionsCoordinator.requestSignAndPublish(
                buildEventJson = {
                    NostrEventBuilder.muteList(muteListRepository.getCurrentMutedPubkeys() + target)
                },
                currentUserHex = userPreferences.getPublicKey(),
                onSigned = {
                    interactionActionsCoordinator.applyMuteChange(target, mute = true)
                    interactionActionsCoordinator.mirrorMuteIntoActiveFilter(target, mute = true) {
                        feedRepository.getFilterById(activeFeedFilter.id)
                    }
                    _uiState.update {
                        it.copy(errorMessage = UiMessage.Res(R.string.user_muted_success), errorRelayId = null)
                    }
                }
            )
        }
    }

    /**
     * Toggle pin state for [event] (NIP-51 pin list, kind 10001) — global, network-synced,
     * following the same requestSignAndPublish convention as muteUser/likeEvent.
     */
    fun togglePin(event: Event) {
        if (!userPreferences.canSignWithAmber()) {
            _uiState.update {
                it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish), errorRelayId = null)
            }
            return
        }
        val eventId = event.id.lowercase()
        val wasPinned = _uiState.value.pinnedEventIds.contains(eventId)
        viewModelScope.launch {
            interactionActionsCoordinator.requestSignAndPublish(
                buildEventJson = {
                    val currentPinned = pinListRepository.getCurrentPinnedEventIds()
                    NostrEventBuilder.pinList(if (wasPinned) currentPinned - eventId else currentPinned + eventId)
                },
                currentUserHex = userPreferences.getPublicKey(),
                onSigned = {
                    interactionActionsCoordinator.applyPinChange(eventId, pin = !wasPinned)
                    _uiState.update {
                        it.copy(
                            errorMessage = UiMessage.Res(
                                if (wasPinned) R.string.note_unpinned_success else R.string.note_pinned_success
                            ),
                            errorRelayId = null
                        )
                    }
                }
            )
        }
    }

    /**
     * Get interaction state for event
     */
    fun getInteraction(eventId: String): EventInteraction {
        return feedState.value.interactions[eventId] ?: EventInteraction(eventId)
    }

        /**
         * Load events older than the oldest currently cached note.
         * Uses a backward sliding time window (7 days per page).
         * New events arrive via the same cached-events flow.
         */
    fun loadOlderFeed() {
        val state = feedState.value
        if (state.isLoading || state.isLoadingMore || state.olderNotesExhausted) return

        val oldest = state.events.minOfOrNull { it.createdAt } ?: return
        val now = System.currentTimeMillis()
        val decision = decideLoadOlderFeed(
            oldest = oldest,
            lastAnchor = state.lastOlderAnchor,
            lastLoadMoreAtMs = lastLoadOlderAtMs,
            nowMs = now,
            sameAnchorCooldownMs = LOAD_OLDER_SAME_ANCHOR_COOLDOWN_MS,
            differentAnchorCooldownMs = LOAD_OLDER_DIFFERENT_ANCHOR_COOLDOWN_MS,
            maxLookbackSecs = LOAD_OLDER_MAX_LOOKBACK_SECS
        )
        if (decision.isExhausted) {
            _uiState.update { it.copy(olderNotesExhausted = true) }
            return
        }
        if (!decision.shouldFetch) return
        lastLoadOlderAtMs = now

        _uiState.update {
            it.copy(isLoadingMore = true, lastOlderAnchor = oldest)
        }

        // Increase the render window so older rows are included in the next emission — capped
        // well below MAX_IN_MEMORY_EVENT_CACHE (EventRepositoryImpl, currently 100k) so scrolling
        // can grow deep into whatever's already in the in-memory pool before loadOlderEvents()
        // needs to fall back to a fresh relay REQ.
        _displayLimit.value = (_displayLimit.value + 200).coerceAtMost(10000)

        eventRepository.loadOlderEvents(CHANNEL_FEED, oldest)
        logger.d { "Loading older feed events before timestamp $oldest" }

        // Fallback: stop spinner even if no older events arrive for this page window.
        loadOlderTimeoutJob?.cancel()
        loadOlderTimeoutJob = viewModelScope.launch {
            delay(LOAD_OLDER_TIMEOUT_MS)
            _uiState.update { current ->
                if (current.lastOlderAnchor == oldest) {
                    current.copy(isLoadingMore = false)
                } else {
                    current
                }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update {
            it.copy(errorMessage = null, errorRelayId = null) }
    }

    fun searchNotes(query: String) {
        val normalizedQuery = query.trim()
        searchJob?.cancel()

        if (normalizedQuery.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch {
            eventRepository.searchNotes(normalizedQuery).collectLatest { results ->
                _searchResults.value = results

                // Search results never go through computedFeedFlow's own author-profile derivation
                // (that only covers visible top-level feed notes), so without this a search-only
                // author's avatar/name never renders even after their profile is fetched — same
                // fix, same reuse of quotedAuthorProfiles, as prefetchViewportImages above.
                val searchAuthorPubkeys = results.map { it.pubkey }.distinct()
                if (searchAuthorPubkeys.isNotEmpty()) {
                    val searchAuthorProfiles = userRepository.getProfiles(searchAuthorPubkeys).associateBy { it.pubkey.lowercase() }
                    if (searchAuthorProfiles.isNotEmpty()) {
                        _uiState.update { it.copy(quotedAuthorProfiles = it.quotedAuthorProfiles.mergeBounded(searchAuthorProfiles)) }
                    }
                }
            }
        }
    }

    /**
     * Tears down the search subscription — called when the search panel itself closes, not on
     * every query change. searchNotes() reuses one stable channel per query (no auto EOSE/timeout
     * close, see EventRepositoryImpl.searchNotes) precisely so it keeps absorbing slower relays'
     * results for as long as the panel is open; this is the only thing that should end it.
     */
    fun closeSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        eventRepository.clearChannel(NostrChannels.SEARCH)
    }


    fun canSignEvents(): Boolean {
        return interactionActionsCoordinator.canSignEvents()
    }

    // The status dot reflects Orbot's own connection state exclusively (TorRuntimeState.ready,
    // driven by Orbot's STATUS broadcast — see observeTorRuntimeState()), never the result of
    // this exit-info lookup: check.torproject.org is an extra, best-effort enrichment (exit
    // IP/country) fetched over that same proxy, and its own network flakiness (slow relay,
    // transient timeout) isn't evidence Orbot itself is disconnected — flipping the dot red on
    // that failure was a false positive independent of Orbot's real state.
    private fun fetchTorExitInfo() {
        viewModelScope.launch {
            if (!TorProxyConfig.isReady) return@launch

            checkTorStatusUseCase().onSuccess { result ->
                _uiState.update {
                    it.copy(
                        torExitIp = result.exitIp,
                        torExitCountry = result.countryCode,
                        errorMessage = null,
                        errorRelayId = null
                    )
                }
            }
        }
    }

    fun prefetchViewportImages(events: List<Event>, firstVisibleIndex: Int, visibleCount: Int) {
        if (events.isEmpty() || visibleCount <= 0) return

        val snapshotEvents = events.toList()
        viewportPrefetchJob?.cancel()
        viewportPrefetchJob = viewModelScope.launch(Dispatchers.Default) {
            val imageUrlsToKeep = collectViewportImagePrefetchUrls(
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                lookAheadItems = 4,
                maxUrls = 12
            )
            if (imageUrlsToKeep.isNotEmpty()) {
                imagePrefetcher.prefetchWindowUrls(scopeTag = feedViewportPrefetchScope, urls = imageUrlsToKeep)
            }

            val urlsToKeep = collectViewportHttpPrefetchUrls(
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                lookAheadItems = 6,
                maxUrls = 12,
                includeImages = false
            )
            if (urlsToKeep.isNotEmpty()) {
                urlPrefetcher.prefetchWindowUrls(scopeTag = feedViewportUrlPrefetchScope, urls = urlsToKeep)
            }

            val alreadyKnownIds = snapshotEvents.mapTo(HashSet()) { it.id } + _uiState.value.resolvedQuotedEvents.keys
            val newlyResolvedQuotes = resolveViewportQuotedEvents(
                eventRepository = eventRepository,
                trackReferencedAuthorUseCase = trackReferencedAuthorUseCase,
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                alreadyKnownIds = alreadyKnownIds
            )
            if (newlyResolvedQuotes.isNotEmpty()) {
                _uiState.update { it.copy(resolvedQuotedEvents = it.resolvedQuotedEvents.mergeBounded(newlyResolvedQuotes)) }

                // Quoted-note authors aren't necessarily authors of any visible top-level note,
                // so `profiles` (re-derived from computedFeedFlow every emission) never covers
                // them on its own — fetch (cache-or-Room, no network unless actually missing)
                // and merge explicitly, same fix as ThreadViewModel.prefetchViewportImages.
                val quotedAuthorPubkeys = newlyResolvedQuotes.values.map { it.pubkey }.distinct()
                // Lowercased for symmetry with computed.profiles (keyed by event.pubkey.lowercase()
                // in computedFeedFlow) — currently harmless since UserProfile.pubkey is itself
                // always lowercase by construction, but keying this merge the same way the rest of
                // `profiles` is keyed removes the implicit assumption instead of relying on it.
                val quotedAuthorProfiles = userRepository.getProfiles(quotedAuthorPubkeys).associateBy { it.pubkey.lowercase() }
                if (quotedAuthorProfiles.isNotEmpty()) {
                    _uiState.update { it.copy(quotedAuthorProfiles = it.quotedAuthorProfiles.mergeBounded(quotedAuthorProfiles)) }
                }
            }

            requestViewportMentionedProfiles(
                userRepository = userRepository,
                trackReferencedAuthorUseCase = trackReferencedAuthorUseCase,
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount
            )

            val viewportEventIds = collectViewportEventIds(
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                lookAheadItems = 8,
                maxIds = 80
            )
            val oldestVisibleCreatedAt = collectViewportOldestCreatedAt(
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                lookAheadItems = 8
            )
            feedEngagementSchedulingCoordinator.scheduleEngagementSubscription(viewportEventIds, oldestVisibleCreatedAt)
        }
    }

    private fun resetViewportPrefetchScope() {
        imagePrefetcher.resetScope(scopeTag = feedViewportPrefetchScope)
        urlPrefetcher.resetScope(scopeTag = feedViewportUrlPrefetchScope)
    }

    /**
     * Get cached URL metadata (captured during prefetch)
     */
    fun getUrlMetadata(url: String) = urlPrefetcher.getMetadata(url)

    override fun onCleared() {
        feedEngagementSchedulingCoordinator.cancelScheduledWork()
        activeFilterJob?.cancel()
        viewportPrefetchJob?.cancel()
        feedEngagementSchedulingCoordinator.resetRequestedProfileAuthors()
        eventRepository.setChannelOverlay(CHANNEL_FEED, emptyList())
        eventRepository.clearChannel(CHANNEL_METADATA_HYDRATION)
        resetViewportPrefetchScope()
    }
}


