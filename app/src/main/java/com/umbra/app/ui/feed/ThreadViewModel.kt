package com.umbra.app.ui.feed

import android.net.Uri
import com.umbra.app.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.repository.ReactionEmojiRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.DeleteNoteUseCase
import com.umbra.app.domain.usecase.RemoveDeletedNoteFromCacheUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.domain.usecase.BuildEventShareUrlUseCase
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.ui.common.UiMessage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Immutable
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.encodeToString
import com.umbra.app.ui.common.ImmutableMapSnapshot
import com.umbra.app.ui.common.toImmutableSnapshot
import javax.inject.Inject

private val HEX_64_REGEX = Regex("^[a-fA-F0-9]{64}$")

@Immutable
data class ThreadState(
    val eventId: String,
    val anchor: Event? = null,
    val events: List<Event> = emptyList(),
    val replyCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    val reactionCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    val repostCounts: ImmutableMapSnapshot<String, Int> = ImmutableMapSnapshot(),
    val profiles: ImmutableMapSnapshot<String, UserProfile> = ImmutableMapSnapshot(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val errorMessage: UiMessage? = null,
    // Quoted events resolved via viewport prefetch (see prefetchViewportImages) that aren't
    // already part of this thread's own event graph — same rationale as
    // FeedState.resolvedQuotedEvents; fixes quoted notes never rendering in the thread view for
    // quotes of notes outside the thread itself.
    val resolvedQuotedEvents: ImmutableMapSnapshot<String, Event> = ImmutableMapSnapshot()
)

@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val reactionEmojiRepository: ReactionEmojiRepository,
    private val userPreferences: UserPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val publishSignedEventUseCase: PublishSignedEventUseCase,
    private val videoCacheDataSourceProvider: VideoCacheDataSourceProvider,
    private val deleteNoteUseCase: DeleteNoteUseCase,
    private val removeDeletedNoteFromCacheUseCase: RemoveDeletedNoteFromCacheUseCase,
    private val trackReferencedAuthorUseCase: TrackReferencedAuthorUseCase,
    private val imagePrefetcher: ImagePrefetcher? = null,
    private val urlPrefetcher: UrlPrefetcher? = null,
    private val buildEventShareUrlUseCase: BuildEventShareUrlUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "UmbraThreadVM"
        private const val THREAD_ROOM_WINDOW = 3000
        private const val THREAD_VIEWPORT_PREFETCH_SCOPE = "thread-viewport"
        private const val THREAD_VIEWPORT_URL_PREFETCH_SCOPE = "thread-url-viewport"
    }

    private val logger = UmbraLog.tag(TAG)

    private val eventRef: String = checkNotNull(savedStateHandle["eventId"])
    private var hasLoadedInitialGraph = false
    private var viewportPrefetchJob: Job? = null
    private val roomSeedEvents = MutableStateFlow<List<Event>>(emptyList())
    private val anchorEventId = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(ThreadState(eventId = eventRef))
    val state: StateFlow<ThreadState> = _state.asStateFlow()
    val threadItems: StateFlow<List<Event>> = state
        .map { it.events }
        .distinctUntilChanged { old, new ->
            old.size == new.size && old.map { it.id } == new.map { it.id }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Cached (not the plain uncached factory) so thread-view playback shares the same disk cache
    // feed/profile/composer already write to/read from - a video already watched in the feed no
    // longer had to be redownloaded just because it's being viewed from its thread.
    val mediaDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()
    val userRepositoryPublic get() = userRepository

    val reactionEmojis: StateFlow<List<ReactionEmoji>> = reactionEmojiRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun canSignEvents(): Boolean = userPreferences.canSignWithAmber()

    fun currentUserPubkey(): String? = userPreferences.getPublicKey()

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private val _shareUrlEffect = MutableSharedFlow<String>()
    val shareUrlEffect: SharedFlow<String> = _shareUrlEffect.asSharedFlow()

    fun shareEvent(event: Event) {
        viewModelScope.launch {
            _shareUrlEffect.emit(buildEventShareUrlUseCase(event.id))
        }
    }

    fun getEventJson(event: Event): String {
        return JsonUtils.PrettyJson.encodeToString(Event.serializer(), event)
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

    fun deleteEvent(event: Event) {
        val currentUserPubkey = userPreferences.getPublicKey()?.lowercase() ?: return
        val eventJson = deleteNoteUseCase(event, currentUserPubkey).getOrElse { return }
        requestSignEvent(eventJson, currentUserPubkey)
        _state.update { current ->
            current.copy(events = current.events.filter { it.id != event.id })
        }
        viewModelScope.launch {
            removeDeletedNoteFromCacheUseCase(event.id)
                .onFailure {
                    _state.update { current ->
                        current.copy(errorMessage = UiMessage.Res(R.string.error_delete_note_failed))
                    }
                }
        }
    }

    fun requestSignEvent(eventJson: String, currentUserHex: String? = null) {
        viewModelScope.launch {
            val signedEvent = try {
                amberSignerGateway.signEvent(eventJson, currentUserHex)
            } catch (e: Exception) {
                logger.d { "Error requesting signed event: ${scrubThrowableMessageForLogs(e)}" }
                null
            }
            if (signedEvent != null) publishSignedEvent(signedEvent)
        }
    }

    fun publishSignedEvent(signedJson: String) {
        viewModelScope.launch {
            publishSignedEventUseCase(signedJson).onFailure { e ->
                logger.d { "Publish failed: ${scrubThrowableMessageForLogs(e)}" }
            }
        }
    }

    init {
        bootstrapFromRoom()
        observeThread()
        observeProfiles()
    }

    private fun bootstrapFromRoom() {
        viewModelScope.launch {
            val anchor = resolveAnchorFromReference(eventRef)
            if (anchor == null) {
                hasLoadedInitialGraph = true
                _state.update { it.copy(anchor = null, events = emptyList(), isLoading = false, notFound = true) }
                return@launch
            }
            anchorEventId.value = anchor.id
            val seedEvents = buildLocalThreadSeed(anchor)
            roomSeedEvents.value = seedEvents
            // viewModelScope defaults to Dispatchers.Main.immediate — without this hop, the
            // DFS/count-building work in processThreadGraph (collectDescendants, distinctBy,
            // associateBy over what can be a large, deep thread) would run directly on the UI
            // thread for the very first, usually-largest graph build when the screen opens.
            // observeThread()'s own ongoing recomputation already does this same hop; this path
            // just hadn't been given it.
            val bootstrappedGraph = withContext(Dispatchers.Default) { processThreadGraph(seedEvents) }
            val roomEvents = bootstrappedGraph.thread
            if (roomEvents.isNotEmpty()) {
                _state.update {
                    it.copy(
                        anchor = bootstrappedGraph.anchor,
                        events = roomEvents,
                        replyCounts = bootstrappedGraph.replyCounts.toImmutableSnapshot(),
                        reactionCounts = bootstrappedGraph.reactionCounts.toImmutableSnapshot(),
                        repostCounts = bootstrappedGraph.repostCounts.toImmutableSnapshot(),
                        profiles = bootstrappedGraph.profileSlice.toImmutableSnapshot(),
                        isLoading = false,
                        notFound = false,
                        eventId = anchor.id
                    )
                }
            }
        }
    }

    private data class ThreadGraphSignature(
        val eventIds: Set<String>,
        val profilePubkeys: Set<String>,
        val engagementChecksum: Int
    )

    private data class ThreadGraphResult(
        val anchor: Event?,
        val thread: List<Event>,
        val replyCounts: Map<String, Int>,
        val reactionCounts: Map<String, Int>,
        val repostCounts: Map<String, Int>,
        val profileSlice: Map<String, UserProfile>,
        val signature: ThreadGraphSignature
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeThread() {
        viewModelScope.launch {
            combine(
                eventRepository.observeRecentEvents(THREAD_ROOM_WINDOW),
                roomSeedEvents,
                anchorEventId,
                // Forces periodic re-processing so a reply hidden by isFromFuture() (see
                // processThreadGraph below) reappears once its timestamp passes, not only when
                // Room happens to emit for an unrelated reason.
                futureEventRecheckTicker()
            ) { roomEvents, seedEvents, _, _ ->
                (seedEvents + roomEvents).distinctBy { it.id }
            }
                .conflate()
                .mapLatest { mergedEvents ->
                    withContext(Dispatchers.Default) {
                        processThreadGraph(mergedEvents)
                    }
                }
                .distinctUntilChanged { old, new -> old.signature == new.signature }
                .collect { result ->
                    trackThreadAuthors(result.thread)
                    _state.update { current ->
                        val isFirstLoad = !hasLoadedInitialGraph
                        if (isFirstLoad && (result.anchor != null || current.events.isEmpty())) {
                            hasLoadedInitialGraph = true
                        }

                        current.copy(
                            anchor = result.anchor,
                            events = result.thread,
                            replyCounts = result.replyCounts.toImmutableSnapshot(),
                            reactionCounts = result.reactionCounts.toImmutableSnapshot(),
                            repostCounts = result.repostCounts.toImmutableSnapshot(),
                            profiles = current.profiles + result.profileSlice,
                            isLoading = !hasLoadedInitialGraph,
                            notFound = hasLoadedInitialGraph && result.anchor == null,
                            eventId = result.anchor?.id ?: current.eventId
                        )
                    }
                }
        }
    }

    /**
     * Tracks every distinct author currently in the thread graph (anchor, ancestors,
     * descendants) so their outbox relays (NIP-65) can be discovered even when they aren't
     * followed — see TrackReferencedAuthorUseCase. Bounded defensively: a thread's author count
     * is normally small, but a pathological fan-out shouldn't be able to fire an unbounded burst
     * of one-shot hydration REQs from a single collect tick.
     */
    private fun trackThreadAuthors(events: List<Event>) {
        events.asSequence()
            .map { it.pubkey }
            .distinct()
            .take(50)
            .forEach { trackReferencedAuthorUseCase(it) }
    }

    private suspend fun resolveAnchorFromReference(rawReference: String): Event? {
        val normalized = Uri.decode(rawReference)
            .trim()
            .removePrefix("nostr://")
            .removePrefix("nostr:")

        // fetchEventById (not getEventById): this is the anchor the user actually navigated to
        // (a tapped link/mention/notification/thread), so a bounded relay wait if it's not
        // already cached is worth it — unlike the ancestor-walk below, which stays cache-only.
        //
        // nevent1/naddr1 (unlike bare note1) can carry relay hints (NIP-19 TLV type 1) — exactly
        // the case this anchor might not be reachable on any already-configured relay yet.
        // discoverRelayHints registers them as discovered relays for future opens/retries
        // (best-effort/async, gated behind the normal debounced reconcile); fetchEventById's own
        // relayHints param additionally dials them directly for *this* wait (see its doc comment)
        // instead of only relying on that debounce to eventually catch up. naddr has no such
        // direct-dial fallback yet — getLatestAddressableEvent stays cache-only.
        return when {
            normalized.startsWith("note1", ignoreCase = true) -> {
                Bech32Encoder.decodeNote(normalized)?.let { eventRepository.fetchEventById(it) }
            }
            normalized.startsWith("nevent1", ignoreCase = true) -> {
                val nevent = Bech32Encoder.decodeNevent(normalized) ?: return null
                userRepository.discoverRelayHints(nevent.relays)
                eventRepository.fetchEventById(nevent.eventId, relayHints = nevent.relays)
            }
            normalized.startsWith("naddr1", ignoreCase = true) -> {
                val naddr = Bech32Encoder.decodeNaddr(normalized) ?: return null
                userRepository.discoverRelayHints(naddr.relays)
                eventRepository.getLatestAddressableEvent(
                    kind = naddr.kind,
                    pubkey = naddr.authorPubkey,
                    identifier = naddr.identifier
                )
            }
            HEX_64_REGEX.matches(normalized) -> eventRepository.fetchEventById(normalized)
            else -> null
        }
    }

    private suspend fun buildLocalThreadSeed(anchor: Event): List<Event> {
        val eventMap = linkedMapOf<String, Event>()
        eventMap[anchor.id] = anchor

        var parentId = anchor.getParentEventId()
        val visitedParents = mutableSetOf<String>()
        while (!parentId.isNullOrBlank() && visitedParents.add(parentId)) {
            val parent = eventRepository.getEventById(parentId) ?: break
            eventMap[parent.id] = parent
            parentId = parent.getParentEventId()
        }

        val descendantIds = mutableSetOf<String>()
        var frontier = eventMap.keys.toSet()

        while (frontier.isNotEmpty()) {
            val referencingEvents = eventRepository.getEventsReferencingIds(frontier.toList())
                .distinctBy { it.id }
            val directReplies = referencingEvents.filter { event ->
                event.kind == Event.KIND_TEXT_NOTE && event.isReply() && frontier.contains(event.getParentEventId())
            }
            val newReplies = directReplies.filter { descendantIds.add(it.id) }
            newReplies.forEach { reply -> eventMap[reply.id] = reply }
            frontier = newReplies.map { it.id }.toSet()
        }

        val threadIds = eventMap.keys.toList()
        val directEngagement = eventRepository.getEventsReferencingIds(threadIds)
            .filter { it.kind == Event.KIND_REACTION || it.kind == Event.KIND_REPOST }
        directEngagement.forEach { engagement -> eventMap.putIfAbsent(engagement.id, engagement) }

        return eventMap.values.toList()
    }

    private suspend fun processThreadGraph(allEvents: List<Event>): ThreadGraphResult {
        val map = allEvents.associateBy { it.id }
        val activeAnchorId = anchorEventId.value
        val anchor = activeAnchorId?.let(map::get)
        if (anchor == null) {
            return ThreadGraphResult(
                anchor = null,
                thread = emptyList(),
                replyCounts = emptyMap(),
                reactionCounts = emptyMap(),
                repostCounts = emptyMap(),
                profileSlice = emptyMap(),
                signature = ThreadGraphSignature(
                    eventIds = emptySet(),
                    profilePubkeys = emptySet(),
                    engagementChecksum = 0
                )
            )
        }

        val parentChain = mutableListOf<Event>()
        var parentId = anchor.getParentEventId()
        val visited = mutableSetOf<String>()
        while (!parentId.isNullOrBlank() && visited.add(parentId)) {
            val parent = map[parentId] ?: break
            parentChain.add(parent)
            parentId = parent.getParentEventId()
        }

        // Replies stream in passively like a feed, so spoofed-future timestamps are filtered the
        // same way — unlike the anchor/parentChain above, which the user explicitly navigated to
        // and must never be hidden just because of a bogus timestamp.
        val descendants = collectDescendants(anchor.id, allEvents).filterNot { it.isFromFuture() }
        val thread = (parentChain.asReversed() + anchor + descendants).distinctBy { it.id }
        val pubkeys = thread.map { it.pubkey }.distinct()
        // Lowercased so this map's keys always match the lowercase event.pubkey it's looked up by
        // (ThreadScreen.kt's state.profiles[event.pubkey] reads) — currently harmless since
        // UserProfile.pubkey is itself always lowercase by construction, but removes the implicit
        // assumption instead of relying on it.
        val profileSlice = userRepository.getProfilesByPubkey(pubkeys)
        val threadIds = thread.map { it.id }
        val replyCounts = mutableMapOf<String, Int>()
        val reactionCounts = mutableMapOf<String, Int>()
        val repostCounts = mutableMapOf<String, Int>()

        eventRepository.getEventsReferencingIds(threadIds)
            .asSequence()
            .distinctBy { it.id }
            .filter { event ->
                event.kind == Event.KIND_REACTION ||
                    event.kind == Event.KIND_REPOST ||
                    (event.kind == Event.KIND_TEXT_NOTE && event.isReply())
            }
            .forEach { event ->
                val targetEventId = event.getParentEventId() ?: event.getTagValue("e") ?: return@forEach
                if (!threadIds.contains(targetEventId)) return@forEach

                when (event.kind) {
                    Event.KIND_REACTION -> reactionCounts[targetEventId] = (reactionCounts[targetEventId] ?: 0) + 1
                    Event.KIND_REPOST -> repostCounts[targetEventId] = (repostCounts[targetEventId] ?: 0) + 1
                    Event.KIND_TEXT_NOTE -> replyCounts[targetEventId] = (replyCounts[targetEventId] ?: 0) + 1
                }
            }

        val engagementChecksum =
            31 * replyCounts.hashCode() + 17 * reactionCounts.hashCode() + repostCounts.hashCode()

        // Reorders only the anchor's direct-reply branches by popularity (replies + likes +
        // reposts on that top-level reply itself), now that counts are known — nested replies
        // within a branch stay chronological (collectDescendants' own DFS order), since ranking
        // deep reply chains by popularity would make them harder to follow, not easier.
        val reorderedDescendants = reorderTopLevelDescendantsByPopularity(
            descendants = descendants,
            anchorId = anchor.id,
            replyCounts = replyCounts,
            reactionCounts = reactionCounts,
            repostCounts = repostCounts
        )
        val finalThread = (parentChain.asReversed() + anchor + reorderedDescendants).distinctBy { it.id }

        return ThreadGraphResult(
            anchor = anchor,
            thread = finalThread,
            replyCounts = replyCounts,
            reactionCounts = reactionCounts,
            repostCounts = repostCounts,
            profileSlice = profileSlice,
            signature = ThreadGraphSignature(
                eventIds = finalThread.map { it.id }.toSet(),
                profilePubkeys = profileSlice.keys,
                engagementChecksum = engagementChecksum
            )
        )
    }

    /**
     * profileFlow is app-wide (every screen's profile fetches land here), but this screen only
     * ever renders this thread's own participants and resolved quoted-note authors — merging in
     * every unrelated profile update elsewhere in the app replaced this thread's state (forcing a
     * recomposition of anything reading it) for no benefit while a thread is open. Participants'
     * and quoted authors' profiles are already populated directly via getProfiles() (see
     * processThreadGraph / prefetchViewportImages' quotedAuthorProfiles fetch); this subscription
     * only needs to keep those already-relevant entries fresh, not adopt new ones.
     */
    private fun observeProfiles() {
        viewModelScope.launch {
            userRepository.profileFlow.collect { profile ->
                val normalizedPubkey = profile.pubkey.lowercase()
                val current = _state.value
                val isRelevant = current.events.any { it.pubkey.equals(normalizedPubkey, ignoreCase = true) } ||
                    current.resolvedQuotedEvents.toMap().values.any { it.pubkey.equals(normalizedPubkey, ignoreCase = true) }
                if (!isRelevant) return@collect

                _state.update {
                    it.copy(profiles = it.profiles + (profile.pubkey to profile))
                }
            }
        }
    }

    private fun collectDescendants(anchorId: String, allEvents: List<Event>): List<Event> {
        val byParent = allEvents
            .filter { it.kind == Event.KIND_TEXT_NOTE && it.isReply() }
            .groupBy { it.getParentEventId() }

        val out = mutableListOf<Event>()
        val visited = mutableSetOf<String>()

        // DFS keeps a reply directly under its parent branch.
        fun walk(parent: String) {
            val children = (byParent[parent] ?: emptyList()).sortedBy { it.createdAt }
            children.forEach { child ->
                if (visited.add(child.id)) {
                    out.add(child)
                    walk(child.id)
                }
            }
        }

        walk(anchorId)

        return out
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
                imagePrefetcher?.prefetchWindowUrls(scopeTag = THREAD_VIEWPORT_PREFETCH_SCOPE, urls = imageUrls)
            }

            val urls = collectViewportHttpPrefetchUrls(
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount,
                lookAheadItems = 8,
                maxUrls = 16,
                includeImages = false
            )
            if (urls.isNotEmpty()) {
                urlPrefetcher?.prefetchWindowUrls(scopeTag = THREAD_VIEWPORT_URL_PREFETCH_SCOPE, urls = urls)
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

                // Quoted-note authors aren't thread participants, so processThreadGraph()'s bulk
                // getProfiles(pubkeys) never covers them, and profileFlow (replay=0) only reaches
                // a collector for hydration that happens to land while it's actively collecting —
                // useless for an author whose kind:0 was already cached before this thread opened.
                // Fetch (cache-or-Room, no network unless actually missing) and merge explicitly
                // so an already-known quoted author's avatar/name render on the first viewport
                // pass, not just when their metadata happens to arrive mid-session.
                val quotedAuthorPubkeys = newlyResolvedQuotes.values.map { it.pubkey }.distinct()
                val quotedAuthorProfiles = userRepository.getProfilesByPubkey(quotedAuthorPubkeys)
                if (quotedAuthorProfiles.isNotEmpty()) {
                    _state.update { it.copy(profiles = it.profiles + quotedAuthorProfiles) }
                }
            }

            requestViewportMentionedProfiles(
                userRepository = userRepository,
                trackReferencedAuthorUseCase = trackReferencedAuthorUseCase,
                events = snapshotEvents,
                firstVisibleIndex = firstVisibleIndex,
                visibleCount = visibleCount
            )
        }
    }

    /**
     * Get cached URL metadata (captured during prefetch)
     */
    fun getUrlMetadata(url: String) = urlPrefetcher?.getMetadata(url)

    override fun onCleared() {
        viewportPrefetchJob?.cancel()
        imagePrefetcher?.resetScope(THREAD_VIEWPORT_PREFETCH_SCOPE)
        urlPrefetcher?.resetScope(THREAD_VIEWPORT_URL_PREFETCH_SCOPE)
    }
}

/**
 * Reorders the top-level branches of [descendants] (each branch = a direct reply to [anchorId]
 * plus its own nested replies, kept contiguous) by descending popularity (replies + likes +
 * reposts on that top-level reply itself — `// TODO: fold in zap sats once available`). Nested
 * replies within a branch stay in their existing (chronological) order — only which branch
 * appears first changes.
 *
 * [descendants] is expected to be [ThreadViewModel]'s own `collectDescendants`'s DFS pre-order
 * output, which already guarantees each top-level branch's events are contiguous (a branch's
 * nested replies always immediately follow their branch root, before the next sibling root) — so
 * branches can be recovered by splitting on "parent == anchorId" without re-walking the reply
 * graph. Top-level (file-scope), not a private method, so it's directly unit-testable without
 * constructing a full ThreadViewModel.
 */
internal fun reorderTopLevelDescendantsByPopularity(
    descendants: List<Event>,
    anchorId: String,
    replyCounts: Map<String, Int>,
    reactionCounts: Map<String, Int>,
    repostCounts: Map<String, Int>
): List<Event> {
    if (descendants.size <= 1) return descendants

    val branches = mutableListOf<MutableList<Event>>()
    descendants.forEach { event ->
        if (event.getParentEventId() == anchorId) {
            branches.add(mutableListOf(event))
        } else {
            (branches.lastOrNull() ?: mutableListOf<Event>().also { branches.add(it) }).add(event)
        }
    }

    fun popularity(event: Event): Int =
        (replyCounts[event.id] ?: 0) + (reactionCounts[event.id] ?: 0) + (repostCounts[event.id] ?: 0)

    return branches
        .sortedWith(
            compareByDescending<List<Event>> { popularity(it.first()) }
                .thenByDescending { it.first().createdAt }
        )
        .flatten()
}

