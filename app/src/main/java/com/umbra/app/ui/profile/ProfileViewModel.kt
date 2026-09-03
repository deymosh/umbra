@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.umbra.app.ui.profile

import com.umbra.app.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.crypto.normalizePubkey
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.model.PendingRepost
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip01.NostrValidation
import com.umbra.app.domain.nip01.isTimestampFromFuture
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.repository.ReactionEmojiRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.DeleteNoteUseCase
import com.umbra.app.domain.usecase.RemoveDeletedNoteFromCacheUseCase
import com.umbra.app.domain.usecase.BackfillProfileUseCase
import com.umbra.app.domain.usecase.ResolveProfileRelayHintsUseCase
import com.umbra.app.domain.usecase.StopProfileBackfillUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import com.umbra.app.domain.usecase.BuildHydrationAuthorSetUseCase
import com.umbra.app.domain.usecase.BuildEngagementFiltersUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.domain.usecase.BuildEventShareUrlUseCase
import com.umbra.app.domain.media.MediaDataSourceProvider
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.ui.common.InteractionActionsCoordinator
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.ui.common.collectViewportEventIds
import com.umbra.app.ui.common.collectViewportHttpPrefetchUrls
import com.umbra.app.ui.common.collectViewportImagePrefetchUrls
import com.umbra.app.ui.common.futureEventRecheckTicker
import com.umbra.app.ui.common.mergeBounded
import com.umbra.app.ui.common.requestViewportMentionedProfiles
import com.umbra.app.ui.common.resolveViewportQuotedEvents
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.ImagePrefetcher
import com.umbra.app.util.UrlPrefetcher
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.umbra.app.ui.common.ImmutableListSnapshot
import com.umbra.app.ui.common.ImmutableMapSnapshot
import com.umbra.app.ui.common.toImmutableSnapshot
import javax.inject.Inject

@Immutable
data class ProfileRelayStats(
    val total: Int = 0,
    val connected: Int = 0,
    val outboxEnabled: Int = 0,
    val inboxEnabled: Int = 0,
    val dmEnabled: Int = 0,
    val onion: Int = 0,
    val withNip11Info: Int = 0
)

@Immutable
data class ProfileState(
    val profile: UserProfile? = null,
    val notes: List<Event> = emptyList(),
    val totalNotesCount: Int = 0,
    // Null until at least one NIP-45-capable relay has responded — avoids a false "0" flash.
    val followersCount: Int? = null,
    val profiles: ImmutableMapSnapshot<String, UserProfile> = ImmutableMapSnapshot(),
    val replyCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    val reactionCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    val repostCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    /** Event id -> reposter pubkey, for a note in [notes] that arrived via a NIP-18 repost. */
    val repostedByPubkeys: ImmutableMapSnapshot<String, String> = ImmutableMapSnapshot(),
    /** Event id -> the repost event's own created_at, for the repost banner's relative-time label. */
    val repostedAtByEvent: ImmutableMapSnapshot<String, Long> = ImmutableMapSnapshot(),
    /** Event id -> the repost event itself, for the repost banner's overflow menu (same actions as a normal note's menu). */
    val repostEventByEvent: ImmutableMapSnapshot<String, Event> = ImmutableMapSnapshot(),
    /** Reposts known but whose target hasn't resolved yet — rendered as a PendingRepostCard. */
    val pendingReposts: ImmutableListSnapshot<PendingRepost> = ImmutableListSnapshot(),
    val isFollowing: Boolean = false,
    val isFollowActionInFlight: Boolean = false,
    val followedPubkeys: List<String> = emptyList(),
    val followedProfiles: ImmutableMapSnapshot<String, UserProfile> = ImmutableMapSnapshot(),
    val mutedPubkeys: List<String> = emptyList(),
    val pinnedNotes: List<Event> = emptyList(),
    val relays: List<Relay> = emptyList(),
    val relayStats: ProfileRelayStats = ProfileRelayStats(),
    // Target user's published relay lists (NIP-65 kind 10002 + NIP-17 kind 10050)
    val targetOutboxRelays: List<String> = emptyList(),
    val targetInboxRelays: List<String> = emptyList(),
    val targetDmRelays: List<String> = emptyList(),
    val isLoading: Boolean = true,
    // Whether loadMoreNotes() has an older-notes page fetch currently in flight — mirrors
    // FeedState.isLoadingMore, drives the same bottom-of-list spinner in notesFeedSection.
    val isLoadingMore: Boolean = false,
    // True once loadMoreNotes() has paged back to PROFILE_MAX_LOOKBACK_SECS with nothing further
    // found — drives a "no more notes found" row instead of retrying forever.
    val olderNotesExhausted: Boolean = false,
    val errorMessage: UiMessage? = null,
    // Quoted events resolved via viewport prefetch (see prefetchViewportImages) that aren't
    // already part of `notes`/`pinnedNotes` — same rationale as FeedState.resolvedQuotedEvents.
    val resolvedQuotedEvents: ImmutableMapSnapshot<String, Event> = ImmutableMapSnapshot()
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val reactionEmojiRepository: ReactionEmojiRepository,
    private val contactListRepository: ContactListRepository,
    private val muteListRepository: MuteListRepository,
    private val pinListRepository: PinListRepository,
    private val feedRepository: FeedRepository,
    private val relayRepository: RelayRepository,
    private val userPreferences: UserPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val publishSignedEventUseCase: PublishSignedEventUseCase,
    private val mediaDataSourceProvider: MediaDataSourceProvider,
    private val videoCacheDataSourceProvider: VideoCacheDataSourceProvider,
    private val deleteNoteUseCase: DeleteNoteUseCase,
    private val removeDeletedNoteFromCacheUseCase: RemoveDeletedNoteFromCacheUseCase,
    private val backfillProfileUseCase: BackfillProfileUseCase,
    private val stopProfileBackfillUseCase: StopProfileBackfillUseCase,
    private val resolveProfileRelayHintsUseCase: ResolveProfileRelayHintsUseCase,
    private val buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase,
    private val buildHydrationAuthorSetUseCase: BuildHydrationAuthorSetUseCase,
    private val buildEngagementFiltersUseCase: BuildEngagementFiltersUseCase,
    private val trackReferencedAuthorUseCase: TrackReferencedAuthorUseCase,
    private val imagePrefetcher: ImagePrefetcher? = null,
    private val urlPrefetcher: UrlPrefetcher? = null,
    private val buildEventShareUrlUseCase: BuildEventShareUrlUseCase
) : ViewModel() {

    private data class ProfileNotesSnapshot(
        val notes: List<Event>,
        val replyCounts: ImmutableMapSnapshot<String, Int>,
        val reactionCounts: ImmutableMapSnapshot<String, Int>,
        val repostCounts: ImmutableMapSnapshot<String, Int>,
        val repostedByPubkeys: ImmutableMapSnapshot<String, String>,
        val repostedAtByEvent: ImmutableMapSnapshot<String, Long>,
        val repostEventByEvent: ImmutableMapSnapshot<String, Event>,
        // Reposter profiles (this profile's own is already `profile`/`state.profile`, and a
        // repost's original author may be a third party neither this profile nor the signed-in
        // user's follow list — see ProfileScreen's resolveProfileForPubkey, which previously had
        // no source for state.profiles at all).
        val profiles: ImmutableMapSnapshot<String, UserProfile>,
        val pendingReposts: ImmutableListSnapshot<PendingRepost> = ImmutableListSnapshot()
    )

    companion object {
        private const val TAG = "UmbraProfileVM"
        private const val INITIAL_DISPLAY_LIMIT = 50
        private const val PAGE_SIZE = 50
        private const val PROFILE_ENGAGEMENT_LIMIT = 400
        // Defensive ceiling on collectViewportEventIds' window (see
        // scheduleProfileEngagementSubscription's doc comment for why this is viewport-scoped, not
        // a cap on however many notes are loaded) — buildEngagementFiltersUseCase chunks 20 ids
        // per EventFilter, so this bounds the overlay to at most 4 filter chunks on top of the base
        // one even if a very large number of items were ever reported visible at once.
        private const val PROFILE_ENGAGEMENT_MAX_NOTE_IDS = 80
        // Same rationale/value as FeedViewModel.ENGAGEMENT_REFRESH_MIN_INTERVAL_MS: the viewport
        // quiet window + same-id-set check alone still let a continuously-but-slowly-scrolling
        // profile screen re-fire engagement REQs more often than the feed does; this caps it to
        // the same cadence.
        private const val PROFILE_ENGAGEMENT_REFRESH_MIN_INTERVAL_MS = 12_000L
        private const val PROFILE_LOAD_OLDER_WINDOW_SECS = 365L * 24 * 60 * 60L
        private const val PROFILE_LOAD_OLDER_LIMIT = 1000
        private const val PROFILE_LOAD_OLDER_COOLDOWN_MS = 1_200L
        private const val PROFILE_LOAD_OLDER_SAME_ANCHOR_COOLDOWN_MS = 8_000L
        // How far back loadMoreNotes() is willing to page before giving up and showing "no more
        // notes found" instead of retrying forever — see decideLoadMoreNotes's isExhausted.
        private const val PROFILE_MAX_LOOKBACK_SECS = 2L * 365L * 24 * 60 * 60L
        // Backstop that clears isLoadingMore if a page fetch never yields a Room/cache emission
        // (relay never responds/never sends EOSE) — same role as FeedViewModel's LOAD_OLDER_TIMEOUT_MS.
        private const val PROFILE_LOAD_OLDER_TIMEOUT_MS = 18_000L
        private const val PROFILE_VIEWPORT_PREFETCH_SCOPE = "profile-viewport"
        private const val PROFILE_VIEWPORT_URL_PREFETCH_SCOPE = "profile-url-viewport"
    }

    private val logger = UmbraLog.tag(TAG)

    val mediaDataSourceFactory get() = mediaDataSourceProvider.getDataSourceFactory()
    val mediaCacheDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()
    val userRepositoryPublic get() = userRepository

    val reactionEmojis: StateFlow<List<ReactionEmoji>> = reactionEmojiRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val rawPubkeyArg: String = checkNotNull(savedStateHandle["pubkey"])
    val pubkey: String = normalizePubkey(rawPubkeyArg)

    private val _displayLimit = MutableStateFlow(INITIAL_DISPLAY_LIMIT)
    private val _state = MutableStateFlow(ProfileState(isLoading = true))

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
    val state: StateFlow<ProfileState> = _state.asStateFlow()
    private val profileBackfillNotesChannelId = NostrChannels.profileBackfillNotes(pubkey)
    private val profileBackfillMetadataChannelId = NostrChannels.profileBackfillMetadata(pubkey)
    private var lastProfileEngagementKey: String? = null
    private var lastProfileEngagementAtMs: Long = 0L
    private var lastLoadMoreAtMs: Long = 0L
    private var lastLoadOlderAnchor: Long? = null
    private var loadOlderTimeoutJob: Job? = null
    private var viewportPrefetchJob: Job? = null

    // Manually constructed facade delegate (not Hilt-injected) — see EventChannelRouting/
    // RelayIssueBannerCoordinator's precedent. Declared right after _state, not earlier, to avoid
    // a Kotlin forward-property-reference bug: a coordinator field declared before _state would
    // see it as uninitialized at construction time, since property initializers run top-to-bottom.
    // init{} below calls its methods.
    private val profileObserversCoordinator = ProfileObserversCoordinator(
        pubkey = pubkey,
        eventRepository = eventRepository,
        userRepository = userRepository,
        contactListRepository = contactListRepository,
        muteListRepository = muteListRepository,
        pinListRepository = pinListRepository,
        relayRepository = relayRepository,
        userPreferences = userPreferences,
        buildHydrationAuthorSetUseCase = buildHydrationAuthorSetUseCase,
        buildProfileHydrationRequestsUseCase = buildProfileHydrationRequestsUseCase,
        state = _state,
        scope = viewModelScope
    )

    // Manually constructed — its own instance, separate from FeedViewModel's. Owns the
    // sign/publish/mute/pin plumbing shared with FeedViewModel's equivalent methods; toggleMute/
    // togglePin/toggleFollow call this coordinator's requestSignAndPublish directly (mutating
    // repository state only from its onSigned callback), while likeEvent/repostEvent still call
    // this ViewModel's own requestSignEvent/onSignedEventReceived below, since those two never
    // need a commit-after-sign repository mutation of their own.
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

    init {
        // Show cached profile immediately
        viewModelScope.launch {
            userRepository.getProfile(pubkey)?.let { cached ->
                _state.update { it.copy(profile = cached) }
                // NIP-05 verification now automatic via UserRepository.getProfile()
            }
        }

        // observeProfileNotes' reactivity source depends on whose profile this is. When
        // pubkey is the signed-in user, it's a real Room JOIN (EventDao.observeProfileNotes,
        // LEFT JOIN user_profiles) that re-runs automatically as events/profiles change in
        // Room — the SSoT case this comment used to describe unconditionally. For any other
        // profile (the more common case — viewing someone else's), there's no JOIN: it maps
        // over the in-memory _cachedEventsFlow and does an explicit
        // userRepository.getProfiles(...) lookup per emission instead (see
        // EventRepositoryImpl.observeProfileNotes' non-self branch). Increasing _displayLimit
        // re-subscribes with a higher limit either way, to reveal more already-cached/-stored
        // rows.
        viewModelScope.launch {
            _displayLimit.flatMapLatest { limit ->
                eventRepository.observeProfileNotes(pubkey, Event.KIND_TEXT_NOTE, limit)
            }
                // Forces re-evaluation of the isFromFuture()/isTimestampFromFuture() checks below
                // purely on a timer — otherwise a note hidden for being future-dated stays hidden
                // past its own timestamp until some unrelated Room change re-emits this flow.
                .combine(futureEventRecheckTicker()) { result, _ -> result }
                .mapLatest { result ->
                    withContext(Dispatchers.Default) {
                        val noteViews = result.notes.filterNot { n ->
                            n.event.isFromFuture() || (n.repostedAt != null && isTimestampFromFuture(n.repostedAt))
                        }
                        ProfileNotesSnapshot(
                            notes = noteViews.map { it.event },
                            replyCounts = noteViews.associate { it.event.id to it.replyCount }.toImmutableSnapshot(),
                            reactionCounts = noteViews.associate { it.event.id to it.reactionCount }.toImmutableSnapshot(),
                            repostCounts = noteViews.associate { it.event.id to it.repostCount }.toImmutableSnapshot(),
                            repostedByPubkeys = noteViews.mapNotNull { n ->
                                n.repostedByPubkey?.let { n.event.id to it }
                            }.toMap().toImmutableSnapshot(),
                            repostedAtByEvent = noteViews.mapNotNull { n ->
                                n.repostedAt?.let { n.event.id to it }
                            }.toMap().toImmutableSnapshot(),
                            repostEventByEvent = noteViews.mapNotNull { n ->
                                n.repostEvent?.let { n.event.id to it }
                            }.toMap().toImmutableSnapshot(),
                            profiles = noteViews.mapNotNull { n ->
                                n.authorProfile?.let { n.event.pubkey.lowercase() to it }
                            }.toMap().toImmutableSnapshot(),
                            pendingReposts = result.pendingReposts
                                .filterNot { isTimestampFromFuture(it.repostedAt) }
                                .toImmutableSnapshot()
                        )
                    }
                }
                .flowOn(Dispatchers.IO)
                .collect { snapshot ->
                    _state.update { state ->
                        // Older content actually arrived (the new oldest moved past the anchor
                        // loadMoreNotes() captured at fetch-start) — clear the spinner right away
                        // instead of waiting out PROFILE_LOAD_OLDER_TIMEOUT_MS's backstop.
                        val newOldest = snapshot.notes.minOfOrNull { it.createdAt }
                        val olderContentArrived = state.isLoadingMore && newOldest != null &&
                            lastLoadOlderAnchor != null && newOldest < lastLoadOlderAnchor!!
                        state.copy(
                            notes = snapshot.notes,
                            replyCounts = snapshot.replyCounts,
                            reactionCounts = snapshot.reactionCounts,
                            repostCounts = snapshot.repostCounts,
                            repostedByPubkeys = snapshot.repostedByPubkeys,
                            repostedAtByEvent = snapshot.repostedAtByEvent,
                            repostEventByEvent = snapshot.repostEventByEvent,
                            pendingReposts = snapshot.pendingReposts,
                            profiles = snapshot.profiles,
                            isLoading = false,
                            isLoadingMore = if (olderContentArrived) false else state.isLoadingMore
                        )
                    }
                    // Engagement overlay is now scheduled from prefetchViewportImages (viewport-
                    // scoped, see scheduleProfileEngagementSubscription's doc comment) instead of
                    // from here with the full, ever-growing snapshot.noteIds.
                }
        }

        profileObserversCoordinator.observeLocalNotesCount()

        profileObserversCoordinator.observeNip45NoteCounts()
        profileObserversCoordinator.requestNip45NoteCountsOnRelayChanges()
        profileObserversCoordinator.observeNip45FollowersCount()
        profileObserversCoordinator.requestNip45FollowersCountOnRelayChanges()

        val isOwnProfile = isCurrentUserProfile()
        observeProfileUpdates()
        observeFollowState()
        profileObserversCoordinator.observeFollowsForViewedProfile()
        profileObserversCoordinator.observeMutedAuthorsForCurrentSession()
        profileObserversCoordinator.observePinnedNotesForCurrentSession()
        if (isOwnProfile) profileObserversCoordinator.observeRelayStats()
        if (!isOwnProfile) {
            profileObserversCoordinator.observeTargetUserRelayLists()
            // BackfillProfileUseCase (called from loadProfile()) already requests this profile's
            // full metadata set, so just record it as referenced — no need to also trigger
            // TrackReferencedAuthorUseCase's own hydration REQ here, that would be redundant.
            eventRepository.noteReferencedAuthor(pubkey)
        }
        loadProfile()
    }

    /** Increases the visible limit for observeProfileNotes (Room SSoT). */
    fun loadMoreNotes() {
        if (state.value.olderNotesExhausted) return
        val oldest = state.value.notes.minOfOrNull { it.createdAt } ?: return
        val now = System.currentTimeMillis()
        val decision = decideLoadMoreNotes(
            oldest = oldest,
            lastAnchor = lastLoadOlderAnchor,
            lastLoadMoreAtMs = lastLoadMoreAtMs,
            nowMs = now,
            sameAnchorCooldownMs = PROFILE_LOAD_OLDER_SAME_ANCHOR_COOLDOWN_MS,
            differentAnchorCooldownMs = PROFILE_LOAD_OLDER_COOLDOWN_MS,
            maxLookbackSecs = PROFILE_MAX_LOOKBACK_SECS
        )
        if (decision.isExhausted) {
            _state.update { it.copy(olderNotesExhausted = true) }
            return
        }
        if (!decision.shouldFetch) return

        lastLoadMoreAtMs = now
        lastLoadOlderAnchor = oldest
        _state.update { it.copy(isLoadingMore = true) }
        _displayLimit.update { it + PAGE_SIZE }

        // Re-dial relay hints on every retry, not just the initial screen-load one BackfillProfileUseCase
        // already did: that first dial's Tor handshake may not have finished before its page channel's
        // own close window elapsed, and this profile's relay-hints-by-pubkey entry can also gain a new
        // hint after the screen opened. A no-op once the relay is already connected (connectToRelayHints
        // itself guards on that), so this costs nothing on the common case where hints are already live.
        if (!isCurrentUserProfile()) {
            resolveProfileRelayHintsUseCase(pubkey).let { relays ->
                if (relays.isNotEmpty()) eventRepository.connectToRelayHints(relays)
            }
        }

        eventRepository.loadOlderEvents(
            channelId = profileBackfillNotesChannelId,
            untilTimestamp = oldest,
            windowSeconds = PROFILE_LOAD_OLDER_WINDOW_SECS,
            limit = PROFILE_LOAD_OLDER_LIMIT
        )

        // Backstop: clear the spinner if this page fetch never yields a new (older) Room/cache
        // emission — same role as FeedViewModel's loadOlderTimeoutJob. Only clears it if this is
        // still the same attempt (the anchor hasn't moved on since), so a fetch that DID succeed
        // in the meantime isn't clobbered back to false by a stale timeout.
        loadOlderTimeoutJob?.cancel()
        loadOlderTimeoutJob = viewModelScope.launch {
            delay(PROFILE_LOAD_OLDER_TIMEOUT_MS)
            if (lastLoadOlderAnchor == oldest) {
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun prefetchViewportImages(events: List<Event>, firstVisibleIndex: Int, visibleCount: Int) {
        if (events.isEmpty() || visibleCount <= 0) return

        val snapshotEvents = events.toList()
        viewportPrefetchJob?.cancel()
        viewportPrefetchJob = viewModelScope.launch(Dispatchers.Default) {
            val imageUrls = collectViewportImagePrefetchUrls(
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                lookAheadItems = 4,
                maxUrls = 12
            )
            if (imageUrls.isNotEmpty()) {
                imagePrefetcher?.prefetchWindowUrls(scopeTag = PROFILE_VIEWPORT_PREFETCH_SCOPE, urls = imageUrls)
            }

            val urls = collectViewportHttpPrefetchUrls(
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                lookAheadItems = 6,
                maxUrls = 12,
                includeImages = false
            )
            if (urls.isNotEmpty()) {
                urlPrefetcher?.prefetchWindowUrls(scopeTag = PROFILE_VIEWPORT_URL_PREFETCH_SCOPE, urls = urls)
            }

            val alreadyKnownIds = snapshotEvents.mapTo(HashSet()) { it.id } + _state.value.resolvedQuotedEvents.keys
            val newlyResolvedQuotes = resolveViewportQuotedEvents(
                eventRepository = eventRepository,
                trackReferencedAuthorUseCase = trackReferencedAuthorUseCase,
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                alreadyKnownIds = alreadyKnownIds
            )
            if (newlyResolvedQuotes.isNotEmpty()) {
                _state.update { it.copy(resolvedQuotedEvents = it.resolvedQuotedEvents.mergeBounded(newlyResolvedQuotes)) }
            }

            requestViewportMentionedProfiles(
                userRepository = userRepository,
                trackReferencedAuthorUseCase = trackReferencedAuthorUseCase,
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount
            )

            // Viewport-scoped, not "however many notes are loaded": a profile's loaded note count
            // only grows as the user scrolls (via loadMoreNotes' _displayLimit), so capping by
            // load order alone would stop covering older notes the moment more than the cap are
            // loaded — even while they're the ones actually on screen. See
            // scheduleProfileEngagementSubscription's doc comment.
            val viewportNoteIds = collectViewportEventIds(
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                lookAheadItems = 8,
                maxIds = PROFILE_ENGAGEMENT_MAX_NOTE_IDS
            )
            scheduleProfileEngagementSubscription(viewportNoteIds)
        }
    }

    // Layers engagement filters onto profileBackfillNotesChannelId as an overlay
    // (setChannelOverlay) rather than a separate subscribeChannel() — same pattern as
    // FeedViewModel.scheduleEngagementSubscription, sent together as one subscription instead of
    // spending a second slot alongside the profile's own standing notes channel.
    //
    // Driven by the viewport (prefetchViewportImages, itself scroll-driven — see ProfileScreen's
    // LaunchedEffect over listState), not by every note ever loaded into the screen's list: an
    // uncapped/load-order-capped version of this used to accumulate one EventFilter chunk per 20
    // note ids as the user scrolled a profile with a long history, observed up to ~21 filters on a
    // single subscription — past what many relays accept, and a source of scroll lag/crashes.
    // [noteIds] is expected to already be viewport-bounded by the caller; PROFILE_ENGAGEMENT_MAX_NOTE_IDS
    // below is a defensive cap, not the primary windowing mechanism.
    private fun scheduleProfileEngagementSubscription(noteIds: List<String>) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastProfileEngagementAtMs < PROFILE_ENGAGEMENT_REFRESH_MIN_INTERVAL_MS) return

        val validIds = NostrValidation.validate64HexSet(noteIds).toList()
        if (validIds.isEmpty()) {
            lastProfileEngagementKey = null
            lastProfileEngagementAtMs = nowMs
            eventRepository.setChannelOverlay(profileBackfillNotesChannelId, emptyList())
            return
        }

        val cappedIds = validIds.take(PROFILE_ENGAGEMENT_MAX_NOTE_IDS)
        val key = cappedIds.joinToString(",")
        if (key == lastProfileEngagementKey) return
        lastProfileEngagementKey = key
        lastProfileEngagementAtMs = nowMs

        val filters = buildEngagementFiltersUseCase(
            eventIds = cappedIds,
            limit = PROFILE_ENGAGEMENT_LIMIT
        )

        eventRepository.setChannelOverlay(profileBackfillNotesChannelId, filters)
    }

    // Room-backed rather than userRepository.profileFlow (a no-replay broadcast) — same fix
    // FeedViewModel.observeCurrentUserProfile already applies for the drawer avatar: profileFlow
    // only reaches a collector that's already actively subscribed at the exact moment a profile
    // is saved, so a metadata event landing in the gap between this ViewModel's construction and
    // this collector actually starting was silently missed, leaving the screen stale until some
    // later, unrelated update happened to race a live collection.
    private fun observeProfileUpdates() {
        viewModelScope.launch {
            userRepository.observeProfile(pubkey)
                .filterNotNull()
                .collect { profile ->
                    _state.update { state ->
                        state.copy(profile = profile)
                    }
                }
        }
    }

    private fun observeFollowState() {
        val currentUserPubkey = userPreferences.getPublicKey()?.takeIf { it.length == 64 }?.lowercase() ?: return
        viewModelScope.launch {
            contactListRepository.getContactList(currentUserPubkey).collect { list ->
                val following = list?.followedPubkeys?.contains(pubkey.lowercase()) == true
                _state.update { it.copy(isFollowing = following) }
            }
        }
    }

    fun canSignEvents(): Boolean = interactionActionsCoordinator.canSignEvents()

    fun isCurrentUserProfile(): Boolean =
        userPreferences.getPublicKey()?.equals(pubkey, ignoreCase = true) == true

    fun currentUserPubkey(): String? = userPreferences.getPublicKey()

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

    fun likeEvent(event: Event, content: String = "+", emoji: CustomEmoji? = null): Boolean {
        if (!userPreferences.canSignWithAmber()) return false
        requestSignEvent(
            eventJson = NostrEventBuilder.reaction(event, content, emoji),
            currentUserHex = userPreferences.getPublicKey()
        )
        return true
    }

    fun addReactionEmoji(emoji: ReactionEmoji) {
        viewModelScope.launch { reactionEmojiRepository.add(emoji) }
    }

    fun removeReactionEmoji(key: String) {
        viewModelScope.launch { reactionEmojiRepository.remove(key) }
    }

    fun repostEvent(event: Event) {
        if (!userPreferences.canSignWithAmber()) return
        requestSignEvent(
            eventJson = NostrEventBuilder.repost(event),
            currentUserHex = userPreferences.getPublicKey()
        )
    }

    fun requestSignEvent(eventJson: String, currentUserHex: String? = null) {
        viewModelScope.launch {
            val signedEvent = try {
                amberSignerGateway.signEvent(eventJson, currentUserHex)
            } catch (e: Exception) {
                logger.d { "Error requesting signed event: ${scrubThrowableMessageForLogs(e)}" }
                null
            }
            if (signedEvent != null) {
                onSignedEventReceived(signedEvent)
            }
        }
    }

    private fun onSignedEventReceived(signedEvent: String) {
        publishSignedEvent(signedEvent)
    }

    fun publishSignedEvent(signedEventJson: String) {
        interactionActionsCoordinator.publishSignedEvent(signedEventJson)
    }

    // NOTE: deletion is preserved exactly as-is below — the notes-list removal is unconditional
    // (not gated on Amber confirming the delete signature) and never rolled back on a
    // rejected/failed sign, unlike toggleMute/togglePin/toggleFollow's pending-action-plus-
    // rollback pattern on this same ViewModel. Tracked as a known bug, not fixed here.
    fun deleteEvent(event: Event) {
        val currentUserPubkey = userPreferences.getPublicKey()?.lowercase() ?: return
        interactionActionsCoordinator.deleteEvent(
            event = event,
            currentUserHex = currentUserPubkey,
            onOptimisticApply = {
                _state.update { state -> state.copy(notes = state.notes.filter { it.id != event.id }) }
            },
            onCacheRemoveFailure = {
                _state.update { state -> state.copy(errorMessage = UiMessage.Res(R.string.error_delete_note_failed)) }
            }
        )
    }

    fun muteUser(targetPubkey: String) = toggleMute(targetPubkey, mute = true)

    fun unmuteUser(targetPubkey: String) = toggleMute(targetPubkey, mute = false)

    /**
     * Mute is a global, NIP-51-published action (kind 10000) so it's synced across the owner's
     * clients and survives switching feed filters — unlike a per-filter local exclude. We also
     * mirror it into the currently active feed filter's local mutedPubkeys, matching
     * requestSignAndPublish's convention of committing state only after Amber confirms the
     * signature (via its onSigned callback).
     */
    private fun toggleMute(targetPubkey: String, mute: Boolean) {
        if (!userPreferences.canSignWithAmber()) {
            _state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
            return
        }

        val target = targetPubkey.lowercase()
        viewModelScope.launch {
            interactionActionsCoordinator.requestSignAndPublish(
                buildEventJson = {
                    val currentMuted = muteListRepository.getCurrentMutedPubkeys()
                    NostrEventBuilder.muteList(if (mute) currentMuted + target else currentMuted - target)
                },
                currentUserHex = userPreferences.getPublicKey(),
                onSigned = {
                    interactionActionsCoordinator.mirrorMuteIntoActiveFilter(target, mute) {
                        feedRepository.getActiveFilters().first().firstOrNull()
                    }
                    val result = interactionActionsCoordinator.applyMuteChange(target, mute)
                    if (!result.isSuccess) {
                        _state.update { state ->
                            state.copy(
                                errorMessage = UiMessage.ResWithArgs(
                                    if (mute) R.string.error_mute_author else R.string.error_unmute_author,
                                    result.exceptionOrNull()?.message ?: ""
                                )
                            )
                        }
                    }
                }
            )
        }
    }

    /**
     * Toggle pin state for [event] (NIP-51 pin list, kind 10001) — global, network-synced,
     * following the same commit-after-sign pattern as toggleFollow()/toggleMute(): the pin-list
     * repository state changes only once Amber has actually confirmed the signature.
     */
    fun togglePin(event: Event) {
        if (!userPreferences.canSignWithAmber()) {
            _state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
            return
        }

        val eventId = event.id.lowercase()
        viewModelScope.launch {
            val wasPinned = pinListRepository.isPinned(eventId)

            interactionActionsCoordinator.requestSignAndPublish(
                buildEventJson = {
                    val currentPinned = pinListRepository.getCurrentPinnedEventIds()
                    NostrEventBuilder.pinList(if (wasPinned) currentPinned - eventId else currentPinned + eventId)
                },
                currentUserHex = userPreferences.getPublicKey(),
                onSigned = {
                    val result = interactionActionsCoordinator.applyPinChange(eventId, pin = !wasPinned)
                    if (!result.isSuccess) {
                        _state.update { state ->
                            state.copy(
                                errorMessage = UiMessage.ResWithArgs(
                                    if (wasPinned) R.string.error_unpin_note else R.string.error_pin_note,
                                    result.exceptionOrNull()?.message ?: ""
                                )
                            )
                        }
                    }
                }
            )
        }
    }

    fun toggleFollow() {
        if (!userPreferences.canSignWithAmber()) {
            _state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
            return
        }

        if (pubkey.equals(userPreferences.getPublicKey(), ignoreCase = true)) {
            return
        }

        viewModelScope.launch {
            val wasFollowing = state.value.isFollowing
            _state.update { it.copy(isFollowActionInFlight = true) }

            interactionActionsCoordinator.requestSignAndPublish(
                buildEventJson = {
                    val currentFollowed = contactListRepository.getCurrentFollowedPubkeys()
                    NostrEventBuilder.contactList(if (wasFollowing) currentFollowed - pubkey else currentFollowed + pubkey)
                },
                currentUserHex = userPreferences.getPublicKey(),
                onSigned = {
                    val result = if (wasFollowing) {
                        contactListRepository.unfollow(pubkey)
                    } else {
                        contactListRepository.follow(pubkey)
                    }

                    _state.update { state ->
                        if (result.isSuccess) {
                            state.copy(isFollowActionInFlight = false)
                        } else {
                            state.copy(
                                isFollowing = wasFollowing,
                                errorMessage = UiMessage.Res(R.string.error_follow_action_failed),
                                isFollowActionInFlight = false
                            )
                        }
                    }
                },
                onRejected = {
                    // Amber sign was cancelled/failed — nothing was mutated, so just clear the
                    // in-flight affordance (no repository state to roll back).
                    _state.update { it.copy(isFollowActionInFlight = false) }
                }
            )
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            // BackfillProfileUseCase subscribes metadata+contact-list+relay-list channels
            // and triggers a notes page load â€” no additional channel setup needed here.
            backfillProfileUseCase(pubkey)

            // Stop spinner if no cached events arrive shortly after backfill trigger.
            kotlinx.coroutines.delay(4000)
            _state.update { if (it.isLoading) it.copy(isLoading = false) else it }
        }
    }

    /**
     * Get cached URL metadata (captured during prefetch)
     */
    fun getUrlMetadata(url: String) = urlPrefetcher?.getMetadata(url)

    override fun onCleared() {
        profileObserversCoordinator.cancelScheduledWork()
        viewportPrefetchJob?.cancel()
        // profileBackfillMetadataChannelId also covers kinds 3/10000/10002/10050 (see BackfillProfileUseCase)
        // clearChannel(profileBackfillNotesChannelId) also clears its engagement overlay, if any.
        eventRepository.clearChannel(profileBackfillNotesChannelId)
        eventRepository.clearChannel(profileBackfillMetadataChannelId)
        stopProfileBackfillUseCase(pubkey)
        imagePrefetcher?.resetScope(PROFILE_VIEWPORT_PREFETCH_SCOPE)
        urlPrefetcher?.resetScope(PROFILE_VIEWPORT_URL_PREFETCH_SCOPE)
    }

}

internal fun bestRemoteCount(remoteCounts: Collection<Long>): Int {
    val best = remoteCounts.maxOrNull() ?: 0L
    return best.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Decides whether [ProfileViewModel.loadMoreNotes] should act on this call — a pure cooldown
 * check, deliberately with only ONE outcome (`shouldFetch`) governing both the network retry
 * (`eventRepository.loadOlderEvents`) and the display-window growth (`_displayLimit`) together.
 *
 * They used to be gated separately: the network call ran on this same cooldown, but the display
 * limit only grew when [oldest] differed from [lastAnchor] ("sameAnchor" false) — which created a
 * circular freeze. [oldest] is derived from the *currently limit-bounded* notes list, so if any
 * single call happened to observe the same [oldest] as the previous one (plausible: the prior
 * limit bump hadn't propagated through Room/the in-memory cache yet, given batching/debounce plus
 * Tor round-trip latency), the display limit would stop growing — permanently, since data older
 * than a frozen window can never enter a `LIMIT N` result, so `oldest` could never change again
 * either. Tying both outcomes to the same cooldown-only decision removes that circularity: the
 * cooldown duration still legitimately varies by [oldest]/[lastAnchor] (a longer wait when nothing
 * moved, so relays aren't re-asked the same query every frame), it just no longer separately
 * blocks the UI from surfacing more of what's already cached/persisted.
 *
 * [isExhausted] is a separate, simpler signal: once [oldest] has already been pushed back to (or
 * past) [maxLookbackSecs] before now, further paging is pointless regardless of the cooldown —
 * this is the floor BackfillProfileUseCase/loadOlderEvents' own windowed paging is willing to
 * search, so reaching it means "nothing more within the window we search," not necessarily "this
 * author has no older notes at all." Once true, [shouldFetch] is forced false too so the retry
 * loop stops firing REQs a relay was already asked and answered nothing new for.
 */
internal data class LoadMoreNotesDecision(val shouldFetch: Boolean, val isExhausted: Boolean)

internal fun decideLoadMoreNotes(
    oldest: Long,
    lastAnchor: Long?,
    lastLoadMoreAtMs: Long,
    nowMs: Long,
    sameAnchorCooldownMs: Long,
    differentAnchorCooldownMs: Long,
    maxLookbackSecs: Long
): LoadMoreNotesDecision {
    val sameAnchor = lastAnchor == oldest
    val cooldown = if (sameAnchor) sameAnchorCooldownMs else differentAnchorCooldownMs
    val isExhausted = oldest <= (nowMs / 1000L) - maxLookbackSecs
    val shouldFetch = !isExhausted && nowMs - lastLoadMoreAtMs >= cooldown
    return LoadMoreNotesDecision(shouldFetch = shouldFetch, isExhausted = isExhausted)
}


