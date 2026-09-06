package com.umbra.app.data.repository

import androidx.room.withTransaction
import com.umbra.app.TorProxyConfig
import com.umbra.app.data.db.dao.EventDao
import com.umbra.app.data.db.dao.EventTagDao
import com.umbra.app.data.db.EncryptedUmbraDatabase
import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.data.db.entities.EventTagEntity
import com.umbra.app.data.db.mapper.clearEventTagsCache
import com.umbra.app.data.db.mapper.toDomain
import com.umbra.app.data.db.mapper.toEntity
import com.umbra.app.data.db.mapper.toNoteView
import com.umbra.app.data.db.mapper.toTagEntities
import com.umbra.app.data.repository.cache.OwnerTagSetCache
import com.umbra.app.data.repository.policy.DiscoveredRelayDialPolicy
import com.umbra.app.data.repository.policy.DiscoveredRelayIdlePolicy
import com.umbra.app.data.repository.policy.FeedRelaySincePolicy
import com.umbra.app.data.repository.policy.OutboxProfilePolicy
import com.umbra.app.data.repository.policy.RelayConnectionPolicy
import com.umbra.app.data.repository.policy.RelayRoleChangePolicy
import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.feed.DefaultFeedFilters
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip01.replaceableKey
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.nip77.NegentropyItem
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.model.EventCacheStats
import com.umbra.app.domain.model.FeedNotesResult
import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.model.PendingRepost
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.relay.isLocalNetworkRelayUrl
import com.umbra.app.domain.nip01.NostrValidation
import com.umbra.app.data.nostr.BackfillAnchorClearer
import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.preferences.SyncPreferences
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip18.extractRepostTarget
import com.umbra.app.domain.nip51.extractIndexRelaysList
import com.umbra.app.domain.nip51.extractSearchRelaysList
import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.domain.nipb7.UserServerList
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.LogScrubber.scrubUrlForLogs
import com.umbra.app.util.logging.UmbraLog
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private sealed interface FeedCacheUpdate {
    data class ExternalBundle(val events: Set<Event>) : FeedCacheUpdate
    data class OwnSnapshot(val events: List<Event>) : FeedCacheUpdate
    data object Rebuild : FeedCacheUpdate
}

/**
 * Implementation of EventRepository
 * Manages WebSocket connections to relays and event caching
 */
@Singleton
class EventRepositoryImpl @Inject constructor(
    private val nostrClient: NostrClient,
    @Named("encrypted") private val encryptedDatabase: EncryptedUmbraDatabase,
    @Named("encrypted") private val encryptedEventDao: EventDao,
    @Named("encrypted") private val encryptedEventTagDao: EventTagDao,
    private val userPreferences: UserPreferences,
    private val userRepository: UserRepository,
    // Typed as the narrow BackfillAnchorClearer seam (this class's only call site is
    // clearBackfillAnchors -> backfillAnchorStore.clear) rather than the concrete
    // BackfillAnchorStore, whose constructor eagerly builds a real Android-Keystore-backed
    // SecurePreferences instance and cannot be constructed in a plain JVM unit test. See
    // BackfillAnchorClearer's doc comment.
    private val backfillAnchorStore: BackfillAnchorClearer,
    private val syncPreferences: SyncPreferences
) : EventRepository {

    companion object {
        private const val TAG = "UmbraEventRepo"
        // In-memory-only cache for everyone except the signed-in user (see EventLruCache doc).
        // Access-order (LruCache) eviction, not insertion-order, so bigger is cheap in principle —
        // but "cheap" needs a realistic per-Event estimate. A prior revision sized this at 100000
        // assuming ~0.5-1KB/event, which undercounted real Kotlin object overhead (id/pubkey/sig
        // as UTF-16 Strings, tags as a List<List<String>> with real per-String/per-List headers,
        // not just raw hex byte counts) — realistically ~2-3KB/event once assembled, so 100000 was
        // on the order of 250-300MB on its own, and a real field OOM (344 connected relays
        // actively streaming, target footprint/growth limit both exactly 268435456 = the 256MB
        // Android default heap ceiling) confirmed this was too large in practice, not just in
        // theory. 50000 (~100-150MB at the corrected estimate) is the new, actually-verified-safe
        // ceiling — combined with android:largeHeap="true" (AndroidManifest.xml) for real headroom
        // on top, rather than relying on the cap alone. Also bounds the externalEvents FIFO mirror
        // in observeFeedNotes() and is the pool FeedViewModel's _displayLimit grows a render window
        // over without triggering a new relay REQ (see loadOlderEvents' fallback role once this
        // pool is exhausted). Still a safety valve, not a normal-operation ceiling — an alternative
        // design relies purely on Android's GC/heap pressure with no fixed count ceiling at all;
        // Umbra keeps a real (if generous) bound rather than going fully unbounded, matching the
        // same reasoning already applied to relay-pool sizing elsewhere in this codebase (a
        // large-but-finite cap so a pathological session can't grow the process without limit,
        // without acting as a practical constraint in ordinary use).
        private const val MAX_IN_MEMORY_EVENT_CACHE = 50000
        // Separate from the above: an UPPER CEILING on encryptedEventDao.observeRecentEvents(),
        // i.e. how many rows of the CURRENT USER's own archive can ever be pulled into the feed
        // merge — an unrelated tradeoff (Room query size/merge cost) from the public cache size.
        // The *effective* count used per call is min(this, the caller's `limit`) — see
        // observeFeedNotes — not this constant directly: the own archive is 100% locally durable
        // (see class doc: only the signed-in user's own events persist in the encrypted DB) and
        // instantly available with zero network latency, while every other author's notes only
        // exist in the in-memory cache once a relay round-trip over Tor actually delivers them.
        // Merging a flat 1000 most-recent own notes regardless of `limit` meant a fresh feed
        // subscription (small `limit`, thin external cache still warming up) could be dominated
        // by the user's own old history purely because it wins the recency sort by being the only
        // fully-populated candidate pool — the exact opposite of "the feed starts from now and
        // older notes (yours included) surface as you scroll further into the past." Scaling with
        // `limit` keeps the own archive competing at the same pagination depth as everything else,
        // instead of holding a permanent 1000-deep bench.
        private const val OWN_ARCHIVE_FEED_MERGE_LIMIT = 1000
        // Real callers of the public observeRecentEvents() request distinct limits — contact/
        // mute/pin-list bootstrap at 4000 (OwnerTagSetCache), ThreadViewModel's room window at
        // 3000, searchNotes() at 1200 — each of which was its own independent Room query +
        // entity-map + mergeHybridEvents sort/dedupe pass running concurrently at session start,
        // despite all three differing only in how many of the same recency-sorted results they
        // keep. observeRecentEvents() queries at max(requested limit, this constant) and caches
        // that single shared Flow; a caller asking for fewer than this just takes a prefix of the
        // already-sorted shared result instead of triggering its own Room scan.
        private const val CANONICAL_RECENT_EVENTS_LIMIT = 4000
        private const val MAX_SEEN_IDS = 5000
        // Bound for verifiedEventIds (see subscribeToEvents) — memoizes successful BIP-340
        // verifications only, so a relay redelivering an already-verified event id skips the
        // BouncyCastle Schnorr check instead of paying its full CPU + garbage cost again.
        private const val MAX_VERIFIED_IDS = 5000
        // Cap for referencedAuthorsOrder (see noteReferencedAuthor) — quoted-note/thread/mention
        // authors from a single session are typically a small, slow-growing set compared to a
        // follow list, so this stays modest; least-recently-referenced pubkeys are evicted first.
        private const val MAX_REFERENCED_AUTHORS = 300
        // Bound for repostTargetFetchAttempted (see scheduleRepostTargetFetch) — a repost-target
        // lookup, unlike pendingEventLookupIds/eventLookupTriedByRelay, must persist for the whole
        // session rather than just one in-flight call, so it needs its own cap/clear-at-max idiom
        // matching MAX_SEEN_IDS/MAX_VERIFIED_IDS above.
        private const val MAX_REPOST_TARGET_FETCH_ATTEMPTS = 2000
        private const val CHANNEL_RESUBSCRIBE_DEBOUNCE_MS = 350L
        // NIP-77 own-event sync is a heavier, less time-sensitive operation than a channel
        // resubscribe — a longer debounce avoids kicking one off on every minor session tweak
        // right after login while relay lists/outbox discovery are still settling.
        private const val NEGENTROPY_SYNC_DEBOUNCE_MS = 5_000L
        private const val FEED_SINCE_SECONDS        = 12 * 60 * 60L  // interactions/outbox window
        private const val INITIAL_FEED_WINDOW_SECS  = 12 * 60 * 60L  // first live feed-notes window
        private const val HISTORY_PAGE_WINDOW_SECS  = 7 * 24 * 60 * 60L // default "load older" page window
        private const val HISTORY_PAGE_CLOSE_MS     = 15_000L           // auto-close page sub after
        private const val NOTIF_SINCE_SECONDS       = 3  * 24 * 60 * 60L
        private val ANON_PUBKEY_REGEX = Regex("^0{64}$")
        // USEFUL_PERSISTED_KINDS/ALWAYS_PERSIST_CONTROL_KINDS moved to EventIngestCache.kt (top
        // level, internal) alongside shouldPersistEvent, the one method that owns the persist-
        // eligibility decision they drive. USEFUL_PERSISTED_KINDS is still referenced below
        // (unqualified — same package) by scheduleNegentropySync().
        // fetchEventById()'s wait budget when relayHints are supplied — same "enough Tor
        // round-trip slack" reasoning as PROFILE_HYDRATION_CHANNEL_CLOSE_MS elsewhere: a hint
        // relay is, by definition, one we weren't already connected to, so the wait has to cover a
        // fresh SOCKS/circuit-build handshake plus the relay's own response, not just a round trip
        // to an already-open socket.
        private const val EVENT_LOOKUP_HINT_TIMEOUT_MS = 15_000L
        private const val EVENT_LOOKUP_HINT_POLL_INTERVAL_MS = 400L
        // Bounds connectToRelayHints()'s dial burst per lookup — a handful of hints is the normal
        // case (most nevent/nprofile references carry 1-3), and capping means a malformed or
        // adversarial reference with a long relay list can't fire an unbounded number of Tor
        // connections from a single fetchEventById() call.
        private const val MAX_HINT_RELAYS_PER_LOOKUP = 3
        // Bounds relayHintsByPubkey — a routing-signal cache, not correctness-critical, so a
        // simple insert-time cap (rather than real LRU eviction) is enough: a pubkey already
        // tracked keeps updating, a brand-new one past the cap is just never recorded.
        private const val MAX_RELAY_HINT_PUBKEYS = 2000
        private const val MAX_RELAY_HINTS_PER_PUBKEY = 8
        // Demand-driven disconnect for isDiscovered relays only (see DiscoveredRelayIdlePolicy) —
        // deliberately long given Tor circuit setup cost, unlike a direct-connect client. A
        // discovered relay reconnects on-demand via the normal connectToEnabledRelays() path the
        // next time its covered author's content is actually requested again.
        private const val DISCOVERED_RELAY_IDLE_GRACE_MS = 45 * 60_000L
        private const val DISCOVERED_RELAY_IDLE_SWEEP_INTERVAL_MS = 15 * 60_000L
        // See the pacing comment in connectToEnabledRelays() — bounds how many relay connect()
        // dials fire in the same instant against the single shared Tor SOCKS process.
        private const val DIAL_BATCH_SIZE = 8
        private const val DIAL_BATCH_PAUSE_MS = 250L
        // subscribeToEvents()'s ingestion pipeline used to verify+persist events from every relay
        // one at a time on a single coroutine — a burst from one relay (backfill, history page)
        // queued ahead of a single EVENT from an unrelated relay (e.g. one fetchEventById() is
        // polling the cache for), delaying that event's cache-visibility even though it already
        // arrived on the wire. Bounded concurrency lets independent events verify+persist in
        // parallel without unbounded fan-out on a burst.
        private const val EVENT_PROCESSING_CONCURRENCY = 8
    }

    private val logger = UmbraLog.tag(TAG)

    // PendingEventInsert moved to EventIngestCache.kt (top-level, internal) alongside
    // scheduleInsert, the method that constructs it.

    // Repost targets a fallback fetchEventById lookup has already been attempted for this session
    // (see scheduleRepostTargetFetch) — a reactive feed/profile combine can recompute for reasons
    // entirely unrelated to this specific target (an unrelated reaction count changing, etc.), and
    // without this, each recompute would re-fire a fresh relay REQ for a target that's genuinely
    // never going to resolve. One attempt per id per session, bounded like seenEventIds below.
    private val repostTargetFetchAttempted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Deduplication: events from multiple relays share IDs — track which we've already processed
    private val seenEventIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Verification memo: event.id is a hash of the event's full content, so a successful
    // verification for a given id is permanently valid to reuse — unlike seenEventIds above,
    // this is safe to check *before* shouldPersistEvent()'s live mute/filter evaluation, since it
    // only ever short-circuits the BIP-340 check itself, never any downstream decision.
    private val verifiedEventIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val connectedRelays = ConcurrentHashMap<String, Relay>()
    // When a discovered relay last actually covered a requested author (see applyChannelToRelay's
    // precise-routing mark, and connectToEnabledRelays' baseline seed on connect) — read by the
    // idle sweep (see sweepIdleDiscoveredRelays) to free connections no longer worth holding open.
    // Never consulted for non-discovered relays; see DiscoveredRelayIdlePolicy.
    private val discoveredRelayLastNeededAtMillis = ConcurrentHashMap<String, Long>()
    // Per-relay `since` watermark for FEED_NOTES (Unix seconds) — see FeedRelaySincePolicy's doc
    // comment. Set when that specific relay reports EOSE for FEED_NOTES (the eoseFlow collector
    // in init); read in applyChannelToRelay via applyPerRelayFeedSince. Sticky across a temporary
    // disconnect/reconnect (same principle as UmbraNostrClient's capability sets) — only cleared
    // when the relay is forgotten for good (connectToEnabledRelays' stale-relay eviction).
    private val feedSinceByRelay = ConcurrentHashMap<String, Long>()
    // Same per-relay-EOSE-watermark idea as feedSinceByRelay, for MERGEABLE_BACKFILL_CHANNEL_IDS
    // (OUTBOX_NOTES/INBOX_NOTES) instead of FEED_NOTES — see OutboxInboxRelaySincePolicy's doc
    // comment. Keyed by (relayUrl, channelId) since it serves two channels, not one.
    private val outboxInboxSinceByRelay = ConcurrentHashMap<Pair<String, String>, Long>()
    private var currentFilters: List<EventFilter> = emptyList()
    private val channelFilters = ConcurrentHashMap<String, List<EventFilter>>()
    // Extra filters layered on top of a channel's own declared filters — e.g. FEED_NOTES' base
    // (followed-authors notes) plus a standing engagement overlay (currently-visible notes'
    // reactions/reposts/zaps) — sent together as one subscription (see effectiveChannelFilters,
    // setChannelOverlay). Distinct from channelFilters so each half can be updated independently
    // (session/follow-list changes vs. scroll-driven engagement changes) without one clobbering
    // the other, unlike the plain multi-filter merges in applySessionChannelsToRelay (outbox/inbox)
    // where both halves are built together in the same place and don't need this.
    private val channelOverlays = ConcurrentHashMap<String, List<EventFilter>>()
    private val lastOverlayFingerprints = ConcurrentHashMap<String, String>()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Ingest/cache/telemetry collaborator — manually instantiated,
    // following NegentropySyncOrchestrator's/EventChannelRouting's precedent. Owns the LRU cache,
    // engagement index, replaceable-slot bookkeeping, the three cached-event flows, and the 250ms
    // burst-coalescing snapshot emitter as one cohesive unit (see EventIngestCache's class doc
    // comment). Declared before init{} (unlike its two lazy siblings further down this file)
    // because init{} calls into it synchronously — a `by lazy` property may not be referenced
    // from init{} before its own textual declaration point.
    private val eventIngestCache by lazy {
        EventIngestCache(
            repoScope = repoScope,
            maxInMemoryEvents = MAX_IN_MEMORY_EVENT_CACHE,
            ownEventArchive = RoomOwnEventArchive(),
            seenEventIds = seenEventIds,
            activeFeedFilter = { activeFeedFilter },
            isCurrentUserPubkey = ::isCurrentUserPubkey,
            isPendingEventLookupId = { it in pendingEventLookupIds },
            isPinnedProfileAuthor = { pinnedProfileAuthors.contains(it) },
            isWiping = { isWiping.get() }
        )
    }
    private val _relayRequestsFlow = MutableStateFlow<List<RelayRequestInfo>>(emptyList())
    private val eventLogCounter = AtomicInteger(0)
    private val isWiping = AtomicBoolean(false)
    private val lastChannelFingerprints = ConcurrentHashMap<String, String>()
    private val pendingChannelJobs = ConcurrentHashMap<String, Job>()
    // Ids currently awaited by a fetchEventById() caller — shared across every concurrent caller
    // so relays get one pooled REQ (NostrChannels.EVENT_LOOKUP) carrying every pending id instead
    // of one REQ per id. See fetchEventById()/applyEventLookupPool().
    private val pendingEventLookupIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Per-relay ids EVENT_LOOKUP has already asked about and gotten EOSE for — an "already tried"
    // set. The pending-id pool changes continuously as concurrent callers come and go, so without
    // this a relay that
    // already told us (via EOSE) it doesn't have ids {a,b} gets asked about them again every time
    // an unrelated caller adds id c to the pool. Purged for an id once it leaves the pending pool
    // (see applyEventLookupPool), so a later unrelated fetchEventById() for the same id starts
    // fresh rather than being silently skipped forever.
    private val eventLookupTriedByRelay = ConcurrentHashMap<String, MutableSet<String>>()
    // Ids actually sent in EVENT_LOOKUP's most recent REQ to each relay (populated by the reqFlow
    // collector in init, which observes the wire-level REQ post-routing/post-clamping) — read when
    // that REQ's EOSE arrives to know which ids this relay just confirmed exhausted, moved into
    // eventLookupTriedByRelay above.
    private val eventLookupLastSentByRelay = ConcurrentHashMap<String, Set<String>>()
    // Author-based analog of eventLookupTriedByRelay/eventLookupLastSentByRelay above, for
    // FEED_PROFILES_ONDEMAND and profile-follows-meta-* — see isAuthorHydrationChannel(). Unlike
    // EVENT_LOOKUP these channels have no single shared "pending pool" to purge tried entries
    // against as items resolve; a pubkey that later actually gets a profile simply stops appearing
    // in these channels' filters at all (no longer "missing"/non-fresh), so a stale tried marker
    // for it is harmless dead weight rather than a correctness bug. The one known gap: if the same
    // relay's answer for a pubkey goes stale (isProfileFresh's 24h window elapses) during a single
    // very long-running session, the tried marker would still suppress re-asking that relay — not
    // purged here since there's no natural trigger to hook that into; acceptable given normal
    // session lengths are far short of 24h continuous foreground time.
    // Tracked per relay only, not per channel: a relay's "doesn't have pubkey P" answer from one
    // hydration channel is still valid for any other hydration channel asking about P.
    private val authorHydrationTriedByRelay = ConcurrentHashMap<String, MutableSet<String>>()
    // Keyed by (relayUrl, channelId) rather than relayUrl alone — unlike EVENT_LOOKUP (always
    // exactly one channel), multiple author-hydration channels can be open on the same relay at
    // once (e.g. FEED_PROFILES_ONDEMAND plus a profile-follows-meta-<pk> for an open profile
    // screen), each needing its own "what did I just send" bookkeeping so an EOSE is attributed to
    // the right channel's authors, not whichever channel happened to REQ most recently.
    private val authorHydrationLastSentByRelay = ConcurrentHashMap<Pair<String, String>, Set<String>>()
    private val pageRequestFingerprint = ConcurrentHashMap<String, String>()
    private var subscriptionNamespace: String = "anon"
    // Session state — set by activateUserSession(), applied to every relay on connect
    private var activeUserPubkey: String? = null
    private var activeFeedFilter: FeedFilter = DefaultFeedFilters.DEFAULT
    private var activeSessionAuthors: Set<String> = emptySet()
    // Precise relay -> authors-it-covers routing, for the subset of activeSessionAuthors whose
    // own outbox is already cached — recomputed whenever the session's follow list changes or a
    // tracked author's NIP-65 relay list arrives; see computeAuthorsPerRelay(). Authors NOT in
    // authorsWithKnownOutbox are always broadcast unscoped (see scopeAuthorsForRelay) — there is
    // deliberately no fallback relay list here, see computeAuthorsPerRelay's doc comment.
    // @Volatile: written from activateUserSession()/the event-ingestion collector, read from
    // applyChannelToRelay() which can run on either caller thread.
    @Volatile
    private var feedAuthorsPerRelay: Map<String, Set<String>> = emptyMap()
    @Volatile
    private var authorsWithKnownOutbox: Set<String> = emptySet()
    // Relays *seen hinted* per pubkey (NIP-19 nprofile1/nevent1 TLV data) — routing signal only,
    // not a connection action (see connectToRelayHints for that). Feeds computeAuthorsPerRelay's
    // hintRelaysFor fallback tier for a followed author whose declared outbox isn't known yet. See
    // recordRelayHint's doc comment (EventRepository) for why this fallback tier exists.
    private val relayHintsByPubkey = ConcurrentHashMap<String, MutableSet<String>>()
    private val authorsPerRelayRefreshJob = AtomicReference<Job?>(null)
    private val historyPageJobs = ConcurrentHashMap<String, Job>()
    // Overlay-based backfill state for MERGEABLE_BACKFILL_CHANNEL_IDS (see applyBackfillOverlay):
    // one mutex per channelId serializes loadOlderEvents()/resyncRecentHistory() calls against the
    // same channel so a caller can never capture a "base" snapshot that still includes another
    // caller's not-yet-reverted overlay filter and leak it back in on its own revert.
    private val channelOverlayMutex = ConcurrentHashMap<String, Mutex>()
    private val channelOverlayJobs = ConcurrentHashMap<String, Job>()
    private val overlayRequestFingerprint = ConcurrentHashMap<String, String>()
    private val pinnedProfileAuthors: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Bounded MRU set of non-owner pubkeys whose content was fetched/viewed this session (quoted
    // note authors, thread authors, mentioned profiles, viewed profile screens) — see
    // noteReferencedAuthor(). Guarded by referencedAuthorsLock since touch-to-MRU is a
    // remove+re-add pair that must stay atomic under concurrent callers.
    private val referencedAuthorsLock = Any()
    private val referencedAuthorsOrder = LinkedHashSet<String>()
    private val initialCacheLoaded = CompletableDeferred<Unit>()
    // Newest cached timestamp for kind 1 — used as `since` on reconnect to skip already-cached events
    @Volatile
    private var cachedFeedSince: Long? = null

    init {
        eventIngestCache.emitEmptySnapshot()
        eventIngestCache.startTelemetryLogging()
        initialCacheLoaded.complete(Unit)

        repoScope.launch {
            nostrClient.reqFlow.collect { request ->
                eventChannelRouting.upsertSubscriptionInfo(request)
                // request.filters reflects exactly what was sent over the wire to this relay —
                // already routed/clamped, so for EVENT_LOOKUP it's this relay's actual (possibly
                // already-narrowed) id set, not the full shared pending pool. See
                // eventLookupTriedByRelay's doc comment.
                if (nostrClient.currentSubscriptionId(request.relayUrl, NostrChannels.EVENT_LOOKUP) == request.subscriptionId) {
                    eventLookupLastSentByRelay[request.relayUrl] = request.filters.firstOrNull()?.ids.orEmpty()
                } else {
                    val channelId = nostrClient.resolveChannelId(request.relayUrl, request.subscriptionId)
                    if (channelId != null && eventChannelRouting.isAuthorHydrationChannel(channelId)) {
                        val authors = request.filters.flatMapTo(mutableSetOf()) { it.authors }
                        authorHydrationLastSentByRelay[request.relayUrl to channelId] = authors
                    }
                }
            }
        }
        repoScope.launch {
            nostrClient.subscriptionEventFlow.collect { (relayUrl, subscriptionId) ->
                eventChannelRouting.incrementSubscriptionEventCount(relayUrl, subscriptionId)
            }
        }
        // EOSE for EVENT_LOOKUP/an author-hydration channel means this relay has now sent
        // everything it currently has for the ids/authors in its last REQ — fold those into
        // eventLookupTriedByRelay/authorHydrationTriedByRelay so the next REQ built for it (see
        // excludeAlreadyTriedEventLookupIds/excludeAlreadyTriedAuthorHydration) doesn't ask again.
        repoScope.launch {
            nostrClient.eoseFlow.collect { eoseSignal ->
                val (relayUrl, subId, completeness) = eoseSignal
                if (nostrClient.currentSubscriptionId(relayUrl, NostrChannels.EVENT_LOOKUP) == subId) {
                    val sent = eventLookupLastSentByRelay[relayUrl].orEmpty()
                    if (sent.isNotEmpty()) {
                        eventLookupTriedByRelay.getOrPut(relayUrl) { ConcurrentHashMap.newKeySet() }.addAll(sent)
                    }
                } else {
                    val channelId = nostrClient.resolveChannelId(relayUrl, subId)
                    if (channelId != null && eventChannelRouting.isAuthorHydrationChannel(channelId)) {
                        val sent = authorHydrationLastSentByRelay[relayUrl to channelId].orEmpty()
                        if (sent.isNotEmpty()) {
                            authorHydrationTriedByRelay.getOrPut(relayUrl) { ConcurrentHashMap.newKeySet() }.addAll(sent)
                        }
                    }
                    // This relay has now delivered everything it currently has for FEED_NOTES —
                    // record its own watermark so the next REQ resumes from here instead of a
                    // global cursor that may be more advanced than what THIS relay has actually
                    // sent (see FeedRelaySincePolicy). NIP-67: skip the advance when this relay
                    // told us (completeness == MORE) it truncated the result set — advancing the
                    // watermark to now would silently skip whatever it didn't send. Leaving the
                    // watermark where it was means the next REQ resumes from the same point
                    // instead (still no explicit `until`-narrowing pagination here — see
                    // EoseCompleteness's doc comment for why the common UNSPECIFIED case, the
                    // overwhelming majority of relays, is unaffected and keeps today's behavior).
                    if (channelId == NostrChannels.FEED_NOTES) {
                        if (FeedRelaySincePolicy.shouldAdvanceWatermark(completeness)) {
                            feedSinceByRelay[relayUrl] = System.currentTimeMillis() / 1000
                        } else {
                            logger.d { "FEED_NOTES EOSE from relay ${scrubUrlForLogs(relayUrl)} reported MORE — not advancing since watermark" }
                        }
                    } else if (channelId != null && channelId in MERGEABLE_BACKFILL_CHANNEL_IDS) {
                        // Same rationale, for OUTBOX_NOTES/INBOX_NOTES's own live filters — see
                        // OutboxInboxRelaySincePolicy.
                        outboxInboxSinceByRelay[relayUrl to channelId] = System.currentTimeMillis() / 1000
                    }
                }
            }
        }

        // When a relay WebSocket opens, immediately apply all active channel subscriptions
        // to it. This handles the race where channels are scheduled (via debounce) before
        // sockets are actually open — particularly affecting outbox-* and feed-notes channels
        // whose filters don't contain a time-varying `since` field.
        repoScope.launch {
            nostrClient.relayOpenedFlow.collect { relayUrl ->
                reapplyChannelsToRelay(relayUrl)
            }
        }

        repoScope.launch {
            while (true) {
                delay(DISCOVERED_RELAY_IDLE_SWEEP_INTERVAL_MS)
                sweepIdleDiscoveredRelays()
            }
        }
    }

    /**
     * Frees connections held by isDiscovered relays that haven't covered a requested author in
     * DISCOVERED_RELAY_IDLE_GRACE_MS — see DiscoveredRelayIdlePolicy. Never touches non-discovered
     * relays. A swept relay isn't removed from the relay list, just disconnected; the normal
     * connectToEnabledRelays() reconnect path picks it back up on-demand once it's relevant again.
     */
    private fun sweepIdleDiscoveredRelays() {
        val now = System.currentTimeMillis()
        connectedRelays.values.toList().forEach { relay ->
            if (!relay.isDiscovered) return@forEach
            if (!nostrClient.isConnected(relay.url)) return@forEach
            val lastNeeded = discoveredRelayLastNeededAtMillis[relay.url] ?: return@forEach
            val eligible = DiscoveredRelayIdlePolicy.isEligibleForIdleDisconnect(
                isDiscovered = relay.isDiscovered,
                lastNeededAtMillis = lastNeeded,
                nowMillis = now,
                graceMs = DISCOVERED_RELAY_IDLE_GRACE_MS
            )
            if (eligible) {
                logger.d { "Disconnecting idle discovered relay (unused for >= ${DISCOVERED_RELAY_IDLE_GRACE_MS / 60_000}min)" }
                nostrClient.disconnect(relay.url)
            }
        }
    }

    override fun reapplyChannelsToRelay(relayUrl: String) {
        channelFilters.keys.forEach { channelId ->
            eventChannelRouting.applyChannelToRelay(relayUrl, channelId, effectiveChannelFilters(channelId))
        }
        logger.d { "Re-applied ${channelFilters.size} channels to relay ${scrubUrlForLogs(relayUrl)}" }
    }

    /**
     * Clears all data from both databases (public and encrypted).
     */
    override suspend fun clearAllData() {
        withContext(Dispatchers.IO) {
            // Prevent any new inserts while wiping databases
            isWiping.set(true)
            try {
            // Ensure no further network activity or background inserts will repopulate DB
            try {
                disconnectFromAll()
            } catch (e: Exception) {
                logger.e(e) { "disconnectFromAll failed during clearAllData; continuing wipe" }
            }

            // Cancel any pending insert batch job and clear pending inserts queue
            eventIngestCache.cancelPendingInserts()

            // Cancel any pending snapshot emit job
            eventIngestCache.cancelPendingSnapshotEmit()

            // Cancel any scheduled channel/history jobs that could re-subscribe
            pendingChannelJobs.values.forEach { it.cancel() }
            pendingChannelJobs.clear()
            channelFilters.clear()
            lastChannelFingerprints.clear()
            channelOverlays.clear()
            lastOverlayFingerprints.clear()

            historyPageJobs.values.forEach { it.cancel() }
            historyPageJobs.clear()
            pageRequestFingerprint.clear()

            channelOverlayJobs.values.forEach { it.cancel() }
            channelOverlayJobs.clear()
            overlayRequestFingerprint.clear()

            // Single (encrypted) database now — see AUDIT.md: everyone else's events are
            // in-memory only, never persisted; there is no second, unencrypted database.
            encryptedDatabase.withTransaction {
                encryptedDatabase.eventDao().deleteAll()
                encryptedDatabase.eventTagDao().deleteAll()
                encryptedDatabase.userProfileDao().deleteAll()
                encryptedDatabase.relayDao().deleteAll()
                encryptedDatabase.feedFilterDao().deleteAll()
            }

            // Clear in-memory caches
            eventIngestCache.clearAll()
            seenEventIds.clear()
            clearEventTagsCache()
            logger.d { "All databases cleared" }
            } finally {
                isWiping.set(false)
            }
        }
    }

    override fun clearBackfillAnchors(pubkey: String) {
        backfillAnchorStore.clear(pubkey)
    }

    override fun isAuthorOutboxKnown(pubkey: String): Boolean {
        return authorsWithKnownOutbox.contains(pubkey.lowercase())
    }

    override fun activateUserSession(pubkey: String?, feedFilter: FeedFilter, authors: Set<String>) {
        activeUserPubkey = pubkey
        activeFeedFilter = feedFilter
        activeSessionAuthors = authors.mapTo(HashSet(authors.size)) { it.lowercase() }
        recomputeFeedAuthorsPerRelay()
        val namespace = if (!pubkey.isNullOrBlank()) pubkey.take(12).lowercase() else "anon"
        setSubscriptionNamespace(namespace)
        // Push new channels to every relay that is already connected
        applySessionChannelsToRelay()
        scheduleNegentropySync()
    }

    // NIP-77: makes sure the signed-in user's own event history is fully synced across their
    // write-relay set. NegentropySyncOrchestrator itself is generic over any EventFilter/local
    // snapshot — the "own events only, not a general backfill feature" scoping is a deliberate
    // decision made HERE (authors={pubkey}, kinds=USEFUL_PERSISTED_KINDS below), not a limitation
    // of the orchestrator. Debounced the same way as scheduleAuthorsPerRelayRefresh() so a
    // relay-list change right after login doesn't retrigger a sync storm; NegentropySyncOrchestrator
    // itself also skips a relay it's already syncing.
    private var negentropySyncJob: Job? = null
    private val negentropySyncOrchestrator by lazy {
        NegentropySyncOrchestrator(nostrClient, encryptedDatabase.eventDao(), repoScope, eventChannelRouting::removeSubscriptionInfo)
    }

    // Channel/subscription-routing collaborator — manually instantiated, following
    // negentropySyncOrchestrator's precedent above, with the same shared-mutable-state instances
    // this facade retains (never copies) so writes from either side stay mutually visible.
    private val eventChannelRouting by lazy {
        EventChannelRouting(
            nostrClient,
            connectedRelays,
            feedSinceByRelay,
            outboxInboxSinceByRelay,
            eventLookupTriedByRelay,
            authorHydrationTriedByRelay,
            discoveredRelayLastNeededAtMillis,
            _relayRequestsFlow,
            { activeSessionAuthors },
            { feedAuthorsPerRelay },
            { authorsWithKnownOutbox }
        )
    }

    private fun scheduleNegentropySync() {
        negentropySyncJob?.cancel()
        negentropySyncJob = repoScope.launch {
            delay(NEGENTROPY_SYNC_DEBOUNCE_MS)
            val pubkey = activeUserPubkey?.let { NostrValidation.validate64HexOrNull(it) } ?: return@launch
            val writeRelayUrls = userRepository.getRelayList(pubkey)?.getOutboxRelays().orEmpty()
            if (writeRelayUrls.isEmpty()) return@launch
            val writeRelays = writeRelayUrls.mapNotNull { connectedRelays[normalizeRelayUrl(it)] }
            if (writeRelays.isEmpty()) return@launch
            // The actual "own events only" scoping decision lives here, not inside
            // NegentropySyncOrchestrator (which is generic over any EventFilter/local snapshot) —
            // see its doc comment.
            negentropySyncOrchestrator.sync(
                relays = writeRelays,
                filter = EventFilter(authors = setOf(pubkey), kinds = USEFUL_PERSISTED_KINDS),
                direction = syncPreferences.getNegentropySyncDirection(),
                localItems = {
                    encryptedDatabase.eventDao().getEventIdsAndTimestampsByPubkey(pubkey, USEFUL_PERSISTED_KINDS)
                        .map { NegentropyItem(it.id, it.createdAt) }
                }
            )
        }
    }

    private fun recomputeFeedAuthorsPerRelay() {
        if (activeSessionAuthors.isEmpty()) {
            feedAuthorsPerRelay = emptyMap()
            authorsWithKnownOutbox = emptySet()
            return
        }
        feedAuthorsPerRelay = computeAuthorsPerRelay(
            followedPubkeys = activeSessionAuthors,
            outboxRelaysFor = { pubkey -> userRepository.getRelayList(pubkey)?.getOutboxRelays().orEmpty() },
            hintRelaysFor = { pubkey -> relayHintsByPubkey[pubkey]?.toList().orEmpty() }
        )
        authorsWithKnownOutbox = feedAuthorsPerRelay.values.flatten().toHashSet()
    }

    private fun reapplyPreciseRoutedChannels() {
        reconnectRelevantDiscoveredRelays()
        PRECISE_ROUTED_CHANNEL_IDS.forEach { channelId ->
            if (channelFilters[channelId].isNullOrEmpty()) return@forEach
            val filters = effectiveChannelFilters(channelId)
            connectedRelays.keys.forEach { relayUrl -> eventChannelRouting.applyChannelToRelay(relayUrl, channelId, filters) }
        }
    }

    /**
     * Counterpart to sweepIdleDiscoveredRelays(): a discovered relay the idle sweep disconnected
     * doesn't change the relay *set*, so NostrSessionManager's relay-set-change reconcile never
     * fires for it on its own — nothing else would ever reconnect it once its covered author
     * becomes relevant again. Called every time feedAuthorsPerRelay is recomputed (i.e. right
     * before reapplying precisely-routed channels), so a discovered relay whose author just
     * started being requested again reconnects immediately instead of staying idle forever.
     */
    private fun reconnectRelevantDiscoveredRelays() {
        val relevantRelayUrls = feedAuthorsPerRelay.keys
        if (relevantRelayUrls.isEmpty()) return
        connectedRelays.values.toList().forEach { relay ->
            if (!relay.isDiscovered) return@forEach
            if (normalizeRelayUrl(relay.url) !in relevantRelayUrls) return@forEach
            if (nostrClient.isConnected(relay.url) || nostrClient.hasActiveSocket(relay.url)) return@forEach
            logger.d { "Reconnecting idle discovered relay now covering a requested author" }
            nostrClient.connect(relay.url)
            discoveredRelayLastNeededAtMillis[relay.url] = System.currentTimeMillis()
        }
    }

    // Debounced: a burst of followed authors' NIP-65 events arriving together (e.g. right after
    // activateUserSession while profile hydration is still catching up) should only trigger one
    // recompute+reapply, not one per event.
    private fun scheduleAuthorsPerRelayRefresh() {
        authorsPerRelayRefreshJob.getAndSet(
            repoScope.launch {
                delay(CHANNEL_RESUBSCRIBE_DEBOUNCE_MS)
                recomputeFeedAuthorsPerRelay()
                reapplyPreciseRoutedChannels()
            }
        )?.cancel()
    }

    private fun applySessionChannelsToRelay() {
        val now = System.currentTimeMillis() / 1000
        // Validate and normalize pubkey to hex-64 lowercase
        val pubkey = activeUserPubkey?.let { NostrValidation.validate64HexOrNull(it) }
            ?.takeIf { !ANON_PUBKEY_REGEX.matches(it) }
        // Feed kinds: notes shown in timeline. Includes NIP-09 deletion requests — otherwise
        // they're never requested from relays at all (a REQ with an explicit `kinds` filter
        // only returns matching kinds), so applyIncomingDeletion() below would never see a
        // deletion published by anyone other than the currently-signed-in user's own client.
        // Also includes NIP-18 reposts (kind 6/16) — a followed author's repost of someone else's
        // note needs its own REQ the same way their own notes do; without this, a repost only
        // ever showed up if its *target* note happened to already be visible (fetched purely as
        // an engagement-count signal via BuildEngagementFiltersUseCase, never rendered as its own
        // feed item). See selectHybridFeedNotes/buildIndexedNoteViews for the unwrap+dedup step.
        val feedKinds = setOf(Event.KIND_TEXT_NOTE, Event.KIND_EVENT_DELETION, Event.KIND_REPOST, Event.KIND_GENERIC_REPOST)
        // Profile kinds: metadata of logged user.
        val profileKinds = setOf(Event.KIND_METADATA)
        // User social graph kinds (replaceable): follows, mute list, relay list, search/index
        // relay lists, DM relay list
        val socialGraphKinds = setOf(
            Event.KIND_CONTACT_LIST,       // 3  — NIP-02 follows
            Event.KIND_MUTED_USERS,        // 10000 — NIP-51 mute list
            Event.KIND_RELAY_LIST_METADATA, // 10002 — NIP-65 relay list
            Event.KIND_SEARCH_RELAYS,      // 10007 — NIP-51 search relay list
            Event.KIND_DM_RELAY_LIST,      // 10050 — NIP-17 DM relay list
            Event.KIND_INDEX_RELAYS,       // 10086 — index relay list
            Event.KIND_BLOSSOM_SERVER_LIST // 10063 — BUD-03 Blossom server list
        )
        // Interaction kinds: non-note events for engagement context (replies are already covered
        // by feedKinds/kind:1 above, so they're deliberately not repeated here). Own-authored
        // interactions (outbox) can only ever be repost/reaction — a zap receipt (kind 9735, NIP-57)
        // is authored by the recipient's zap/LNURL service, never by the zapper, so authors={me}
        // structurally can never match one. The inbox set adds it back in since it's #p-tagged with
        // the recipient (matches the #p={me} tag filter) — without it, zaps received never surfaced.
        val ownInteractionKinds = setOf(Event.KIND_REPOST, Event.KIND_REACTION)
        val inboxInteractionKinds = ownInteractionKinds + Event.KIND_ZAP_RECEIPT

        // Outbox profile/notes/interactions subscriptions: always author=logged user.
        if (!pubkey.isNullOrBlank()) {
            subscribeChannel(NostrChannels.OUTBOX_PROFILE, listOf(
                EventFilter(
                    kinds = profileKinds,
                    authors = setOf(pubkey),
                    limit = 1
                ),
                EventFilter(
                    kinds = socialGraphKinds,
                    authors = setOf(pubkey),
                    // Some relays apply a strict global cap for the channel response.
                    // Keep this limit aligned with the full OUTBOX_PROFILE kind set.
                    limit = OutboxProfilePolicy.socialGraphLimit(profileKinds, socialGraphKinds)
                )
            ))
            // outbox-notes: one subscription, two filters — notes/deletions (no `since`, relay
            // returns the user's latest N notes, full history per relay) and reactions/reposts
            // (windowed). Formerly two separate channels/subscriptions; merged since both are
            // authors={me} (see NostrChannels.OUTBOX_NOTES doc comment).
            subscribeChannel(NostrChannels.OUTBOX_NOTES, listOf(
                EventFilter(
                    kinds = feedKinds,
                    authors = setOf(pubkey),
                    limit = 80
                ),
                EventFilter(
                    kinds = ownInteractionKinds,
                    authors = setOf(pubkey),
                    since = now - FEED_SINCE_SECONDS,
                    limit = 80
                )
            ))
        } else {
            clearChannel(NostrChannels.OUTBOX_PROFILE)
            clearChannel(NostrChannels.OUTBOX_NOTES)
        }

        // feed-notes: use cached since (skip already-stored events) or fall back to initial window.
        // When scoped to follows, request only those authors instead of an unscoped firehose —
        // this is the actual NIP-65 "outbox model" read-side scoping; see activateUserSession().
        // A large follow list is split into multiple filters within the same REQ (chunked by
        // MAX_AUTHORS_PER_FEED_FILTER) rather than one filter with an unbounded authors list.
        // cachedFeedSince is derived from a cached/stored event's created_at (connectToEnabledRelays),
        // which is relay/author-controlled data — a bogus or clock-skewed future timestamp there
        // must not leak into the REQ filter, or the feed subscription silently stops matching
        // anything until real time catches up to it.
        val feedSince = (cachedFeedSince ?: (now - INITIAL_FEED_WINDOW_SECS)).coerceAtMost(now)
        val feedNotesFilters = if (activeSessionAuthors.isEmpty()) {
            listOf(EventFilter(kinds = feedKinds, since = feedSince, limit = 100))
        } else {
            activeSessionAuthors.chunked(MAX_AUTHORS_PER_FEED_FILTER).map { authorChunk ->
                EventFilter(
                    kinds = feedKinds,
                    authors = authorChunk.toSet(),
                    since = feedSince,
                    limit = 100
                )
            }
        }
        subscribeChannel(NostrChannels.FEED_NOTES, feedNotesFilters)

        // Inbox notes subscription: one subscription, two filters — notes/deletions and
        // reactions/reposts that #p-tag the logged-in user. Formerly two separate
        // channels/subscriptions; merged since both share the same #p={me} tag filter (see
        // NostrChannels.INBOX_NOTES doc comment).
        if (!pubkey.isNullOrBlank()) {
            val pTag = mapOf("p" to setOf(pubkey))  // Validated hex-64 pubkey for #p tag
            subscribeChannel(NostrChannels.INBOX_NOTES, listOf(
                EventFilter(
                    kinds = feedKinds,
                    tagFilters = pTag,
                    since = now - NOTIF_SINCE_SECONDS,
                    limit = 120
                ),
                EventFilter(
                    kinds = inboxInteractionKinds,
                    tagFilters = pTag,
                    since = now - NOTIF_SINCE_SECONDS,
                    limit = 120
                )
            ))
        } else {
            clearChannel(NostrChannels.INBOX_NOTES)
        }
    }

    override fun setSubscriptionNamespace(namespace: String) {
        val normalized = namespace
            .lowercase()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(16)
            .ifBlank { "anon" }
        if (subscriptionNamespace != normalized) {
            subscriptionNamespace = normalized
            nostrClient.resetSubscriptionBookkeeping()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun subscribeToEvents(filters: List<EventFilter>): Flow<Event> {
        if (filters.isNotEmpty()) {
            currentFilters = filters
            subscribeChannel(NostrChannels.DEFAULT_EVENTS, filters)
        }

        return nostrClient.eventFlow
            // NOT .conflate(): this transform is where relay lists, profiles, and every
            // persistable event get durably saved (kind:10002/10007/10086/0, scheduleInsert, the
            // in-memory cache) — the one place that's supposed to see every event regardless of
            // which screen is open (see the KIND_METADATA branch's doc comment below).
            // .conflate() is buffer(Channel.CONFLATED): a same-capacity-1 buffer that *overwrites*
            // an unconsumed value when a new one arrives — under a burst (e.g. backfill delivering
            // many notes across several relays near-simultaneously), an event landing here while
            // this transform is still busy with an earlier one is silently dropped, not delayed —
            // no error, no log, it just never gets processed until the next time a relay happens
            // to redeliver it (a resubscribe/reconnect, if it's a replaceable kind). A generous
            // bounded buffer queues instead of dropping, while still decoupling the WebSocket
            // read loop from this transform's processing speed the way .conflate() was there for.
            .buffer(capacity = 10_000)
            // flatMapMerge (not .transform, which processes one event at a time on a single
            // coroutine): up to EVENT_PROCESSING_CONCURRENCY events verify+persist in parallel, so
            // a burst from one relay can no longer head-of-line-block an unrelated event that
            // already arrived from a different relay (see EVENT_PROCESSING_CONCURRENCY's doc
            // comment — this is what made fetchEventById()'s hint-relay lookups resolve slowly).
            .flatMapMerge(concurrency = EVENT_PROCESSING_CONCURRENCY) { (relayUrl, rawEvent) ->
                flow<Event> {
                val event = eventIngestCache.normalizeIncomingEvent(rawEvent)

                if (event.kind == Event.KIND_RELAY_LIST_METADATA) {
                    logger.d { "Received kind:10002 for pubkey=${event.pubkey.take(8)}... tags=${event.tags.size}" }
                }

                // The same event commonly arrives once per matching subscription AND once per
                // connected relay (see the .buffer() doc comment above) — a redelivery of an id
                // already verified this session skips the expensive BIP-340 check entirely.
                val alreadyVerified = verifiedEventIds.contains(event.id)
                // CPU-bound (BouncyCastle Schnorr) — off the flow-collecting coroutine so
                // concurrent flatMapMerge branches actually verify in parallel across cores,
                // not just interleave on one.
                val verified = alreadyVerified || withContext(Dispatchers.Default) { EventCrypto.verifyEvent(event) }
                if (verified && !alreadyVerified) {
                    verifiedEventIds.add(event.id)
                    if (verifiedEventIds.size > MAX_VERIFIED_IDS) {
                        verifiedEventIds.clear()
                        verifiedEventIds.add(event.id)
                    }
                }
                if (!verified) {
                    if (event.kind == Event.KIND_RELAY_LIST_METADATA) {
                        logger.d { "Dropped kind:10002 for pubkey=${event.pubkey.take(8)}...: failed verifyEvent" }
                    }
                    return@flow
                }

                if (event.kind == Event.KIND_EVENT_DELETION) {
                    eventIngestCache.applyIncomingDeletion(event)
                    if (isCurrentUserPubkey(event.pubkey) && seenEventIds.add(event.id)) {
                        eventIngestCache.scheduleInsert(event.toEntity(), event.toTagEntities())
                    }
                    return@flow
                }

                if (event.kind == Event.KIND_RELAY_LIST_METADATA) {
                    val parsed = RelayListMetadata.fromEvent(event)
                    runCatching {
                        userRepository.saveRelayList(parsed)
                    }.onSuccess {
                        logger.d {
                            "Saved kind:10002 for pubkey=${event.pubkey.take(8)}...: " +
                                "${parsed.writeRelays.size} write, ${parsed.readRelays.size} read, ${parsed.allRelays.size} unmarked"
                        }
                    }.onFailure { e ->
                        logger.d { "Failed to save kind:10002 for pubkey=${event.pubkey.take(8)}...: ${scrubThrowableMessageForLogs(e)}" }
                    }
                    // A tracked author's outbox relays just changed — refresh precise routing
                    // rather than waiting for the next activateUserSession() call.
                    if (event.pubkey.lowercase() in activeSessionAuthors) {
                        scheduleAuthorsPerRelayRefresh()
                    }
                    // This is the currently-open profile screen's author (see
                    // pinProfileAuthorForPersistence) — dial its just-learned relays immediately
                    // rather than waiting for the next periodic connectToEnabledRelays() pass.
                    // Closes the cold-start gap BackfillProfileUseCase's own proactive dial can't:
                    // this is the case where the relay list wasn't already known when that ran, so
                    // this arriving kind:10002 (from whichever relay happened to have it) is the
                    // first chance to actually reach this author's real relays at all.
                    if (event.pubkey.lowercase() in pinnedProfileAuthors) {
                        connectToRelayHints(parsed.getAllDeclaredRelays())
                    }
                }

                if (event.kind == Event.KIND_DM_RELAY_LIST) {
                    runCatching {
                        userRepository.saveDmRelayList(DmRelayList.fromEvent(event))
                    }
                }

                if (event.kind == Event.KIND_BLOSSOM_SERVER_LIST) {
                    runCatching {
                        userRepository.saveServerList(UserServerList.fromEvent(event))
                    }
                }

                // Search/index relay lists broaden relay *discovery* the same way kind:10002's
                // outbox relays already do — any relay a tracked author declares in either list
                // is a real relay worth reaching, not just their outbox. See
                // UserRepositoryImpl.saveSearchRelaysList/saveIndexRelaysList.
                if (event.kind == Event.KIND_SEARCH_RELAYS) {
                    extractSearchRelaysList(event)?.let { parsed ->
                        runCatching { userRepository.saveSearchRelaysList(parsed) }
                    }
                }

                if (event.kind == Event.KIND_INDEX_RELAYS) {
                    extractIndexRelaysList(event)?.let { parsed ->
                        runCatching { userRepository.saveIndexRelaysList(parsed) }
                    }
                }

                if (event.kind == Event.KIND_METADATA) {
                    // Derived here (not just left to whichever ViewModel happens to be collecting
                    // this flow) so profile hydration doesn't depend on a hot, non-replay
                    // SharedFlow delivery landing while that specific screen is alive — the same
                    // durability guarantee kind:10002/10050 already get above.
                    runCatching {
                        userRepository.saveProfile(UserProfile.fromJSON(event.pubkey, event.content, event.createdAt))
                    }
                }

                if (!eventIngestCache.shouldPersistEvent(event)) {
                    return@flow
                }

                // Deduplicate: the same event can arrive from several relays simultaneously
                if (!seenEventIds.add(event.id)) {
                    eventIngestCache.recordRelayForSeenEvent(event.id, relayUrl)
                    return@flow
                }

                // Keep the deduplication set bounded (evict oldest by ID insertion order is not
                // guaranteed in ConcurrentHashMap, so simply clear at max to prevent OOM)
                if (seenEventIds.size > MAX_SEEN_IDS) {
                    seenEventIds.clear()
                    seenEventIds.add(event.id)
                    logger.d { "Deduplication set cleared (reached $MAX_SEEN_IDS entries)" }
                }

                val currentUserPubkey = currentUserArchivePubkey()
                val (cacheSize, storedInMemoryCache) = eventIngestCache.ingest(event, relayUrl, currentUserPubkey)
                    .let { it.cacheSize to it.storedInMemoryCache }
                eventIngestCache.cacheVerifiedRepostTarget(event, currentUserPubkey)
                val entity = event.toEntity()
                val tags = event.toTagEntities()
                eventIngestCache.scheduleInsert(entity, tags, event.replaceableKey())
                if (storedInMemoryCache) {
                    eventIngestCache.enqueueSnapshotEvent(event)
                    eventIngestCache.scheduleSnapshotEmit()
                }

                val loggedCount = eventLogCounter.incrementAndGet()
                if (loggedCount % 100 == 0) {
                    logger.d { "Event stream active: +$loggedCount events (cache=$cacheSize)" }
                }

                emit(event)
                }
            }
    }

    override fun pinProfileAuthorForPersistence(pubkey: String) {
        val normalized = NostrValidation.validate64HexOrNull(pubkey)?.lowercase() ?: return
        pinnedProfileAuthors.add(normalized)
    }

    override fun unpinProfileAuthorForPersistence(pubkey: String) {
        val normalized = NostrValidation.validate64HexOrNull(pubkey)?.lowercase() ?: return
        pinnedProfileAuthors.remove(normalized)
    }

    override fun noteReferencedAuthor(pubkey: String): Boolean {
        val normalized = NostrValidation.validate64HexOrNull(pubkey)?.lowercase() ?: return false
        synchronized(referencedAuthorsLock) {
            val isNew = !referencedAuthorsOrder.remove(normalized)
            referencedAuthorsOrder.add(normalized) // re-insert at the end = most-recently-used
            while (referencedAuthorsOrder.size > MAX_REFERENCED_AUTHORS) {
                referencedAuthorsOrder.iterator().let { it.next(); it.remove() }
            }
            return isNew
        }
    }

    // shouldPersistEvent/cacheVerifiedRepostTarget moved to EventIngestCache.kt — see
    // eventIngestCache.shouldPersistEvent/cacheVerifiedRepostTarget, called from
    // subscribeToEvents below.

    /**
     * [resolveFeedEvents] plus the fallback this codebase's quote resolution already has and
     * plain repost resolution didn't: for each repost whose target isn't resolvable via
     * [eventsById] right now, kick off a bounded, fire-and-forget relay lookup
     * ([scheduleRepostTargetFetch]) instead of just letting it stay dropped forever. Centralized
     * here (not duplicated at each of the three call sites — home feed, and both branches of
     * observeProfileNotes) since all three need the exact same fallback.
     */
    private fun resolveFeedEventsAndScheduleFetches(
        selected: List<Event>,
        eventsById: (String) -> Event?
    ): FeedEventResolution {
        val result = resolveFeedEvents(selected, eventsById)
        result.unresolvedReposts.forEach { repost ->
            val target = extractRepostTarget(repost)
            target.eventId?.let { targetId -> scheduleRepostTargetFetch(targetId, target.relayHint) }
        }
        return result
    }

    /**
     * Fire-and-forget relay lookup for a repost target that's neither cached nor (for a self-
     * repost) in Room — same eventual mechanism a quoted note's unresolved reference already uses
     * (fetchEventById via the pooled EVENT_LOOKUP channel). Must not suspend the caller:
     * fetchEventById can take up to its full timeout (default 5s), and this is called from
     * reactive Flow map()/combine() steps that need to keep emitting promptly regardless of
     * whether this particular lookup ever succeeds.
     *
     * At most one attempt per target id per app session — repostTargetFetchAttempted persists
     * across calls (unlike pendingEventLookupIds/eventLookupTriedByRelay, which are scoped to a
     * single in-flight fetchEventById call and get purged the moment it completes; reusing those
     * here would re-fire a fresh relay REQ for a permanently-unresolvable target on every
     * unrelated recompute of whichever flow called this).
     */
    private fun scheduleRepostTargetFetch(targetId: String, relayHint: String?) {
        if (!repostTargetFetchAttempted.add(targetId)) return
        if (repostTargetFetchAttempted.size > MAX_REPOST_TARGET_FETCH_ATTEMPTS) {
            repostTargetFetchAttempted.clear()
            repostTargetFetchAttempted.add(targetId)
        }
        repoScope.launch {
            fetchEventById(targetId, relayHints = listOfNotNull(relayHint))
        }
    }

    /**
     * Resolves the current user's own reposts ([ownReposts], already collapsed to latest-per-
     * target) into [NoteView]s — the own-profile analog of observeProfileNotes' non-self branch.
     * A repost target is resolved via [getEventById] first, which already covers both the
     * in-memory L1 cache (a repost of someone ELSE's note — see cacheVerifiedRepostTarget) and
     * Room's own-archive fallback (a repost of the current user's OWN note); anything still
     * unresolved falls back to a scheduled relay lookup via
     * [resolveFeedEventsAndScheduleFetches]/[scheduleRepostTargetFetch].
     *
     * [getEventById] is suspend, so target resolution has to happen here as its own pass (a plain
     * `Map<String, Event>`) rather than inside resolveFeedEvents' synchronous `eventsById` lookup
     * — [cachedEvents] alone (unlike [allCachedAndResolvedEvents]'s combined form) never contains
     * the current user's own events, so a self-repost-of-own-note target must be added in
     * explicitly or it would resolve via Room here but then fail to re-resolve inside
     * buildCachedNoteViews' own internal (allEvents-only) lookup.
     */
    private suspend fun resolveOwnRepostNoteViews(
        ownReposts: List<Event>,
        cachedEvents: List<Event>
    ): Pair<List<NoteView>, List<PendingRepost>> {
        val targetIds = ownReposts.mapNotNull {
            extractRepostTarget(it).eventId?.takeIf(String::isNotBlank)
        }.distinct()

        val resolvedTargets = HashMap<String, Event>(targetIds.size)
        targetIds.forEach { id -> getEventById(id)?.let { resolvedTargets[id] = it } }

        val allCachedAndResolvedEvents = cachedEvents + resolvedTargets.values
        val eventsById = allCachedAndResolvedEvents.associateBy { it.id }
        val resolution = resolveFeedEventsAndScheduleFetches(ownReposts) { eventsById[it] }
        val pendingReposts = toPendingReposts(resolution.unresolvedReposts)
        if (resolution.resolved.isEmpty()) return emptyList<NoteView>() to pendingReposts

        val neededPubkeys = (
            resolution.resolved.map { it.targetEvent.pubkey } + resolution.resolved.mapNotNull { it.repostedByPubkey }
            ).distinct()
        val profiles = userRepository.getProfilesByPubkey(neededPubkeys)

        return buildCachedNoteViews(allCachedAndResolvedEvents, profiles, ownReposts) to pendingReposts
    }

    // normalizeIncomingEvent moved to EventIngestCache.kt — see
    // eventIngestCache.normalizeIncomingEvent, called from subscribeToEvents below.

    override fun subscribeChannel(channelId: String, filters: List<EventFilter>) {
        if (filters.isEmpty()) {
            clearChannel(channelId)
            return
        }

        channelFilters[channelId] = filters

        val fingerprint = eventChannelRouting.fingerprint(filters)
        if (lastChannelFingerprints[channelId] == fingerprint) {
            return
        }
        lastChannelFingerprints[channelId] = fingerprint
        scheduleChannelApply(channelId)
    }

    /**
     * Sets (or, with an empty list, clears) [channelId]'s overlay filters — see [channelOverlays]'
     * doc comment. [channelId] must already have its own base filters from [subscribeChannel]; an
     * overlay on a channel with no base is a no-op (nothing to layer onto).
     */
    override fun setChannelOverlay(channelId: String, overlayFilters: List<EventFilter>) {
        if (overlayFilters.isEmpty()) {
            if (channelOverlays.remove(channelId) == null) return
            lastOverlayFingerprints.remove(channelId)
            scheduleChannelApply(channelId)
            return
        }

        channelOverlays[channelId] = overlayFilters

        val fingerprint = eventChannelRouting.fingerprint(overlayFilters)
        if (lastOverlayFingerprints[channelId] == fingerprint) {
            return
        }
        lastOverlayFingerprints[channelId] = fingerprint
        scheduleChannelApply(channelId)
    }

    private fun effectiveChannelFilters(channelId: String): List<EventFilter> {
        val overlay = channelOverlays[channelId]
        val base = channelFilters[channelId].orEmpty()
        return if (overlay.isNullOrEmpty()) base else base + overlay
    }

    override fun clearChannel(channelId: String) {
        pendingChannelJobs.remove(channelId)?.cancel()
        channelFilters.remove(channelId)
        lastChannelFingerprints.remove(channelId)
        channelOverlays.remove(channelId)
        lastOverlayFingerprints.remove(channelId)
        // authorHydrationLastSentByRelay is keyed by (relayUrl, channelId) — one-shot hydration
        // channels (referenced-author batches, profile-follows-meta-<pk>) mint a fresh channelId
        // per use and never reuse it, so without this every such channel leaves a permanent
        // (relayUrl, channelId) entry behind instead of being reclaimed when the channel closes.
        authorHydrationLastSentByRelay.keys.removeIf { it.second == channelId }

        connectedRelays.keys.forEach { relayUrl ->
            val subId = nostrClient.clearChannelSubscription(relayUrl, channelId)
            if (subId != null) {
                eventChannelRouting.removeSubscriptionInfo(relayUrl, subId)
            }
        }
    }

    private fun snapshotChannelRelaySubs(channelId: String): Set<Pair<String, String>> =
        nostrClient.subscriptionsForChannel(channelId)

    override suspend fun awaitChannelEoseOrTimeout(channelId: String, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            // subscribeChannel() debounces (CHANNEL_RESUBSCRIBE_DEBOUNCE_MS) before actually
            // applying to relays, so snapshotChannelRelaySubs may return empty immediately
            // after subscribing — give it a moment before treating "no relays" as "nothing to
            // wait for" (which would close the channel before the REQ was even sent).
            var expected = snapshotChannelRelaySubs(channelId)
            var waitedMs = 0L
            val debounceGraceMs = CHANNEL_RESUBSCRIBE_DEBOUNCE_MS * 3
            while (expected.isEmpty() && waitedMs < debounceGraceMs) {
                delay(50L)
                waitedMs += 50L
                expected = snapshotChannelRelaySubs(channelId)
            }
            if (expected.isEmpty()) return@withTimeoutOrNull

            val remaining = expected.toMutableSet()
            nostrClient.eoseFlow
                .transformWhile { eoseSignal ->
                    remaining.remove(eoseSignal.relayUrl to eoseSignal.subscriptionId)
                    emit(Unit)
                    remaining.isNotEmpty()
                }
                .collect()
        }
    }

    override fun getCachedEvents(): Flow<List<Event>> {
        return eventIngestCache.cachedEventsFlow
    }

    // observeRecentEvents(limit) is independently collected, at the SAME limit, by several
    // long-lived subscribers at once — MuteListRepositoryImpl/PinListRepositoryImpl/
    // ContactListRepositoryImpl all request limit=4000 for the whole app session. Each
    // independent collection was its own cold Flow, so every write to the `events` table re-ran
    // Room's query, the full entity->domain remap, AND mergeHybridEvents' sort/dedupe over up to
    // `limit` events once per active collector at that limit — the same "N cold collectors of one
    // Room query" cost getAllRelays() had, and the same shareIn fix. Keyed per distinct
    // *effective* limit (see CANONICAL_RECENT_EVENTS_LIMIT) rather than per requested limit, so
    // callers asking for fewer than the canonical ceiling share that one query too instead of
    // each running their own.
    private val recentEventsFlowCache = ConcurrentHashMap<Int, Flow<List<Event>>>()

    override fun observeRecentEvents(limit: Int): Flow<List<Event>> {
        val effectiveLimit = maxOf(limit, CANONICAL_RECENT_EVENTS_LIMIT)
        val sharedFlow = recentEventsFlowCache.getOrPut(effectiveLimit) {
            combine(
                eventIngestCache.cachedEventsFlow,
                encryptedEventDao.observeRecentEvents(effectiveLimit)
            ) { cached, encryptedEntities ->
                mergeHybridEvents(
                    cachedEvents = cached,
                    encryptedEvents = encryptedEntities.map { it.toDomain() },
                    currentUserPubkey = currentUserArchivePubkey(),
                    limit = effectiveLimit
                )
            }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .shareIn(
                    scope = repoScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    replay = 1
                )
        }
        return if (limit == effectiveLimit) sharedFlow else sharedFlow.map { it.take(limit) }
    }

    // Search-dedicated candidate source: unlike observeRecentEvents (shared across Mute/Contact/
    // Pin-list consumers, capped at CANONICAL_RECENT_EVENTS_LIMIT=4000 for their sake — a "show
    // the most recent N" window), search must be able to match anything actually resident in the
    // in-memory cache, up to its real MAX_IN_MEMORY_EVENT_CACHE ceiling — "find within everything
    // cached." Ordinary feed/engagement traffic kept pushing older search matches out of the
    // shared 4000-item window long before the 50k cache was ever close to full, which made search
    // results silently disappear as the user kept browsing. Deliberately not cached via
    // recentEventsFlowCache/shareIn — this is only collected while the search panel is actually
    // open, so a fresh cold flow per call avoids paying its cost as a permanent background one.
    private fun observeSearchCandidateEvents(): Flow<List<Event>> =
        combine(
            eventIngestCache.cachedEventsFlow,
            encryptedEventDao.observeRecentEvents(MAX_IN_MEMORY_EVENT_CACHE)
        ) { cached, encryptedEntities ->
            mergeHybridEvents(
                cachedEvents = cached,
                encryptedEvents = encryptedEntities.map { it.toDomain() },
                currentUserPubkey = currentUserArchivePubkey(),
                limit = MAX_IN_MEMORY_EVENT_CACHE
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override suspend fun getEventById(id: String): Event? =
        withContext(Dispatchers.IO) {
            eventIngestCache.getCached(id)
                ?: encryptedEventDao.getEventById(id)?.toDomain()
                    ?.takeIf { isCurrentUserPubkey(it.pubkey) }
        }

    override suspend fun getEventRelays(eventId: String): Set<String> =
        eventIngestCache.getCachedRelays(eventId)

    override suspend fun fetchEventById(id: String, timeoutMs: Long, relayHints: List<String>): Event? {
        getEventById(id)?.let { return it }
        if (!NostrValidation.is64HexValid(id)) return null

        // Pooled: every concurrent fetchEventById() caller adds its id to the same pending set and
        // shares one REQ per relay (NostrChannels.EVENT_LOOKUP) instead of opening its own — a
        // thread/feed rendering several quoted notes at once used to open one subscription per
        // quote. subscribeChannel()'s existing debounce+fingerprint check coalesces ids added
        // within the same window into a single reapply.
        pendingEventLookupIds += id
        applyEventLookupPool()
        try {
            if (relayHints.isEmpty()) {
                awaitChannelEoseOrTimeout(NostrChannels.EVENT_LOOKUP, timeoutMs)
            } else {
                // A hint is fresh, specific evidence this relay has the event — worth asking
                // again even if an earlier, unrelated lookup already got a "doesn't have it" EOSE
                // from it (eventLookupTriedByRelay's exclusion is pool-wide and sticky, with no
                // way on its own to know a hint arrived later for the same id). Must run before
                // connectToRelayHints(): for a hint relay that's already connected, connect() is
                // a no-op (see its own guard), so nothing would otherwise trigger a fresh REQ.
                reviveTriedHintRelaysForLookup(id, relayHints)

                // awaitChannelEoseOrTimeout snapshots which relays it's waiting on right when it's
                // called — a hint relay dialed by connectToRelayHints() almost always hasn't
                // finished its Tor handshake yet at that instant, so it's never part of that
                // snapshot and an EOSE-driven early return here would resolve before the hint
                // relay is even asked. Poll instead: cheap (local cache/DB read), and correctly
                // keeps waiting up to the extended budget regardless of when the hint relay joins.
                connectToRelayHints(relayHints)
                val effectiveTimeoutMs = maxOf(timeoutMs, EVENT_LOOKUP_HINT_TIMEOUT_MS)
                withTimeoutOrNull(effectiveTimeoutMs) {
                    while (getEventById(id) == null) {
                        delay(EVENT_LOOKUP_HINT_POLL_INTERVAL_MS)
                    }
                }
            }
            // The event, if found, arrived via the normal ingestion pipeline while we waited above
            // and is already in the cache/archive by now.
            return getEventById(id)
        } finally {
            pendingEventLookupIds -= id
            applyEventLookupPool()
        }
    }

    /**
     * Best-effort immediate connect to [relayUrls] (NIP-19 relay hints) — see the interface doc
     * comment for why this bypasses the normal debounced relay-set reconcile. Filters out blank
     * and local-network URLs (same safety bar as UserRepositoryImpl.addDiscoveredRelays) and caps
     * the count so a maliciously long hint list can't fire an unbounded dial burst. Fire-and-
     * forget: nostrClient.connect() itself returns immediately and continues the actual
     * handshake asynchronously (see connectToEnabledRelays' matching comment) — any relay it opens
     * shows up via relayOpenedFlow, which reapplyChannelsToRelay already reacts to for every
     * currently-open channel, EVENT_LOOKUP included.
     */
    override fun connectToRelayHints(relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        relayUrls.asSequence()
            .map { normalizeRelayUrl(it) }
            .filter { it.isNotBlank() && !isLocalNetworkRelayUrl(it) }
            .distinct()
            .take(MAX_HINT_RELAYS_PER_LOOKUP)
            .forEach { hintUrl ->
                // hasActiveSocket also covers a hint relay whose Tor handshake is still in
                // flight (isConnected only flips true once it completes) — without this,
                // connect() (which unconditionally tears down and redials) gets called again on
                // every viewport-prefetch tick for a hint relay that's simply still connecting,
                // and the handshake never gets a chance to finish. Same guard as
                // reconnectRelevantDiscoveredRelays().
                if (!nostrClient.isConnected(hintUrl) && !nostrClient.hasActiveSocket(hintUrl)) {
                    nostrClient.connect(hintUrl)
                }
            }
    }

    override fun recordRelayHint(pubkey: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        val normalizedPubkey = pubkey.lowercase()
        val existing = relayHintsByPubkey[normalizedPubkey]
        if (existing == null && relayHintsByPubkey.size >= MAX_RELAY_HINT_PUBKEYS) return

        val hints = existing ?: relayHintsByPubkey.getOrPut(normalizedPubkey) { ConcurrentHashMap.newKeySet() }
        relayUrls.asSequence()
            .map { normalizeRelayUrl(it) }
            .filter { it.isNotBlank() }
            .forEach { hintUrl ->
                if (hints.size >= MAX_RELAY_HINTS_PER_PUBKEY && hintUrl !in hints) return@forEach
                hints.add(hintUrl)
            }
    }

    override fun getRelayHints(pubkey: String): List<String> =
        relayHintsByPubkey[pubkey.lowercase()]?.toList().orEmpty()

    /**
     * Clears [id] from [eventLookupTriedByRelay] for any of [relayHints] that already carry it,
     * and — for a hint relay that's already connected — forces an immediate fresh REQ rather than
     * waiting on a reapply trigger that a same-relay hint wouldn't otherwise cause (a newly
     * *connecting* hint relay is already covered by relayOpenedFlow's reapply). Same cap/normalize/
     * filter treatment as [connectToRelayHints] so both agree on which hints are eligible.
     */
    private fun reviveTriedHintRelaysForLookup(id: String, relayHints: List<String>) {
        val normalizedHints = relayHints.asSequence()
            .map { normalizeRelayUrl(it) }
            .filter { it.isNotBlank() && !isLocalNetworkRelayUrl(it) }
            .distinct()
            .take(MAX_HINT_RELAYS_PER_LOOKUP)
            .toList()

        val filters = channelFilters[NostrChannels.EVENT_LOOKUP] ?: return
        normalizedHints.forEach { hintUrl ->
            if (eventLookupTriedByRelay[hintUrl]?.remove(id) == true && nostrClient.isConnected(hintUrl)) {
                eventChannelRouting.applyChannelToRelay(hintUrl, NostrChannels.EVENT_LOOKUP, filters)
            }
        }
    }

    // Reapplies NostrChannels.EVENT_LOOKUP with the current pending-id set — grows when a new id
    // is added, shrinks when one resolves or times out, and closes the channel entirely once no
    // caller is still waiting. Also purges eventLookupTriedByRelay entries for ids no longer
    // pending, so a later unrelated fetchEventById() call for the same id starts fresh instead of
    // being silently skipped forever because some earlier, unrelated request for it already
    // exhausted that relay.
    private fun applyEventLookupPool() {
        val ids = pendingEventLookupIds.toSet()
        if (ids.isEmpty()) {
            clearChannel(NostrChannels.EVENT_LOOKUP)
            eventLookupTriedByRelay.clear()
            eventLookupLastSentByRelay.clear()
        } else {
            eventLookupTriedByRelay.values.forEach { it.retainAll(ids) }
            subscribeChannel(NostrChannels.EVENT_LOOKUP, listOf(EventFilter(ids = ids, limit = ids.size)))
        }
    }

    override suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): Event? =
        withContext(Dispatchers.IO) {
            val cached = eventIngestCache.snapshot().asSequence()
                .filter { it.kind == kind && it.pubkey.equals(pubkey, ignoreCase = true) }
                .filter { it.getTagValue("d") == identifier }
                .maxByOrNull { it.createdAt }
            val encrypted = encryptedEventDao.getLatestAddressableEvent(kind, pubkey, identifier)
                ?.toDomain()
                ?.takeIf { isCurrentUserPubkey(it.pubkey) }
            listOfNotNull(cached, encrypted).maxByOrNull { it.createdAt }
        }

    override suspend fun getEventsByIds(ids: List<String>): List<Event> =
        withContext(Dispatchers.IO) {
            val cached = eventIngestCache.getCachedByIds(ids)
            val encrypted = encryptedEventDao.getEventsByIds(ids)
                .map { it.toDomain() }
                .filter { isCurrentUserPubkey(it.pubkey) }
            (cached + encrypted).distinctBy { it.id }
        }

    override suspend fun getEventsReferencingIds(targetIds: List<String>): List<Event> =
        withContext(Dispatchers.IO) {
            if (targetIds.isEmpty()) {
                emptyList()
            } else {
                val targets = targetIds.toHashSet()
                val cached = eventIngestCache.snapshot()
                    .filter { event -> event.getTagValues("e").any { it in targets } }
                val encrypted = encryptedEventDao.getEventsReferencingIds(targetIds)
                    .map { it.toDomain() }
                    .filter { isCurrentUserPubkey(it.pubkey) }
                (cached + encrypted).distinctBy { it.id }
            }
        }

    override fun observeEventsByPubkeyAndKind(pubkey: String, kind: Int, limit: Int): Flow<List<Event>> =
        if (isCurrentUserPubkey(pubkey)) {
            encryptedEventDao.observeEventsByPubkeyAndKind(pubkey, kind, limit)
                .map { entities -> entities.map { it.toDomain() } }
                .flowOn(Dispatchers.IO)
        } else {
            eventIngestCache.cachedEventsFlow.map { events ->
                events.asSequence()
                    .filter { it.pubkey.equals(pubkey, ignoreCase = true) && it.kind == kind }
                    .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
                    .take(limit)
                    .toList()
            }.distinctUntilChanged().flowOn(Dispatchers.Default)
        }

    override fun observeCountEventsByPubkeyAndKind(pubkey: String, kind: Int): Flow<Int> =
        if (isCurrentUserPubkey(pubkey)) {
            encryptedEventDao.observeCountEventsByPubkeyAndKind(pubkey, kind).flowOn(Dispatchers.IO)
        } else {
            eventIngestCache.cachedEventsFlow.map { events ->
                events.count { it.pubkey.equals(pubkey, ignoreCase = true) && it.kind == kind }
            }.distinctUntilChanged().flowOn(Dispatchers.Default)
        }

    // scheduleInsert moved to EventIngestCache.kt — see eventIngestCache.scheduleInsert, called
    // from subscribeToEvents above. This class now only owns the Room transaction boundary itself
    // (see RoomOwnEventArchive below), never the debounce/queue logic that decides when to flush.

    /**
     * Facade-side [OwnEventArchive] implementation — the seam through which [EventIngestCache]
     * reaches the encrypted archive, so that class itself depends on the narrow [OwnEventArchive]
     * interface rather than the concrete Room database type. See [OwnEventArchive]'s own doc
     * comment for why this exists (this project runs no Robolectric; a real Room database cannot
     * be constructed in a plain JVM unit test). Other facade methods (e.g. `clearAllData`,
     * `deleteEvent`) still reach the Room database directly for their own, unrelated purposes —
     * this class only wraps the slice [EventIngestCache] needs.
     */
    private inner class RoomOwnEventArchive : OwnEventArchive {
        override suspend fun writeBatch(batch: List<PendingEventInsert>) {
            encryptedDatabase.withTransaction {
                encryptedEventDao.insertEvents(batch.map { it.entity })
                val encryptedEventIds = batch.map { it.entity.id }.distinct()
                if (encryptedEventIds.isNotEmpty()) {
                    encryptedEventTagDao.deleteTagsForEvents(encryptedEventIds)
                }
                val encryptedTags = batch.flatMap { it.tags }
                if (encryptedTags.isNotEmpty()) encryptedEventTagDao.insertTags(encryptedTags)
                // Room's @Upsert only conflict-resolves by id, and every revision of a
                // replaceable event has a distinct id — without this, old revisions of the
                // signed-in user's own profile/contact-list/relay-lists/etc. would accumulate
                // in `events`/`event_tags` forever instead of being superseded the way
                // cachedEvents (EventLruCache) already is above. Runs after the tag
                // inserts/deletes above so each slot's d-tag lookup reflects this batch.
                batch.mapNotNull { it.replaceableKey }.distinct().forEach { key ->
                    encryptedEventDao.deleteSupersededReplaceableEvents(key.kind, key.pubkey, key.dTag)
                }
            }
        }

        override suspend fun getEventsByIds(ids: List<String>): List<EventEntity> =
            encryptedEventDao.getEventsByIds(ids)

        override suspend fun deleteEventById(id: String) {
            encryptedEventDao.deleteEventById(id)
        }

        override suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): EventEntity? =
            encryptedEventDao.getLatestAddressableEvent(kind, pubkey, identifier)
    }

    override suspend fun clearCache(): Unit = withContext(Dispatchers.IO) {
        eventIngestCache.clearAll()
        seenEventIds.clear()
        eventIngestCache.signalFeedRebuild()
        logger.d { "Cache cleared" }
    }

    override suspend fun getInMemoryCacheStats(): EventCacheStats = withContext(Dispatchers.IO) {
        eventIngestCache.cacheStats()
    }

    override suspend fun trimMemory(aggressive: Boolean): Unit = withContext(Dispatchers.IO) {
        val target = if (aggressive) MAX_IN_MEMORY_EVENT_CACHE / 4 else MAX_IN_MEMORY_EVENT_CACHE / 2
        val trimmed = eventIngestCache.trimTo(target)
        if (trimmed > 0) {
            // Lets every active observeFeedNotes() collector's own externalEvents mirror shrink
            // back down too, instead of only the shared source cache.
            eventIngestCache.signalFeedRebuild()
            logger.d { "Trimmed $trimmed events from in-memory cache (aggressive=$aggressive)" }
        }
    }

    override suspend fun deleteEvent(eventId: String): Unit = withContext(Dispatchers.IO) {
        encryptedDatabase.withTransaction {
            encryptedEventTagDao.deleteTagsForEvent(eventId)
            encryptedEventDao.deleteEventById(eventId)
        }

        eventIngestCache.removeCachedEvent(eventId)
        eventIngestCache.emitSnapshotNow()
        eventIngestCache.signalFeedRebuild()
    }

    override suspend fun connectToEnabledRelays(relays: List<Relay>): Result<Unit> {
        if (!TorProxyConfig.isReady) {
            return Result.failure(IllegalStateException("Tor proxy is not ready"))
        }

        initialCacheLoaded.await()

        // Use newest cached event timestamp as `since` to avoid re-fetching events already in DB
        val newestKind1 = withContext(Dispatchers.IO) {
            val newestCached = eventIngestCache.snapshot().asSequence()
                .filter { it.kind == Event.KIND_TEXT_NOTE }
                .maxOfOrNull { it.createdAt }
            listOfNotNull(
                newestCached,
                encryptedEventDao.getNewestTimestampByKind(Event.KIND_TEXT_NOTE)
            ).maxOrNull()
        }
        cachedFeedSince = if (newestKind1 != null && newestKind1 > 0) {
            newestKind1 - 60
        } else {
            null
        }

        // Own (manually-configured/NIP-65) relays connect before isDiscovered ones, so a large
        // discovered-relay pool can never starve the user's own relays of a connection slot —
        // defense in depth alongside the Dispatcher capacity sized for both in NetworkModule.
        //
        // hasAnyActiveRole() (isReadActive/isWriteActive/isDmActive/isSearchActive/
        // isIndexActive), not just read/write — a relay whose only active role was DM (never
        // actually reachable, since DMs aren't published yet) or search/index (the new NIP-44
        // relay-list feature: a relay enabled only there previously never got a connection
        // opened at all, so it sat configured in the UI without ever actually being reachable)
        // was silently excluded from ever connecting.
        //
        // `|| it.isDiscovered` is required separately: since isReadEnabled/isReadActive were
        // changed to exclusively reflect a genuine kind:10002 declaration (see
        // RelayRepositoryImpl.buildFirstLoginRelaySet / UserRepositoryImpl.addDiscoveredRelays),
        // every discovered/bootstrap relay has hasAnyActiveRole() == false by construction even
        // though isEnabled == true — without this clause they'd never reach nostrClient.
        // connect() at all, despite canApplyChannelToRelay() already treating them as eligible
        // for feed/inbox reads via that same isDiscovered flag.
        // See DiscoveredRelayDialPolicy's doc comment: own relays dial first, then discovered
        // relays known to cover a followed author, then coverage-unknown discovered relays —
        // MAX_DISCOVERED_RELAY_DIALS_PER_PASS below defers the least valuable connections first
        // if a pass is large enough to hit the cap.
        val enabledRelays = DiscoveredRelayDialPolicy.sortForDialing(
            relays = relays.filter { it.isEnabled && (it.hasAnyActiveRole() || it.isDiscovered) },
            authorCoveredRelayUrls = feedAuthorsPerRelay.keys
        )

        return try {
            // The loop below only ever adds, so a relay tracked from a previous cycle that fell
            // out of the eligible set — disabled, deleted, or its last active role removed —
            // would otherwise keep its socket open forever
            // (previously only disconnectFromAll() on logout/Tor-drop/app-stop would ever close
            // it). Reuses the same per-relay cleanup sweepIdleDiscoveredRelays() already does for
            // its own disconnects, just triggered by eligibility instead of idle time.
            val eligibleUrls = enabledRelays.mapTo(HashSet()) { it.url }
            val staleUrls = RelayConnectionPolicy.staleRelayUrls(connectedRelays.keys, eligibleUrls)
            staleUrls.forEach { staleUrl ->
                forgetRelayAndCleanup(staleUrl)
                logger.d { "Disconnected relay no longer eligible (disabled/removed/role lost)" }
            }

            // Refresh session channel definitions once per connect cycle.
            applySessionChannelsToRelay()

            var discoveredDialsThisPass = 0
            for ((index, relay) in enabledRelays.withIndex()) {
                // Every relay routes through the same single local Tor SOCKS process
                // (127.0.0.1:9050) — nostrClient.connect() itself returns immediately, but it
                // starts a real SOCKS/circuit-build handshake right there, so an unthrottled loop
                // over a large discovered-relay pool (up to MAX_TOTAL_DISCOVERED_RELAYS) fires
                // hundreds of simultaneous dials at that one process. Tor's circuit-building
                // throughput isn't unlimited; the more dials competing for it at once, the longer
                // EACH ONE takes to resolve either way, which is what made many relays sit
                // ambiguously "connecting" for a long time. Pacing dial *starts* into small
                // batches spreads the burst out (e.g. 200 relays / 8 per batch * 250ms ≈ 6s to
                // dispatch all of them) without meaningfully delaying full connectivity — and the
                // user's own non-discovered relays, sorted first above, are always in the very
                // first batch regardless of how large the discovered pool is.
                //
                // This pause is dial-start pacing only — it fires on a fixed cadence regardless
                // of whether the previous batch's connects succeeded or failed, and connect()
                // below never blocks this loop waiting for a handshake outcome (that arrives
                // later via onWebSocketOpen/onFailure/onClosed). So a batch of relays that are
                // all down cannot stall reaching the next batch: a single relay's failure just
                // continue()s below (see the comment there), and the next batch starts after
                // this same fixed 250ms either way. Do not turn this into a wait-for-outcome
                // gate — that would reintroduce exactly the "one bad group blocks everything
                // else" failure mode this design avoids.
                if (index > 0 && index % DIAL_BATCH_SIZE == 0) {
                    delay(DIAL_BATCH_PAUSE_MS)
                }
                val shouldConnect = RelayConnectionPolicy.shouldConnect(
                    isTracked = connectedRelays.containsKey(relay.url),
                    isConnected = nostrClient.isConnected(relay.url),
                    hasActiveSocket = nostrClient.hasActiveSocket(relay.url)
                )

                if (shouldConnect && DiscoveredRelayDialPolicy.shouldDeferDial(relay.isDiscovered, discoveredDialsThisPass)) {
                    // Deferred to a later pass — see DiscoveredRelayDialPolicy's doc comment.
                    continue
                }

                if (shouldConnect) {
                    if (relay.isDiscovered) {
                        discoveredDialsThisPass++
                    }
                    // All connections go through ORBOT - no proxy parameters needed.
                    // getOrThrow() here used to propagate a single relay's connect failure out of
                    // this for loop entirely, aborting the whole batch via the outer try/catch
                    // below — every relay later in enabledRelays iteration order (getAllRelays()
                    // is addedAtMillis DESC, so that's every *older* discovered relay, since newer
                    // ones sort first) silently never got a connect attempt at all, this pass or
                    // any retry, since the same relay fails identically every time. One dead/
                    // unreachable relay anywhere in a large discovered pool must not block the
                    // rest — skip just this relay and keep going.
                    val connectResult = nostrClient.connect(relay.url)
                    if (connectResult.isFailure) {
                        logger.d {
                            "Failed to connect to relay, skipping for this pass: " +
                                scrubThrowableMessageForLogs(connectResult.exceptionOrNull() ?: Exception("unknown"))
                        }
                        continue
                    }
                    connectedRelays[relay.url] = relay
                    if (relay.isDiscovered) {
                        // Baseline so the idle sweep has something to measure against even if
                        // this relay never once covers a requested author — see
                        // sweepIdleDiscoveredRelays/DiscoveredRelayIdlePolicy.
                        discoveredRelayLastNeededAtMillis[relay.url] = System.currentTimeMillis()
                    }
                    logger.d { "Connected to relay: [scrubbed]" }
                }

                // Always refresh connected relay capabilities from latest Room-sourced config.
                val previousRelay = connectedRelays[relay.url]
                connectedRelays[relay.url] = relay

                // For already-open sockets, replay non-session channels to refresh caps.
                // Freshly opened sockets are covered by relayOpenedFlow re-apply — except the 4
                // session-gated channels below also need reapplying here when this relay's role
                // just changed (e.g. its real kind:10002 named it a write-active outbox relay)
                // while it stayed connected: no fresh socket-open event fires for that case, so
                // relayOpenedFlow would never reach it. See RelayRoleChangePolicy.
                val roleChanged = !shouldConnect && RelayRoleChangePolicy.roleAffectingFieldsChanged(previousRelay, relay)
                if (!shouldConnect) {
                    channelFilters.forEach { (channelId, filters) ->
                        val isSessionGatedChannel = channelId == NostrChannels.OUTBOX_PROFILE ||
                            channelId == NostrChannels.OUTBOX_NOTES ||
                            channelId == NostrChannels.FEED_NOTES ||
                            channelId == NostrChannels.INBOX_NOTES
                        if (!isSessionGatedChannel || roleChanged) {
                            eventChannelRouting.applyChannelToRelay(relay.url, channelId, filters)
                        }
                    }
                }
                if (channelFilters.isEmpty() && currentFilters.isNotEmpty()) {
                    eventChannelRouting.applyChannelToRelay(relay.url, NostrChannels.DEFAULT_EVENTS, currentFilters)
                }
            }
            // Newly cached NIP-65 data (e.g. from userRepository.saveRelayList() calls that
            // landed during this cycle) may have changed which authors have a known outbox —
            // recompute and reapply so already-connected relays pick up tighter scoping without
            // waiting for the next debounced scheduleAuthorsPerRelayRefresh().
            recomputeFeedAuthorsPerRelay()
            reapplyPreciseRoutedChannels()
            Result.success(Unit)
        } catch (e: Exception) {
            logger.e(e) { "Failed to connect to relays" }
            Result.failure(e)
        }
    }

    override fun disconnectFromAll() {
        nostrClient.disconnectAll()
        connectedRelays.clear()
        _relayRequestsFlow.value = emptyList()
        logger.d { "Disconnected from all relays" }
    }

    override fun disconnectRelay(relayUrl: String) {
        forgetRelayAndCleanup(relayUrl)
        logger.d { "Disconnected relay on demand (disabled)" }
    }

    /**
     * Tears down [relayUrl]'s connection and forgets all of its per-relay tracking state — for a
     * relay that's leaving the pool for good (disabled/deleted/role lost), unlike the idle
     * sweep's discovered-relay disconnects, which expect to reconnect on demand. Shared by
     * [connectToEnabledRelays]'s stale-relay sweep and [disconnectRelay]'s on-demand teardown.
     */
    private fun forgetRelayAndCleanup(relayUrl: String) {
        // forgetRelay(), not disconnect(): see this function's doc comment.
        nostrClient.forgetRelay(relayUrl)
        connectedRelays.remove(relayUrl)
        discoveredRelayLastNeededAtMillis.remove(relayUrl)
        feedSinceByRelay.remove(relayUrl)
        // Also drop this relay's "known missing" verdicts (see forgetRelay's doc comment:
        // reconnection means everything gets relearned anyway). Without this, a lookup that was
        // still pending when this relay left the pool keeps its stale "doesn't have it" marker
        // forever if the relay is later re-enabled while that same id/pubkey is still being
        // awaited — silently skipping a relay that could actually answer, which is why
        // citations/mentions sometimes failed or resolved slowly.
        eventLookupTriedByRelay.remove(relayUrl)
        eventLookupLastSentByRelay.remove(relayUrl)
        authorHydrationTriedByRelay.remove(relayUrl)
        authorHydrationLastSentByRelay.keys.removeIf { it.first == relayUrl }
    }

    fun isRelayConnected(relayUrl: String): Boolean {
        return connectedRelays.containsKey(relayUrl)
    }

    override suspend fun publishEvent(event: Event): Result<Set<String>> {
        if (!TorProxyConfig.isReady) {
            return Result.failure(IllegalStateException("Tor proxy is not ready"))
        }

        return try {
            // Filter to write-enabled relays (outbox) — only publish to those
            val writeRelays = connectedRelays
                .filterValues { relay -> relay.isWriteActive }
                .keys
                .toSet()

            if (writeRelays.isEmpty()) {
                return Result.failure(IllegalStateException("No write-enabled relays configured"))
            }

            // NIP-65 outbox model: also reach the inbox relays of anyone this event addresses
            // (reply/root participants, mentions) — see computeInboxTargetRelays's doc comment.
            val participantPubkeys = event.getTagValues("p")
                .filter { it.isNotBlank() && !it.equals(event.pubkey, ignoreCase = true) }
                .toSet()
            val inboxRelays = computeInboxTargetRelays(
                participantPubkeys = participantPubkeys,
                connectedRelayUrls = connectedRelays.keys,
                inboxRelaysFor = { pubkey -> userRepository.getRelayList(pubkey)?.getInboxRelays().orEmpty() }
            )

            val targetRelays = writeRelays + inboxRelays
            nostrClient.publishEventToRelays(event, targetRelays.toList())
            Result.success(targetRelays)
        } catch (e: Exception) {
            logger.e(e) { "Failed to publish event" }
            Result.failure(e)
        }
    }

    override suspend fun publishAuthEvent(relayUrl: String, event: Event): Result<Unit> {
        if (!TorProxyConfig.isReady) {
            return Result.failure(IllegalStateException("Tor proxy is not ready"))
        }

        return runCatching {
            nostrClient.publishAuthEvent(relayUrl, event)
        }
    }

    override fun observeRelayRequests(): Flow<List<RelayRequestInfo>> {
        return _relayRequestsFlow.asStateFlow()
    }

    override fun observeRelayIssues(): Flow<RelayIssue> {
        return nostrClient.relayIssueFlow
    }

    override fun observeConnectedRelayUrls(): Flow<Set<String>> {
        return nostrClient.connectedRelayUrlsFlow
    }

    override fun resetRelayFailureCount(relayUrl: String) {
        nostrClient.resetFailureCount(relayUrl)
    }

    override fun resetAllRelayBackoff() {
        nostrClient.resetAllBackoff()
    }

    override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {
        // NIP-45 COUNT is optional — require confirmed support rather than assuming it.
        // Centralized here, not just at the ViewModel call site, so this holds regardless of
        // which future caller invokes requestCount.
        if (!relaySupportsNip(connectedRelays[relayUrl], nip = 45)) return
        nostrClient.requestCount(relayUrl, subscriptionId, filters)
    }

    override fun observeRelayCounts(): Flow<RelayCountResult> {
        return nostrClient.countFlow
    }

    /**
     * Folds [overlayFilters] into [channelId]'s own live subscription as extra filters (NIP-01:
     * multiple filters per REQ, still one subscription slot), waits for EOSE — which, since it
     * fires once all currently-stored matches for *every* filter in the REQ have been sent, also
     * marks the backfill window as fully drained — then reverts to the pre-overlay filters. Costs
     * zero extra subscription slots versus a separate derived "-page"/"-resync" channel.
     *
     * Serialized per channelId via [channelOverlayMutex]: without it, a concurrent second caller
     * (e.g. resyncRecentHistory firing right after a loadOlderEvents tick) could capture a "base"
     * snapshot that still includes this call's not-yet-reverted overlay, and revert to *that*
     * instead of the true base — permanently leaking a stale backfill-window filter into the live
     * subscription. After acquiring the lock, only reverts if [channelId]'s filters are still
     * exactly what this call set them to; if something else (e.g. a pubkey/session change via
     * applySessionChannelsToRelay, which doesn't go through this lock) changed them in the
     * meantime, that newer value wins and is left alone.
     */
    private suspend fun applyBackfillOverlay(
        channelId: String,
        overlayFilters: List<EventFilter>,
        closeTimeoutMs: Long,
        logLabel: String
    ) {
        val mutex = channelOverlayMutex.getOrPut(channelId) { Mutex() }
        mutex.withLock {
            val base = channelFilters[channelId]
            if (base.isNullOrEmpty()) return
            val combined = base + overlayFilters
            subscribeChannel(channelId, combined)
            awaitChannelEoseOrTimeout(channelId, closeTimeoutMs)
            if (channelFilters[channelId] == combined) {
                subscribeChannel(channelId, base)
            }
            logger.d { "$logLabel overlay on '$channelId' closed (EOSE or timeout)" }
        }
    }

    /**
     * Load a page of events older than [untilTimestamp] for the given channel. Copies the
     * existing channel's filter (kinds, authors, tags) but applies a backward time window:
     * [untilTimestamp - windowSeconds .. untilTimestamp - 1]. For [MERGEABLE_BACKFILL_CHANNEL_IDS]
     * this folds into the live channel's own subscription (see applyBackfillOverlay) instead of a
     * separate derived "-page" channel; for everything else, the temporary page subscription
     * closes itself after HISTORY_PAGE_CLOSE_MS.
     */
    override fun loadOlderEvents(
        channelId: String,
        untilTimestamp: Long,
        windowSeconds: Long,
        limit: Int
    ) {
        repoScope.launch {
            val baseFilters = channelFilters[channelId]
            if (baseFilters.isNullOrEmpty()) return@launch

            val sanitizedWindow = windowSeconds.coerceIn(6 * 60 * 60L, 365L * 24 * 60 * 60L)
            val sanitizedLimit = limit.coerceIn(50, 1000)

            // Clamp to currently cached oldest for the same author when possible.
            val authorFromFilter = baseFilters
                .firstOrNull()
                ?.authors
                ?.singleOrNull()
            val oldestAnchor = withContext(Dispatchers.IO) {
                if (!authorFromFilter.isNullOrBlank()) {
                    if (isCurrentUserPubkey(authorFromFilter)) {
                        encryptedEventDao.getOldestTimestampByPubkeyAndKind(authorFromFilter, Event.KIND_TEXT_NOTE)
                    } else {
                        eventIngestCache.snapshot().asSequence()
                            .filter {
                                it.kind == Event.KIND_TEXT_NOTE &&
                                    it.pubkey.equals(authorFromFilter, ignoreCase = true)
                            }
                            .minOfOrNull { it.createdAt }
                    }
                } else {
                    // Tag-filtered channels (INBOX_NOTES, no single `authors`) have no one author
                    // to scope a "what's already cached" check by. Scanning the whole shared cache
                    // filtered by KIND_TEXT_NOTE matched neither this channel's author-scope
                    // (unscoped to "me") nor its full kind set (INBOX_NOTES also carries
                    // reactions/reposts) — an unrelated cached event's timestamp could clamp
                    // `until` into skipping a real window. No clamp is correct here;
                    // `untilTimestamp` (driven by NostrSessionManager's own anchor tracking) is
                    // already the right boundary.
                    null
                }
            } ?: untilTimestamp

            val until = minOf(untilTimestamp, oldestAnchor) - 1
            if (until <= 0L) return@launch

            val requestFingerprint = buildString {
                append(channelId)
                append('|')
                append(authorFromFilter ?: "*")
                append('|')
                append(until)
                append('|')
                append((until - sanitizedWindow).coerceAtLeast(0L))
                append('|')
                append(sanitizedLimit)
            }

            val pageFilters = eventChannelRouting.mergeSameScopeFilters(
                baseFilters.map { f ->
                    f.copy(
                        since = (until - sanitizedWindow).coerceAtLeast(0L),
                        until = until,
                        limit = sanitizedLimit
                    )
                }
            )

            if (channelId in MERGEABLE_BACKFILL_CHANNEL_IDS) {
                val activeJob = channelOverlayJobs[channelId]
                if (activeJob?.isActive == true && overlayRequestFingerprint[channelId] == requestFingerprint) {
                    return@launch
                }
                overlayRequestFingerprint[channelId] = requestFingerprint
                // Deliberately NOT cancelling any in-flight overlay job for this channelId (unlike
                // the derived-page-channel path below) — cancelling mid-overlay would abandon it
                // before its revert runs, permanently leaking a stale backfill-window filter into
                // the live subscription. A new call just queues behind channelOverlayMutex until
                // the in-flight one finishes and reverts cleanly; see applyBackfillOverlay's doc.
                channelOverlayJobs[channelId] = repoScope.launch {
                    applyBackfillOverlay(channelId, pageFilters, HISTORY_PAGE_CLOSE_MS, "Backfill page")
                }
                return@launch
            }

            val pageChannelId = "$channelId-page"

            val activePageJob = historyPageJobs[pageChannelId]
            if (activePageJob?.isActive == true && pageRequestFingerprint[pageChannelId] == requestFingerprint) {
                return@launch
            }

            pageRequestFingerprint[pageChannelId] = requestFingerprint
            subscribeChannel(pageChannelId, pageFilters)

            // Close the page sub as soon as every relay it was sent to reports EOSE (NIP-01:
            // no more stored events for this REQ) — HISTORY_PAGE_CLOSE_MS is only a backstop for
            // relays that never send EOSE (buggy/non-conformant) or drop the connection.
            historyPageJobs.remove(pageChannelId)?.cancel()
            historyPageJobs[pageChannelId] = repoScope.launch {
                awaitChannelEoseOrTimeout(pageChannelId, HISTORY_PAGE_CLOSE_MS)
                clearChannel(pageChannelId)
                historyPageJobs.remove(pageChannelId)
                pageRequestFingerprint.remove(pageChannelId)
                logger.d { "Page channel '$pageChannelId' closed (EOSE or timeout)" }
            }
        }
    }

    override fun resyncRecentHistory(
        channelId: String,
        sinceTimestamp: Long,
        untilTimestamp: Long,
        limit: Int
    ) {
        repoScope.launch {
            val baseFilters = channelFilters[channelId]
            if (baseFilters.isNullOrEmpty()) return@launch
            if (untilTimestamp <= sinceTimestamp) return@launch

            // Deliberately NOT clamped to "currently cached oldest for this author" the way
            // loadOlderEvents() is — that clamp assumes a window already queried has nothing more
            // to find, which is exactly the assumption a relay-set change invalidates (see this
            // function's doc comment). Same 6h-1y bound as loadOlderEvents for the same reason:
            // an unbounded window on an old account could ask for years of history in one REQ
            // that any relay would just truncate to its most recent `limit` matches anyway.
            val sanitizedWindow = (untilTimestamp - sinceTimestamp).coerceIn(6 * 60 * 60L, 365L * 24 * 60 * 60L)
            val sanitizedLimit = limit.coerceIn(50, 1000)
            val since = (untilTimestamp - sanitizedWindow).coerceAtLeast(0L)

            val pageFilters = eventChannelRouting.mergeSameScopeFilters(
                baseFilters.map { f -> f.copy(since = since, until = untilTimestamp, limit = sanitizedLimit) }
            )

            if (channelId in MERGEABLE_BACKFILL_CHANNEL_IDS) {
                // Deliberately NOT cancelling any in-flight overlay job — see loadOlderEvents'
                // matching comment; this queues behind channelOverlayMutex instead.
                channelOverlayJobs[channelId] = repoScope.launch {
                    applyBackfillOverlay(channelId, pageFilters, HISTORY_PAGE_CLOSE_MS, "Resync")
                }
                return@launch
            }

            val pageChannelId = "$channelId-resync"

            subscribeChannel(pageChannelId, pageFilters)

            historyPageJobs.remove(pageChannelId)?.cancel()
            historyPageJobs[pageChannelId] = repoScope.launch {
                awaitChannelEoseOrTimeout(pageChannelId, HISTORY_PAGE_CLOSE_MS)
                clearChannel(pageChannelId)
                historyPageJobs.remove(pageChannelId)
                logger.d { "Resync channel '$pageChannelId' closed (EOSE or timeout)" }
            }
        }
    }

    override suspend fun getOldestAuthorNoteTimestamp(pubkey: String): Long? {
        val normalized = pubkey.lowercase()
        return withContext(Dispatchers.IO) {
            if (isCurrentUserPubkey(normalized)) {
                encryptedEventDao.getOldestTimestampByPubkeyAndKind(normalized, Event.KIND_TEXT_NOTE)
            } else {
                eventIngestCache.snapshot().asSequence()
                    .filter {
                        it.kind == Event.KIND_TEXT_NOTE &&
                            it.pubkey.equals(normalized, ignoreCase = true)
                    }
                    .minOfOrNull { it.createdAt }
            }
        }
    }

    override suspend fun getOldestInboxNoteTimestamp(pubkey: String): Long? {
        val normalized = pubkey.lowercase()
        return withContext(Dispatchers.Default) {
            eventIngestCache.snapshot().asSequence()
                .filter { it.kind == Event.KIND_TEXT_NOTE && normalized in it.getMentionedPubkeys() }
                .minOfOrNull { it.createdAt }
        }
    }

    override suspend fun getOldestInboxReactionTimestamp(pubkey: String): Long? {
        val normalized = pubkey.lowercase()
        return withContext(Dispatchers.Default) {
            eventIngestCache.snapshot().asSequence()
                // INBOX_NOTES' interactions filter fetches KIND_REACTION, KIND_REPOST and
                // KIND_ZAP_RECEIPT (see inboxInteractionKinds) — tracking only a subset here
                // could stall the anchor while backfill on the untracked kind(s) was actually
                // still progressing.
                .filter {
                    (it.kind == Event.KIND_REACTION || it.kind == Event.KIND_REPOST || it.kind == Event.KIND_ZAP_RECEIPT) &&
                        normalized in it.getMentionedPubkeys()
                }
                .minOfOrNull { it.createdAt }
        }
    }

    // Reads effectiveChannelFilters(channelId) fresh when the debounce fires (not a filters
    // snapshot taken at schedule time) so that whichever of subscribeChannel()/setChannelOverlay()
    // triggered this always sends the other's latest value too, instead of the base and overlay
    // racing to clobber each other across two independent debounced applies.
    private fun scheduleChannelApply(channelId: String) {
        pendingChannelJobs.remove(channelId)?.cancel()
        pendingChannelJobs[channelId] = repoScope.launch {
            delay(CHANNEL_RESUBSCRIBE_DEBOUNCE_MS)
            val filters = effectiveChannelFilters(channelId)
            connectedRelays.keys.forEach { relayUrl ->
                eventChannelRouting.applyChannelToRelay(relayUrl, channelId, filters)
            }
            logger.d { "Channel '$channelId' updated on ${connectedRelays.size} relays" }
            pendingChannelJobs.remove(channelId)
        }
    }

    // ── Hybrid cache + encrypted-own flows ───────────────────────────────────

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
    ): Flow<FeedNotesResult> = flow {
        val externalEvents = LinkedHashMap<String, Event>(MAX_IN_MEMORY_EVENT_CACHE)
        var ownEvents = emptyList<Event>()
        var ownEventIds: Set<String> = emptySet()
        // Rebuilt only when ownEvents actually changes (see the OwnSnapshot branch below),
        // instead of on every emitCurrentFeed() call — this used to rebuild a fresh
        // EventEngagementIndex over up to 1000 own events on every single feed emission,
        // including ones driven purely by relay activity that never touched ownEvents.
        var ownEngagementSnapshot: Map<String, EngagementCounts> = emptyMap()
        var selectedNotes = emptyList<Event>()

        suspend fun rebuildExternalSnapshot() {
            val snapshot = eventIngestCache.snapshot()
            externalEvents.clear()
            snapshot.forEach { externalEvents[it.id] = it }
        }

        suspend fun emitCurrentFeed() {
            val eventsById = { id: String -> externalEvents[id] ?: ownEvents.firstOrNull { it.id == id } }
            val resolution = resolveFeedEventsAndScheduleFetches(selectedNotes, eventsById)
            val resolved = resolution.resolved
            val neededPubkeys = (resolved.map { it.targetEvent.pubkey } + resolved.mapNotNull { it.repostedByPubkey }).distinct()
            val profiles = userRepository.getProfiles(neededPubkeys)
                .associateBy { it.pubkey.lowercase() }
            val cachedEngagement = eventIngestCache.engagementSnapshot()
            val engagement = mergeEngagementCounts(cachedEngagement, ownEngagementSnapshot)
            emit(
                FeedNotesResult(
                    notes = buildIndexedNoteViews(resolved, profiles, engagement),
                    pendingReposts = toPendingReposts(resolution.unresolvedReposts)
                )
            )
        }

        rebuildExternalSnapshot()
        // Seed from the already-rebuilt L1 cache before waiting on the merged sources below.
        // Without this, a fresh subscription (e.g. resubscribing after WhileSubscribed tears
        // the pipeline down while navigating away and back) starts `selectedNotes` empty; if
        // the first merged update is a small `_cachedEventBundles` replay, the incremental
        // merge in `ExternalBundle` below folds it into that empty list instead of the full
        // cache, and the feed briefly renders as empty.
        if (externalEvents.isNotEmpty() || ownEvents.isNotEmpty()) {
            selectedNotes = selectHybridFeedNotes(
                events = externalEvents.values + ownEvents,
                since = since,
                limit = limit,
                authors = authors,
                mutedPubkeys = mutedPubkeys,
                excludedHashtagsLower = excludedHashtagsLower,
                includeMentions = includeMentions,
                hideNsfw = hideNsfw,
                currentNpub = currentNpub,
                currentUserPubkey = currentUserPubkey,
                desiredTagsLower = desiredTagsLower
            )
            emitCurrentFeed()
        }
        merge(
            eventIngestCache.cachedEventBundles.map { FeedCacheUpdate.ExternalBundle(it) },
            encryptedEventDao.observeRecentEvents(minOf(limit, OWN_ARCHIVE_FEED_MERGE_LIMIT))
                .map { entities -> FeedCacheUpdate.OwnSnapshot(entities.map { it.toDomain() }) },
            eventIngestCache.feedRebuildSignals.map { FeedCacheUpdate.Rebuild }
        ).collect { update ->
            when (update) {
                is FeedCacheUpdate.ExternalBundle -> {
                    val liveEvents = eventIngestCache.getCachedByIds(update.events.map { it.id })
                    liveEvents.forEach { event -> externalEvents[event.id] = event }
                    while (externalEvents.size > MAX_IN_MEMORY_EVENT_CACHE) {
                        externalEvents.remove(externalEvents.keys.first())
                    }
                    selectedNotes = updateFeedNotesIncrementally(
                        currentNotes = selectedNotes,
                        incomingEvents = liveEvents,
                        since = since,
                        limit = limit,
                        authors = authors,
                        mutedPubkeys = mutedPubkeys,
                        excludedHashtagsLower = excludedHashtagsLower,
                        includeMentions = includeMentions,
                        hideNsfw = hideNsfw,
                        currentNpub = currentNpub,
                        currentUserPubkey = currentUserPubkey,
                        desiredTagsLower = desiredTagsLower
                    )
                }
                is FeedCacheUpdate.OwnSnapshot -> {
                    val newOwnEvents = update.events.filter { isCurrentUserPubkey(it.pubkey) }
                    val newOwnEventIds = newOwnEvents.mapTo(HashSet(newOwnEvents.size)) { it.id }
                    // Room's observeRecentEvents() re-emits on ANY own-table change (post, like,
                    // repost, delete) — recomputing selectHybridFeedNotes from scratch over
                    // externalEvents (up to MAX_IN_MEMORY_EVENT_CACHE) + ownEvents on every one of
                    // those was a full re-sort of the whole external cache triggered purely by the
                    // user's own activity. When nothing was removed (the common case: a new own
                    // post/like/repost), fold just the additions in via the same incremental path
                    // ExternalBundle already uses below. A removal (a delete) still needs the full
                    // recompute: incrementally dropping an item from an already-`take(limit)`'d
                    // list can't safely backfill from events the limit had excluded.
                    val hasRemovals = hasRemovedOwnEvents(ownEventIds, newOwnEventIds)
                    if (hasRemovals) {
                        selectedNotes = selectHybridFeedNotes(
                            events = externalEvents.values + newOwnEvents,
                            since = since,
                            limit = limit,
                            authors = authors,
                            mutedPubkeys = mutedPubkeys,
                            excludedHashtagsLower = excludedHashtagsLower,
                            includeMentions = includeMentions,
                            hideNsfw = hideNsfw,
                            currentNpub = currentNpub,
                            currentUserPubkey = currentUserPubkey,
                            desiredTagsLower = desiredTagsLower
                        )
                    } else {
                        val added = addedOwnEvents(newOwnEvents, ownEventIds)
                        if (added.isNotEmpty()) {
                            selectedNotes = updateFeedNotesIncrementally(
                                currentNotes = selectedNotes,
                                incomingEvents = added,
                                since = since,
                                limit = limit,
                                authors = authors,
                                mutedPubkeys = mutedPubkeys,
                                excludedHashtagsLower = excludedHashtagsLower,
                                includeMentions = includeMentions,
                                hideNsfw = hideNsfw,
                                currentNpub = currentNpub,
                                currentUserPubkey = currentUserPubkey,
                                desiredTagsLower = desiredTagsLower
                            )
                        }
                    }
                    ownEvents = newOwnEvents
                    ownEventIds = newOwnEventIds
                    ownEngagementSnapshot = buildAdditionalEngagementSnapshot(newOwnEvents)
                }
                FeedCacheUpdate.Rebuild -> {
                    rebuildExternalSnapshot()
                    selectedNotes = selectHybridFeedNotes(
                        events = externalEvents.values + ownEvents,
                        since = since,
                        limit = limit,
                        authors = authors,
                        mutedPubkeys = mutedPubkeys,
                        excludedHashtagsLower = excludedHashtagsLower,
                        includeMentions = includeMentions,
                        hideNsfw = hideNsfw,
                        currentNpub = currentNpub,
                        currentUserPubkey = currentUserPubkey,
                        desiredTagsLower = desiredTagsLower
                    )
                }
            }
            emitCurrentFeed()
        }
    }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeProfileNotes(pubkey: String, kind: Int, limit: Int): Flow<FeedNotesResult> =
        if (isCurrentUserPubkey(pubkey)) {
            combine(
                encryptedEventDao.observeProfileNotes(pubkey, kind, limit)
                    .map { rows -> rows.map { it.toNoteView() } },
                encryptedEventDao.observeOwnReposts(pubkey, limit)
                    .map { entities -> collapseRepostsToLatestPerTarget(entities.map { it.toDomain() }) },
                // Needed so a repost of someone else's note can resolve, and so this flow
                // reactively recomputes once a background fetch (scheduleRepostTargetFetch) lands
                // an initially-unresolvable target — see resolveOwnRepostNoteViews.
                eventIngestCache.cachedEventsFlow.conflate()
            ) { ownNoteViews, ownReposts, cachedEvents ->
                // Fast path: the overwhelming common case (no reposts) does none of the extra
                // resolution work below and matches today's exact behavior/perf.
                if (ownReposts.isEmpty()) {
                    FeedNotesResult(notes = ownNoteViews.take(limit))
                } else {
                    val (repostNoteViews, pendingReposts) = resolveOwnRepostNoteViews(ownReposts, cachedEvents)
                    FeedNotesResult(
                        notes = mergeOwnNotesAndReposts(ownNoteViews, repostNoteViews, limit),
                        pendingReposts = pendingReposts
                    )
                }
            }
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)
        } else {
            eventIngestCache.cachedEventsFlow.conflate().map { allEvents ->
                val eventsById = allEvents.associateBy { it.id }
                // Reposts (kind 6/16) by this profile's own author — collapsed to latest-per-
                // target the same way the home feed is (see collapseRepostsToLatestPerTarget's
                // doc comment), so this profile's Notes tab shows "reposted" the same way the
                // feed does instead of only ever showing their own kind==requested-kind posts.
                val ownReposts = collapseRepostsToLatestPerTarget(
                    allEvents.filter {
                        it.pubkey.equals(pubkey, ignoreCase = true) &&
                            (it.kind == Event.KIND_REPOST || it.kind == Event.KIND_GENERIC_REPOST)
                    }
                )
                val selected = (
                    allEvents.filter { it.pubkey.equals(pubkey, ignoreCase = true) && it.kind == kind } +
                        ownReposts
                    )
                    .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
                    .take(limit)
                val resolution = resolveFeedEventsAndScheduleFetches(selected) { eventsById[it] }
                val resolved = resolution.resolved
                val neededPubkeys = (
                    resolved.map { it.targetEvent.pubkey } + resolved.mapNotNull { it.repostedByPubkey } + pubkey
                    ).distinct()
                val profiles = userRepository.getProfilesByPubkey(neededPubkeys)
                FeedNotesResult(
                    notes = buildCachedNoteViews(allEvents, profiles, selected),
                    pendingReposts = toPendingReposts(resolution.unresolvedReposts)
                )
            }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
        }

    // Single stable channel id, not one-per-query: a new search query replaces the previous
    // one's filter on the same REQ (subscribeChannel's normal fingerprint-diff behavior) instead
    // of piling up a concurrent subscription per keystroke-pause. Stays open across queries —
    // only the caller closing the search panel (see clearChannel(NostrChannels.SEARCH)) tears it
    // down; there's no EOSE/timeout auto-close here, since a still-open search panel should keep
    // absorbing slower relays' results for whatever the current query is.
    override suspend fun searchNotes(query: String): Flow<List<Event>> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return flowOf(emptyList())

        subscribeChannel(
            channelId = NostrChannels.SEARCH,
            filters = listOf(
                EventFilter(
                    kinds = setOf(Event.KIND_TEXT_NOTE),
                    search = normalizedQuery,
                    limit = 50
                )
            )
        )

        return observeSearchCandidateEvents()
            .map { events ->
                events
                    .asSequence()
                    .filter { it.kind == Event.KIND_TEXT_NOTE }
                    .filter { it.content.contains(normalizedQuery, ignoreCase = true) }
                    .take(50)
                    .toList()
            }
    }

    private fun isCurrentUserPubkey(pubkey: String): Boolean {
        return currentUserArchivePubkey() == pubkey.lowercase()
    }

    private fun currentUserArchivePubkey(): String? {
        return (userPreferences.getPublicKey() ?: activeUserPubkey)
            ?.let(NostrValidation::validate64HexOrNull)
            ?.takeIf { !ANON_PUBKEY_REGEX.matches(it) }
            ?.lowercase()
    }

    // applyIncomingDeletion moved to EventIngestCache.kt — see
    // eventIngestCache.applyIncomingDeletion, called from subscribeToEvents above.

}


