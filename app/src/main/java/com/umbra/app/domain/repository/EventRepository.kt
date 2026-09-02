package com.umbra.app.domain.repository

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.model.EventCacheStats
import com.umbra.app.domain.model.FeedNotesResult
import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayRequestInfo
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing Nostr events
 * Handles fetching events from relays, caching, and filtering
 */
interface EventRepository {
    /**
     * Marks an author as priority for persistence. Events from pinned authors bypass
     * feed-only persistence exclusions so profile backfill works even in curated feeds.
     */
    fun pinProfileAuthorForPersistence(pubkey: String)

    /**
     * Removes a previously pinned author for persistence bypass.
     */
    fun unpinProfileAuthorForPersistence(pubkey: String)

    /**
     * Records that content from [pubkey] was fetched/viewed (quoted note author, thread author,
     * mentioned profile, viewed profile screen) — not necessarily a followed author. Feeds a
     * bounded, most-recently-used set purely for dedup: returns true the first time this pubkey
     * is recorded (not yet evicted from the bounded set), so callers (see
     * TrackReferencedAuthorUseCase) know not to re-request hydration for a pubkey they've already
     * asked about this session. Default is a no-op (returns false); only the real repository
     * implementation tracks anything — test doubles don't need to change.
     */
    fun noteReferencedAuthor(pubkey: String): Boolean = false

    /**
     * True once [pubkey]'s NIP-65 relay list has been fetched and folded into the current
     * session's precise relay routing (see EventRepositoryImpl's `authorsWithKnownOutbox`) — i.e.
     * this author's notes are being requested from their real write relays, not just an unscoped
     * broadcast REQ. Callers use this to decide whether an author still needs their outbox
     * relay-list discovery accelerated (see FeedViewModel's outbox-sweep acceleration). Default is
     * `false`; only the real repository implementation tracks anything — test doubles don't need
     * to change.
     */
    fun isAuthorOutboxKnown(pubkey: String): Boolean = false

    /**
     * Clears all data from both databases (public and encrypted).
     */
    suspend fun clearAllData()

    /**
     * Clears [pubkey]'s durable backfill-progress watermarks (see BackfillAnchorStore) — called on
     * logout so this account's leftover bookkeeping doesn't linger past when the user asked
     * everything to be wiped.
     */
    fun clearBackfillAnchors(pubkey: String)

    /**
     * Activate user session: stores pubkey + feed config and applies all standard
     * subscription channels (feed, metadata, notifications, self-profile) to every
     * currently connected relay. Also called automatically for new relays on connect.
     */
    /**
     * @param authors When non-empty (i.e. [feedFilter] is scoped to follows), the feed-notes
     * relay subscription requests only these authors instead of an unscoped firehose.
     */
    fun activateUserSession(pubkey: String?, feedFilter: FeedFilter, authors: Set<String> = emptySet())

    /**
     * Set a stable namespace for REQ subscription IDs (e.g. per logged user session).
     */
    fun setSubscriptionNamespace(namespace: String)

    /**
     * Subscribe to events from enabled relays
     * @param filters Event filters to apply
     * @return Flow of events matching the filters
     */
    fun subscribeToEvents(filters: List<EventFilter>): Flow<Event>

    /**
     * Subscribe/update a dedicated channel (separate REQ namespace) across connected relays.
     */
    fun subscribeChannel(channelId: String, filters: List<EventFilter>)

    /**
     * Sets (or, with an empty list, clears) extra filters layered on top of [channelId]'s own
     * base filters from [subscribeChannel] — both are sent together as one subscription (NIP-01:
     * multiple filters per REQ, still one subscription slot) rather than as two separate channels.
     * For a caller whose filters change independently of and more often than the channel's base
     * (e.g. FEED_NOTES' base is the followed-authors note filter, its overlay is the
     * currently-visible notes' engagement filter, which churns on scroll) — updating either one
     * always sends the other's latest value too, they don't clobber each other. A no-op if
     * [channelId] has no base filters yet.
     */
    fun setChannelOverlay(channelId: String, overlayFilters: List<EventFilter>)

    /**
     * Close a dedicated channel (and any overlay set on it) on all connected relays.
     */
    fun clearChannel(channelId: String)

    /**
     * Re-sends every active channel's filters as a fresh REQ to [relayUrl]. Used after a
     * NIP-42 AUTH challenge succeeds so the original subscriptions (which the relay may have
     * required auth for) are replayed instead of left dropped for that relay.
     */
    fun reapplyChannelsToRelay(relayUrl: String)

    /**
     * Suspends until every relay currently subscribed to [channelId] has sent EOSE (NIP-01: end
     * of stored events — the relay has nothing more to backfill for this REQ), or [timeoutMs]
     * elapses, whichever comes first. Used to close ephemeral/one-shot channels (history pages,
     * profile hydration) as soon as every relay is done rather than always waiting out a fixed
     * delay. Returns immediately if the channel currently has no active relay subscriptions.
     */
    suspend fun awaitChannelEoseOrTimeout(channelId: String, timeoutMs: Long)

    /**
     * Load a page of events older than [untilTimestamp] for the given channel.
     * Events arrive via the same [getCachedEvents] flow. The temporary subscription
     * closes automatically after a timeout.
    */
    fun loadOlderEvents(
        channelId: String,
        untilTimestamp: Long,
        windowSeconds: Long = 7 * 24 * 60 * 60L,
        limit: Int = 200
    )

    /**
     * Re-requests [channelId]'s base filter for the explicit `[sinceTimestamp, untilTimestamp]`
     * window against whatever relays are *currently* connected — unlike [loadOlderEvents], this
     * never clamps the window to what's already cached for the channel's author. Use this when a
     * window was already fetched once but against a *different* relay set than is connected now
     * (e.g. the user's real NIP-65 relay list replacing bootstrap defaults) — a newly-added relay
     * can hold events in that window the old set never had (public relays prune/lose history; a
     * private relay may keep everything), so "we already have the oldest event we're going to get
     * for this window" no longer holds. Events arrive via the same [getCachedEvents] flow. The
     * temporary subscription closes automatically after a timeout.
     */
    fun resyncRecentHistory(
        channelId: String,
        sinceTimestamp: Long,
        untilTimestamp: Long,
        limit: Int = 200
    )

    /**
     * Returns the oldest cached note timestamp for a specific author.
     */
    suspend fun getOldestAuthorNoteTimestamp(pubkey: String): Long?

    /**
     * Returns the oldest cached note timestamp for events that reference the user (INBOX_NOTES).
     */
    suspend fun getOldestInboxNoteTimestamp(pubkey: String): Long?

    /**
     * Returns the oldest cached reaction timestamp for events that reference the user (part of INBOX_NOTES' interactions filter).
     */
    suspend fun getOldestInboxReactionTimestamp(pubkey: String): Long?

    /**
     * Get cached events
     * @return Flow of cached events
     */
    fun getCachedEvents(): Flow<List<Event>>

    /**
     * Observe latest persisted events directly from Room.
     */
    fun observeRecentEvents(limit: Int = 2000): Flow<List<Event>>

    /**
     * Get one event from local cache storage by id.
     */
    suspend fun getEventById(id: String): Event?

    /**
     * Relay(s) known to have delivered [eventId] to the in-memory cache, if any — empty if the
     * event was never cached (e.g. it's the signed-in user's own event, persisted to Room instead)
     * or has since been evicted. Transport provenance only, not a Nostr protocol property.
     */
    suspend fun getEventRelays(eventId: String): Set<String>

    /**
     * Like [getEventById], but falls back to a one-shot relay lookup (a short-lived REQ for
     * exactly this id) when the event isn't already cached locally — fixes the common case of
     * opening a note/mention/notification reference that has scrolled out of the in-memory
     * cache. [timeoutMs] bounds how long the relay round-trip is allowed to take against relays
     * we're already connected to.
     *
     * [relayHints] are NIP-19 relay hints (a nevent1's TLV type 1) that came with the reference,
     * if any — the whole reason a nevent/nprofile carries them over a bare note/npub is to tell a
     * client where to find an entity it doesn't already have. When present, [connectToRelayHints]
     * dials them directly and the effective wait is extended to give a fresh Tor connection to an
     * unfamiliar relay realistic time to complete — a bare [timeoutMs] (sized for already-
     * connected relays) would almost always lose that race otherwise. The default implementation
     * here is cache-only (no network) — only the real repository implementation performs the
     * relay fallback; test doubles don't need to change.
     */
    suspend fun fetchEventById(id: String, timeoutMs: Long = 5000L, relayHints: List<String> = emptyList()): Event? = getEventById(id)

    /**
     * Best-effort, immediate connect to [relayUrls] — bypasses the normal session-level relay-set
     * reconcile (debounced, see NostrSessionManager.RELAY_SET_DEBOUNCE_MS) so a relay hint has a
     * realistic chance of being reachable before a bounded wait (e.g. [fetchEventById]'s
     * [relayHints]) times out. Does not persist [relayUrls] into the configured relay list itself
     * — callers that want that (so the hint keeps helping after this call's wait ends) should also
     * call UserRepository.discoverRelayHints. No-op default; only the real repository
     * implementation has a websocket client to dial with.
     */
    fun connectToRelayHints(relayUrls: List<String>) {}

    /**
     * Records [relayUrls] as relays *seen hinted* for [pubkey] (a NIP-19 nprofile1/nevent1's TLV
     * relay hints, wherever this pubkey turned up as an author/mention) — routing data, not a
     * connection action (see [connectToRelayHints] for that). Fallback outbox-routing tier: when a
     * followed author's own declared kind:10002 outbox isn't known yet, a relay we've *seen* them
     * hinted at is still real routing signal — better than treating them as fully unscoped. See
     * [computeAuthorsPerRelay]'s `hintRelaysFor` param, which this feeds. No-op default; only the
     * real repository implementation keeps this cache.
     */
    fun recordRelayHint(pubkey: String, relayUrls: List<String>) {}

    /**
     * Reads back whatever [recordRelayHint] has accumulated for [pubkey] so far — the read side of
     * that cache, for callers that want to dial a pubkey's hinted relays directly (e.g. backfilling
     * an open profile screen whose declared kind:10002 outbox isn't known yet) rather than only
     * having it consulted internally by [computeAuthorsPerRelay]'s routing. No-op default (empty);
     * only the real repository implementation keeps this cache.
     */
    fun getRelayHints(pubkey: String): List<String> = emptyList()

    /**
     * Resolve latest replaceable/parameterized replaceable event by coordinate.
     */
    suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): Event?

    /**
     * Get multiple events from local cache storage by ids.
     */
    suspend fun getEventsByIds(ids: List<String>): List<Event>

    /**
     * Get locally persisted events that reference any of the given event ids via #e tags.
     */
    suspend fun getEventsReferencingIds(targetIds: List<String>): List<Event>

    /**
     * Observe events by author and kind from local cache storage.
     */
    fun observeEventsByPubkeyAndKind(pubkey: String, kind: Int, limit: Int = 100): Flow<List<Event>>

    /**
     * Observe total amount of events by author and kind from local cache storage.
     */
    fun observeCountEventsByPubkeyAndKind(pubkey: String, kind: Int): Flow<Int>

    /**
     * Clear event cache
     */
    suspend fun clearCache()

    /**
     * Snapshot of the in-memory (non-persisted) event cache's current occupancy. Default returns
     * an empty/zero stat pair; only the real repository implementation has anything to report.
     */
    suspend fun getInMemoryCacheStats(): EventCacheStats = EventCacheStats(size = 0, maxSize = 0)

    /**
     * Shrinks the in-memory event cache under real OS memory pressure (see
     * `UmbraApp.onTrimMemory`) without lowering its normal ceiling — the cache is free to grow
     * back afterward via ordinary ingestion. [aggressive] selects a deeper trim for
     * `TRIM_MEMORY_BACKGROUND`+ versus a lighter one for `TRIM_MEMORY_UI_HIDDEN`. Default is a
     * no-op; only the real repository implementation has a cache to trim.
     */
    suspend fun trimMemory(aggressive: Boolean) {}

    suspend fun deleteEvent(eventId: String)

    /**
     * Connect to all enabled relays
     */
    suspend fun connectToEnabledRelays(relays: List<Relay>): Result<Unit>

    /**
     * Disconnect from all relays
     */
    fun disconnectFromAll()

    /**
     * Publish an event to relays — the author's own outbox plus, for a reply/mention, the
     * inbox relays of every addressed pubkey (see NIP-65 outbox model).
     * @param event The event to publish
     * @return the set of relay URLs the event was actually sent to
     */
    suspend fun publishEvent(event: Event): Result<Set<String>>

    /**
     * Publish NIP-42 AUTH event to a specific relay.
     */
    suspend fun publishAuthEvent(relayUrl: String, event: Event): Result<Unit>

    /**
     * Observe outgoing REQ messages sent to relays.
     */
    fun observeRelayRequests(): Flow<List<RelayRequestInfo>>

    /**
     * Observe relay-side errors/notices (rate limits, auth issues, connectivity failures).
     */
    fun observeRelayIssues(): Flow<RelayIssue>

    /**
     * Observe relay URLs that currently have an active WebSocket connection.
     */
    fun observeConnectedRelayUrls(): Flow<Set<String>>

    /**
     * Clears [relayUrl]'s consecutive connect-failure count — call when the user manually
     * re-enables a relay that got auto-disabled (see [RelayIssue] kind AUTO_DISABLED) so it gets
     * a fresh run at the failure threshold instead of immediately re-tripping.
     */
    fun resetRelayFailureCount(relayUrl: String)

    /**
     * Immediately tears down [relayUrl]'s connection and forgets its per-relay tracking state —
     * call when a relay is disabled and must stop being used right away, rather than waiting for
     * the next [connectToEnabledRelays] reconcile pass to notice it's no longer eligible.
     */
    fun disconnectRelay(relayUrl: String)

    /**
     * Pool-wide counterpart to [resetRelayFailureCount] — clears every relay's accumulated
     * failure/cooldown/SOCKS-retry state. Call when circuit health recovers after a confirmed
     * "Tor Active but circuits dead" episode (see [RelayIssue] kind TOR_CIRCUITS_RECOVERED /
     * com.umbra.app.domain.relay.TorCircuitHealthTracker), immediately followed by a fresh
     * [connectToEnabledRelays] pass so the now-forgiven relays actually retry.
     */
    fun resetAllRelayBackoff()

    /**
     * Request NIP-45 COUNT results for a given relay/filter set.
     */
    fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>)

    /**
     * Observe parsed NIP-45 COUNT responses from relays.
     */
    fun observeRelayCounts(): Flow<RelayCountResult>

    // ── SSoT Room Flows — the Single Source of Truth for UI ──────────────────

    /**
     * Observe feed notes (kind 1) joined with author profiles and live engagement
     * counts from the local Room cache.
     *
     * Room re-emits whenever [events], [user_profiles] or [event_tags] change —
     * including when a kind-0 metadata event is inserted, so all UI components
     * observing a pubkey receive the updated [NoteView.authorProfile] automatically.
     *
     * @param since   Unix timestamp (seconds); only events newer than this are returned.
     *                Use 0 to return all cached events.
     * @param limit   Maximum number of notes to return, sorted newest-first.
     * @param authors When non-empty, restricts results to this author set (follow list / curation).
     *
     * Returns [FeedNotesResult] (not a bare `List<NoteView>`) since a resolution pass can also
     * surface reposts whose target isn't available yet — see [FeedNotesResult.pendingReposts].
     */
    fun observeFeedNotes(
        since: Long = 0L,
        limit: Int = 300,
        authors: Set<String> = emptySet(),
        mutedPubkeys: Set<String> = emptySet(),
        excludedHashtagsLower: Set<String> = emptySet(),
        includeMentions: Boolean = true,
        hideNsfw: Boolean = true,
        currentNpub: String? = null,
        currentUserPubkey: String? = null,
        desiredTagsLower: Set<String> = emptySet()
    ): Flow<FeedNotesResult>

    /**
     * Observe notes for a single author (profile screen), joined with profile
     * info and engagement counts. See [observeFeedNotes] for why this returns [FeedNotesResult].
     *
     * @param kind   Nostr event kind — typically [Event.KIND_TEXT_NOTE].
     */
    fun observeProfileNotes(
        pubkey: String,
        kind: Int = Event.KIND_TEXT_NOTE,
        limit: Int = 100
    ): Flow<FeedNotesResult>

    suspend fun searchNotes(query: String): Flow<List<Event>>
}

