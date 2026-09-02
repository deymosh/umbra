package com.umbra.app.data.repository

import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.data.db.entities.EventTagEntity
import com.umbra.app.data.repository.cache.EventLruCache
import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.model.EventCacheStats
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.ReplaceableEventKey
import com.umbra.app.domain.nip01.replaceableKey
import com.umbra.app.domain.nip01.winsReplaceableRace
import com.umbra.app.domain.nip18.parseRepostedEvent
import com.umbra.app.util.logging.UmbraLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outcome of one [EventIngestCache.ingest] call — the post-ingest cache size (for the ingestion
 * pipeline's periodic activity log) and whether this specific event actually landed in the
 * in-memory cache (vs. skipped because it's not the winning revision of a replaceable slot, or
 * this author/session combination isn't eligible for in-memory caching at all). */
internal data class IngestOutcome(val cacheSize: Int, val storedInMemoryCache: Boolean)

/** A batched, not-yet-durable insert into the encrypted own-archive — one per verified event
 * authored by the signed-in user, queued by [EventIngestCache.scheduleInsert] and flushed as one
 * transaction by [OwnEventArchive.writeBatch]. Promoted to top level from a private nested class
 * on [EventRepositoryImpl] when the persistence half of the ingest cluster moved here. */
internal data class PendingEventInsert(
    val entity: EventEntity,
    val tags: List<EventTagEntity>,
    val replaceableKey: ReplaceableEventKey? = null
)

// Kinds an unsolicited (not explicitly requested, not the signed-in user's own) event must be one
// of to even be considered for persistence into the encrypted own-archive — see
// EventIngestCache.shouldPersistEvent. Also used by EventRepositoryImpl.scheduleNegentropySync()
// to scope NIP-77 sync to the same set of kinds this class is willing to persist.
internal val USEFUL_PERSISTED_KINDS = setOf(
    Event.KIND_METADATA,
    Event.KIND_TEXT_NOTE,
    Event.KIND_CONTACT_LIST,
    Event.KIND_MUTED_USERS,
    Event.KIND_RELAY_LIST_METADATA,
    Event.KIND_SEARCH_RELAYS,
    Event.KIND_DM_RELAY_LIST,
    Event.KIND_INDEX_RELAYS,
    Event.KIND_REPOST,
    Event.KIND_GENERIC_REPOST,
    Event.KIND_REACTION,
    Event.KIND_ZAP_RECEIPT
)

// Control/list kinds that are always persisted once useful-kind-eligible, regardless of the
// active feed filter's mute/NSFW/hashtag exclusions — see EventIngestCache.shouldPersistEvent.
internal val ALWAYS_PERSIST_CONTROL_KINDS = setOf(
    Event.KIND_METADATA,
    Event.KIND_CONTACT_LIST,
    Event.KIND_MUTED_USERS,
    Event.KIND_RELAY_LIST_METADATA,
    Event.KIND_SEARCH_RELAYS,
    Event.KIND_DM_RELAY_LIST,
    Event.KIND_INDEX_RELAYS,
    Event.KIND_BLOSSOM_SERVER_LIST
)

/**
 * The narrow slice of the encrypted own-user archive [EventIngestCache] needs — mirrors
 * [NegentropyEventSource]'s narrowing precedent so a plain JVM unit test can supply a small fake
 * instead of a real Room database instance. This project runs no Robolectric, and the SQLCipher-
 * backed encrypted database class cannot be meaningfully constructed or have its transaction
 * helper stubbed in `testDebugUnitTest` — taking that concrete database type as a constructor
 * parameter here would make this whole class unconstructible in a unit test. The transaction
 * boundary itself lives behind [writeBatch], so the Room dependency stays entirely on the facade
 * side (see [EventRepositoryImpl]'s private implementation of this interface).
 */
internal interface OwnEventArchive {
    /** Performs the whole batched-insert transaction as one unit: insert events, delete then
     * re-insert tags for those events, then delete any now-superseded replaceable revisions. */
    suspend fun writeBatch(batch: List<PendingEventInsert>)
    suspend fun getEventsByIds(ids: List<String>): List<EventEntity>
    suspend fun deleteEventById(id: String)
    suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): EventEntity?
}

/**
 * In-memory event ingest/cache/persist collaborator extracted from [EventRepositoryImpl].
 * Constructor shape and manual-instantiation style follow
 * [NegentropySyncOrchestrator]'s / [EventChannelRouting]'s precedent: a package-`internal class`,
 * manually constructed by the facade (not Hilt-injected).
 *
 * Owns four things as one cohesive, single-mutex-guarded unit — this grouping is deliberate, not
 * incidental: the LRU cache, the engagement index, the replaceable-event "latest revision per
 * slot" bookkeeping, and the mutex guarding all three. [EventLruCache]'s `onEvicted` callback
 * fires synchronously, potentially while [ingest] already holds [cachedEventsMutex] — it must
 * stay a plain, synchronous, same-class callback and must never itself try to reacquire that
 * lock, or every future eviction deadlocks. Splitting these four across a class boundary would
 * turn that contract into a cross-class one with no compiler enforcement (see [EventIngestCacheTest]'s
 * synchronous-eviction and no-deadlock regression tests, which pin this).
 *
 * Also owns the three cached-event [SharedFlow]s and the 250ms burst-coalescing snapshot emitter:
 * a rapid run of ingests inside one [SNAPSHOT_BATCH_MS] window collapses into exactly
 * one [cachedEventsFlow] emission and one [cachedEventBundles] emission, not one of each per
 * event. [EventRepositoryImpl.observeFeedNotes]/`observeProfileNotes` compose `merge`/`conflate`/
 * `map` directly on these exposed properties — the same underlying [MutableSharedFlow] instances
 * every caller composed on before extraction, never a re-wrapped flow, so the replay-1/drop-oldest
 * backpressure semantics those two consumers depend on survive unchanged.
 *
 * Finally, owns the persistence half of the cluster: the persist-eligibility decision
 * ([shouldPersistEvent]), repost-target caching ([cacheVerifiedRepostTarget]), incoming-event
 * normalization ([normalizeIncomingEvent]), the debounced own-archive write ([scheduleInsert]),
 * and incoming-deletion application ([applyIncomingDeletion]) — all reaching the encrypted archive
 * only through the narrow [ownEventArchive] seam, never a concrete Room type. [activeFeedFilter]
 * is read at call time on every [shouldPersistEvent] evaluation, never captured once: every
 * exclusion it drives (muted pubkeys, NSFW, excluded hashtags/tags/content-prefixes) is
 * user-owned, user-editable, user-removable state, and reading it live is what keeps that promise
 * true after extraction.
 *
 * BIP-340 Schnorr and event-id integrity verification (the domain crypto gate) runs upstream of
 * this class entirely, inside [EventRepositoryImpl.subscribeToEvents] — no method here re-verifies
 * or may run ahead of that gate, with one deliberate exception: [cacheVerifiedRepostTarget]'s own
 * verify-the-embedded-event call on the NIP-18 repost's inner content, which is a second,
 * independent verification of a *different* event than the one that already passed the outer
 * gate.
 */
internal class EventIngestCache(
    private val repoScope: CoroutineScope,
    private val maxInMemoryEvents: Int,
    private val ownEventArchive: OwnEventArchive,
    private val seenEventIds: MutableSet<String>,
    private val activeFeedFilter: () -> FeedFilter,
    private val isCurrentUserPubkey: (String) -> Boolean,
    private val isPendingEventLookupId: (String) -> Boolean,
    private val isPinnedProfileAuthor: (String) -> Boolean,
    private val isWiping: () -> Boolean
) {
    private companion object {
        private const val TAG = "EventIngestCache"
        private const val SNAPSHOT_BATCH_MS = 250L
        // Local, counts-only debug signal — no analytics, no crash reporter, never
        // leaves the device. See the class doc comment and startTelemetryLogging's own comment.
        private const val CACHE_TELEMETRY_LOG_INTERVAL_MS = 60_000L
        // Matches the debounce that already existed on the facade before this method moved here —
        // coalesces a burst of verified own-events into one batched archive transaction.
        private const val INSERT_DEBOUNCE_MS = 200L
    }

    private val logger = UmbraLog.tag(TAG)

    private val cachedEngagementIndex = EventEngagementIndex()
    private val cachedEvents = EventLruCache(
        maxSize = maxInMemoryEvents,
        onEvicted = { evictedEvent -> cachedEngagementIndex.remove(evictedEvent.id) }
    )
    // Newest known event id per replaceable-event slot (NIP-01/33) currently resident in
    // cachedEvents — lets ingestion proactively evict a superseded revision (e.g. an older
    // kind-0) instead of leaving it reachable via cachedEvents.get()/snapshot() until the LRU
    // happens to reclaim it. Guarded by cachedEventsMutex, same as the two caches above.
    private val latestReplaceableEventId = mutableMapOf<ReplaceableEventKey, String>()
    private val cachedEventsMutex = Mutex()

    private val _cachedEventsFlow = MutableSharedFlow<List<Event>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _cachedEventBundles = MutableSharedFlow<Set<Event>>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _feedRebuildSignals = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Same underlying [MutableSharedFlow] every consumer composed on before extraction —
     * [EventRepositoryImpl.getCachedEvents]/`observeRecentEvents`/`observeSearchCandidateEvents`/
     * `observeEventsByPubkeyAndKind`/`observeCountEventsByPubkeyAndKind` and
     * `observeProfileNotes`'s `.conflate()` call sites all read this exact instance. */
    val cachedEventsFlow: SharedFlow<List<Event>> = _cachedEventsFlow.asSharedFlow()

    /** Per-ingest-burst bundle of newly-cached events, coalesced by [scheduleSnapshotEmit] — read
     * by [EventRepositoryImpl.observeFeedNotes]'s `merge(...)` as the incremental-update source. */
    val cachedEventBundles: SharedFlow<Set<Event>> = _cachedEventBundles.asSharedFlow()

    /** Fired when a full feed recompute (not an incremental bundle) is needed — e.g. after
     * [clearAll]/[trimTo]/[removeCachedEvent]/[removeCachedEventsAuthoredBy] change cache
     * membership out from under an already-built feed snapshot. */
    val feedRebuildSignals: SharedFlow<Unit> = _feedRebuildSignals.asSharedFlow()

    private var snapshotEmitJob: Job? = null
    private val pendingSnapshotEvents: MutableSet<Event> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var snapshotEmitPending: Boolean = false

    // Own-archive write queue (moved in with scheduleInsert) — a verified own-event is queued
    // here and flushed as one batched ownEventArchive.writeBatch() transaction after
    // INSERT_DEBOUNCE_MS of quiet, coalescing a burst into one archive write instead of one per
    // event.
    private val pendingInserts = ConcurrentLinkedQueue<PendingEventInsert>()
    private val insertDebounceJob = AtomicReference<Job?>(null)

    /**
     * Atomically ingests [event] delivered by [relayUrl]: resolves NIP-01/33 replaceable-event
     * superseding (LOG-1/LOG-6), indexes engagement, stores into the LRU cache, and records relay
     * provenance — all inside one [cachedEventsMutex] acquisition, so the atomicity across all
     * three structures is the fix, not something layered on top of it. [currentUserPubkey] gates
     * whether this author/session combination is eligible for in-memory caching at all
     * ([shouldStoreInMemoryCache]) — this class has no notion of "the signed-in user" beyond that
     * one parameter.
     */
    suspend fun ingest(event: Event, relayUrl: String, currentUserPubkey: String?): IngestOutcome {
        return if (shouldStoreInMemoryCache(event.pubkey, currentUserPubkey)) {
            cachedEventsMutex.withLock {
                val replaceableKey = event.replaceableKey()
                val supersededId = replaceableKey?.let { latestReplaceableEventId[it] }
                val superseded = supersededId?.let(cachedEvents::get)
                if (superseded != null && !event.winsReplaceableRace(superseded)) {
                    // A stale/losing revision of an already-cached replaceable slot (e.g.
                    // an older kind-0) — id-keyed storage would let it coexist alongside
                    // the newer revision until the LRU happens to reclaim it, so it's
                    // dropped here instead. Still validly received/verified this session
                    // (dedup/verification bookkeeping already ran upstream).
                    IngestOutcome(cacheSize = cachedEvents.size, storedInMemoryCache = false)
                } else {
                    if (replaceableKey != null) {
                        if (supersededId != null && supersededId != event.id) {
                            // Evict the superseded revision now rather than waiting for
                            // the LRU to reclaim it. EventEngagementIndex only tracks
                            // kind 1/6/7 (see EventEngagementIndex.add), never the
                            // replaceable kinds this branch handles, so no
                            // cachedEngagementIndex.remove() is needed for it.
                            cachedEvents.remove(supersededId)
                        }
                        latestReplaceableEventId[replaceableKey] = event.id
                    }
                    // Order matters: index the new event before it's inserted, so if this
                    // put() evicts an older entry, EventLruCache's onEvicted callback removing
                    // that entry from the index can't race the new entry's own indexing.
                    cachedEngagementIndex.add(event)
                    cachedEvents.put(event)
                    cachedEvents.recordRelay(event.id, relayUrl)
                    IngestOutcome(cacheSize = cachedEvents.size, storedInMemoryCache = true)
                }
            }
        } else {
            IngestOutcome(cacheSize = cachedEventsMutex.withLock { cachedEvents.size }, storedInMemoryCache = false)
        }
    }

    /** Records that [relayUrl] also delivered an already-seen, already-processed [eventId] —
     * the dedupe path (this event was already ingested once this session by a different relay). */
    suspend fun recordRelayForSeenEvent(eventId: String, relayUrl: String) {
        cachedEventsMutex.withLock { cachedEvents.recordRelay(eventId, relayUrl) }
    }

    /**
     * NIP-18: caches an already-verified repost target [target] into the in-memory L1 cache, so
     * the feed's repost-unwrap step can resolve it via a plain [getCached]/[getCachedByIds] lookup
     * the same way it resolves any other externally-authored event, instead of re-parsing +
     * re-verifying the repost's embedded JSON on every feed emission. Callers are responsible for
     * parsing/verifying [target] and checking [shouldStoreInMemoryCache] first — this method is
     * only the cache-write half.
     */
    suspend fun cacheRepostTarget(target: Event) {
        cachedEventsMutex.withLock {
            cachedEngagementIndex.add(target)
            cachedEvents.put(target)
        }
    }

    suspend fun getCached(id: String): Event? = cachedEventsMutex.withLock { cachedEvents.get(id) }

    suspend fun getCachedRelays(eventId: String): Set<String> =
        cachedEventsMutex.withLock { cachedEvents.getRelays(eventId) }

    suspend fun getCachedByIds(ids: List<String>): List<Event> =
        cachedEventsMutex.withLock { ids.mapNotNull(cachedEvents::get) }

    suspend fun snapshot(): List<Event> = cachedEventsMutex.withLock { cachedEvents.snapshot() }

    suspend fun engagementSnapshot(): Map<String, EngagementCounts> =
        cachedEventsMutex.withLock { cachedEngagementIndex.snapshot() }

    suspend fun cacheStats(): EventCacheStats =
        cachedEventsMutex.withLock { EventCacheStats(cachedEvents.size, cachedEvents.maxSize) }

    /** Evicts least-recently-accessed entries down to [target] (a temporary shrink for real
     * memory pressure — see [EventLruCache.trimTo]). Returns the number of entries removed. */
    suspend fun trimTo(target: Int): Int = cachedEventsMutex.withLock {
        val before = cachedEvents.size
        cachedEvents.trimTo(target)
        before - cachedEvents.size
    }

    suspend fun removeCachedEvent(eventId: String) {
        cachedEventsMutex.withLock {
            cachedEvents.remove(eventId)
            cachedEngagementIndex.remove(eventId)
        }
    }

    /**
     * NIP-09: removes each of [eventIds] that is both currently cached AND authored by
     * [authorPubkey] — the ownership check is a correctness requirement (a deletion may only
     * affect events authored by the deletion's own signer), not an optimization, so it stays
     * unconditional here rather than being trusted to the caller.
     */
    suspend fun removeCachedEventsAuthoredBy(eventIds: List<String>, authorPubkey: String) {
        cachedEventsMutex.withLock {
            eventIds.forEach { targetId ->
                cachedEvents.get(targetId)
                    ?.takeIf { it.pubkey.equals(authorPubkey, ignoreCase = true) }
                    ?.let {
                        cachedEvents.remove(targetId)
                        cachedEngagementIndex.remove(targetId)
                    }
            }
        }
    }

    /** Empties the cache, the engagement index, and the replaceable-slot map together (one
     * mutex acquisition, matching [ingest]'s atomicity), then emits an empty snapshot so any
     * active feed collector reflects the wipe immediately instead of on its next unrelated
     * emission. */
    suspend fun clearAll() {
        cachedEventsMutex.withLock {
            cachedEvents.clear()
            cachedEngagementIndex.clear()
            latestReplaceableEventId.clear()
        }
        emitEmptySnapshot()
    }

    /**
     * The 250ms burst-coalescing snapshot emitter: a run of [enqueueSnapshotEvent] +
     * `scheduleSnapshotEmit` calls inside one [SNAPSHOT_BATCH_MS] window collapses into exactly
     * one [cachedEventsFlow] emission (the full cache snapshot) and one [cachedEventBundles]
     * emission (just the events enqueued during the window), not one of each per event.
     */
    fun scheduleSnapshotEmit() {
        snapshotEmitPending = true
        if (snapshotEmitJob?.isActive == true) return

        snapshotEmitJob = repoScope.launch {
            delay(SNAPSHOT_BATCH_MS)
            if (!snapshotEmitPending) return@launch
            snapshotEmitPending = false
            val snapshot = cachedEventsMutex.withLock { cachedEvents.snapshot() }
            val bundle = buildSet {
                while (pendingSnapshotEvents.isNotEmpty()) {
                    addAll(pendingSnapshotEvents)
                    pendingSnapshotEvents.clear()
                }
            }
            _cachedEventsFlow.tryEmit(snapshot)
            if (bundle.isNotEmpty()) {
                _cachedEventBundles.tryEmit(bundle)
            }
        }
    }

    /** Queues [event] into the next coalesced [cachedEventBundles] emission — call alongside
     * [scheduleSnapshotEmit], not instead of it. */
    fun enqueueSnapshotEvent(event: Event) {
        pendingSnapshotEvents.add(event)
    }

    /** Cancels any in-flight [scheduleSnapshotEmit] debounce and drops its pending state —
     * for callers (e.g. [EventRepositoryImpl.clearAllData]) that are about to wipe all data and
     * must ensure no stale scheduled emit fires afterward. */
    fun cancelPendingSnapshotEmit() {
        snapshotEmitJob?.cancel()
        snapshotEmitJob = null
        snapshotEmitPending = false
        pendingSnapshotEvents.clear()
    }

    /** Emits the current cache contents immediately (bypassing the 250ms coalescing window) —
     * for call sites that just mutated cache membership directly (e.g. delete/trim) and need the
     * next [cachedEventsFlow] collection to reflect it right away. */
    suspend fun emitSnapshotNow() {
        _cachedEventsFlow.tryEmit(cachedEventsMutex.withLock { cachedEvents.snapshot() })
    }

    /** Signals a full feed rebuild is needed (as opposed to an incremental [cachedEventBundles]
     * update) — see [feedRebuildSignals]'s doc comment. */
    fun signalFeedRebuild() {
        _feedRebuildSignals.tryEmit(Unit)
    }

    /** Emits an empty cache snapshot directly, without reading [cachedEvents] — used by
     * [clearAll] and by callers that already know the cache is (or should appear) empty. */
    fun emitEmptySnapshot() {
        _cachedEventsFlow.tryEmit(emptyList())
    }

    /**
     * Decides whether [event] is eligible to reach the encrypted own-archive at all (the gate
     * [scheduleInsert] is called behind, for every relay-delivered event, not just the signed-in
     * user's own). The signed-in user's own events and explicitly-requested-by-id events always
     * pass; everything else must be a useful, filter-eligible kind. Every exclusion below
     * ([activeFeedFilter]'s `excludedHashtags`/`excludedTags`/`excludedContentPrefixes`/
     * `mutedPubkeys`/`hideNsfw`) is read from the live, user-editable filter at call time — never
     * captured once, never a hardcoded fallback — because muting, NSFW hiding, and feed content
     * filters are user-owned state the user can edit and fully remove via FeedConfigScreen/
     * ProfileScreen, not a fixed app-side decision about what a user is allowed to see.
     */
    fun shouldPersistEvent(event: Event): Boolean {
        if (isCurrentUserPubkey(event.pubkey)) return true
        // Explicitly requested by id (a quote/mention/repost target being resolved via
        // fetchEventById) — always cache it regardless of kind, since USEFUL_PERSISTED_KINDS
        // below is tuned for the generic/unsolicited ingestion path (broad feed subscriptions),
        // not for "the user is looking at a reference to this exact event right now." Without
        // this, a quoted kind that isn't in USEFUL_PERSISTED_KINDS (e.g. a long-form article or
        // any other not-yet-content-parsed kind) never gets cached, so fetchEventById's caller
        // polls forever and the quote is stuck as an unresolved chip no matter how long it waits.
        if (isPendingEventLookupId(event.id)) return true
        if (event.kind !in USEFUL_PERSISTED_KINDS) return false

        // Read the active filter up front (not just below) so its excludedHashtags/excludedTags/
        // excludedContentPrefixes — which default to FilterDefaults' hygiene baseline but are
        // fully user-editable via FeedConfigScreen — are what actually gates a kind-1 note here,
        // not the unconditional hardcoded defaults isUsefulClientNote() falls back to for callers
        // with no live filter to pass (e.g. ProfileScreen).
        val filter = activeFeedFilter()
        if (event.kind == Event.KIND_TEXT_NOTE &&
            !event.isUsefulClientNote(
                excludedHashtags = filter.excludedHashtags,
                excludedTagNamePrefixes = filter.excludedTags,
                excludedContentPrefixes = filter.excludedContentPrefixes
            )
        ) return false

        if (event.kind in ALWAYS_PERSIST_CONTROL_KINDS) return true
        if (event.kind == Event.KIND_REPOST ||
            event.kind == Event.KIND_GENERIC_REPOST ||
            event.kind == Event.KIND_REACTION ||
            event.kind == Event.KIND_ZAP_RECEIPT ||
            (event.kind == Event.KIND_TEXT_NOTE && event.isReply())
        ) return true
        if (isPinnedProfileAuthor(event.pubkey.lowercase())) return true

        // Muted authors: skip events from muted pubkeys in the active filter
        if (filter.mutedPubkeys.any { it.equals(event.pubkey, ignoreCase = true) }) return false

        // NSFW hiding
        if (filter.hideNsfw && event.hasAnyHashtag(setOf("nsfw"))) return false

        return true
    }

    /**
     * NIP-18: a repost's content SHOULD carry the full serialized original event — cache it into
     * the in-memory L1 cache right away (verified first: parseRepostedEvent only parses JSON, it
     * doesn't check the embedded signature) so the feed's repost unwrap step
     * (selectHybridFeedNotes/buildIndexedNoteViews) can resolve the target via a plain
     * eventsById lookup, the same way it resolves any other externally-authored event, instead of
     * re-parsing+re-verifying the embedded JSON on every feed emission. A repost with no/malformed
     * embedded content is a no-op here — its target then only resolves if already cached some
     * other way (e.g. we also follow its author), same as today.
     */
    suspend fun cacheVerifiedRepostTarget(event: Event, currentUserPubkey: String?) {
        if (event.kind != Event.KIND_REPOST && event.kind != Event.KIND_GENERIC_REPOST) return
        val target = parseRepostedEvent(event) ?: return
        if (!withContext(Dispatchers.Default) { EventCrypto.verifyEvent(target) }) return
        if (!shouldStoreInMemoryCache(target.pubkey, currentUserPubkey)) return
        cacheRepostTarget(target)
    }

    fun normalizeIncomingEvent(event: Event): Event {
        if (event.pubkey.none { it.isUpperCase() }) return event
        return event.copy(pubkey = event.pubkey.lowercase())
    }

    /**
     * Queues [entity] (plus [tags]/[replaceableKey]) for a debounced, batched write to the
     * encrypted own-archive via [ownEventArchive]. [isWiping] and the current-user gate both stay
     * ahead of the queue add: only the signed-in user's own events may ever reach the encrypted
     * archive, which is the whole basis of this app's persistence model (everyone else's content
     * lives only in the in-memory cache above). The 200ms debounce (see [INSERT_DEBOUNCE_MS])
     * coalesces a burst of own-events into one batched transaction rather than one per event.
     */
    fun scheduleInsert(
        entity: EventEntity,
        tags: List<EventTagEntity> = emptyList(),
        replaceableKey: ReplaceableEventKey? = null
    ) {
        if (isWiping()) return
        if (!isCurrentUserPubkey(entity.pubkey)) return
        pendingInserts.add(PendingEventInsert(entity = entity, tags = tags, replaceableKey = replaceableKey))
        val newJob = repoScope.launch(Dispatchers.IO) {
            delay(INSERT_DEBOUNCE_MS)
            val batch = buildList {
                while (pendingInserts.isNotEmpty()) pendingInserts.poll()?.let { add(it) }
            }
            if (batch.isNotEmpty()) {
                ownEventArchive.writeBatch(batch)
            }
        }
        insertDebounceJob.getAndSet(newJob)?.cancel()
    }

    /** Cancels any in-flight [scheduleInsert] debounce and drops its queued-but-not-yet-written
     * batch — for callers (e.g. [EventRepositoryImpl.clearAllData]) that are about to wipe all
     * data and must ensure no stale batched write lands afterward. Mirrors
     * [cancelPendingSnapshotEmit]'s role for the snapshot-emit debounce. */
    fun cancelPendingInserts() {
        insertDebounceJob.getAndSet(null)?.cancel()
        pendingInserts.clear()
    }

    /**
     * NIP-09: apply incoming deletion event to locally cached events.
     * Only deletes notes authored by the same pubkey that signed the delete request.
     */
    suspend fun applyIncomingDeletion(deletionEvent: Event) {
        val eTagIds = deletionEvent.getTagValues("e")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        // "a" tags reference addressable events as "kind:pubkey:d-identifier" rather than an
        // id — resolved to concrete ids below (via the same getLatestAddressableEvent lookup
        // ProfileViewModel/EventRepositoryImpl already use elsewhere), since the in-memory and
        // Room deletes below both key on event id.
        val aTagCoordinates = deletionEvent.getTagValues("a")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (eTagIds.isEmpty() && aTagCoordinates.isEmpty()) return

        val resolvedAddressableIds = mutableListOf<String>()

        withContext(Dispatchers.IO) {
            // Only the signed-in user's own events are ever persisted to Room (the encrypted
            // archive) — everyone else's content lives only in the in-memory cache, which is
            // cleared below regardless of author. A deletion authored by someone else therefore
            // never matches anything here; this only does real work for the user's own notes.
            val encryptedTargets = ownEventArchive.getEventsByIds(eTagIds)
                .filter { it.pubkey.equals(deletionEvent.pubkey, ignoreCase = true) }
            encryptedTargets.forEach { target ->
                ownEventArchive.deleteEventById(target.id)
                seenEventIds.remove(target.id)
            }

            aTagCoordinates.forEach { coordinate ->
                val parts = coordinate.split(":", limit = 3)
                if (parts.size != 3) return@forEach
                val kind = parts[0].toIntOrNull() ?: return@forEach
                val authorPubkey = parts[1]
                val identifier = parts[2]
                // Spec: client must validate the a-tag's own pubkey matches the deletion
                // request's pubkey — same ownership check as the e-tag path above.
                if (!authorPubkey.equals(deletionEvent.pubkey, ignoreCase = true)) return@forEach

                // Spec: an a-tag deletion only removes versions up to this request's own
                // created_at, not any version published after it.
                ownEventArchive.getLatestAddressableEvent(kind, authorPubkey, identifier)
                    ?.takeIf { it.createdAt <= deletionEvent.createdAt }
                    ?.let { target ->
                        ownEventArchive.deleteEventById(target.id)
                        seenEventIds.remove(target.id)
                        resolvedAddressableIds.add(target.id)
                    }
            }
        }

        val targetIds = (eTagIds + resolvedAddressableIds).distinct()
        if (targetIds.isEmpty()) return

        removeCachedEventsAuthoredBy(targetIds, deletionEvent.pubkey)
        emitSnapshotNow()
        signalFeedRebuild()
    }

    /**
     * Periodic, local-only, counts-only debug log of cache occupancy and cumulative hit/miss/
     * eviction counters — copies the shape of [EventRepositoryImpl]'s existing discovered-
     * relay idle sweep (`repoScope.launch { while (true) { delay(...); ... } }`). Routed through
     * the [logger] field (this class's own scrubbed logging-utility tag), whose `d { }` is already
     * `Log.isLoggable`-gated, never raw `android.util.Log`.
     * Carries counts and the ceiling only — no event id, event content, pubkey, relay URL, or
     * filter body ever reaches this line, and it has no network, file, or third-party sink: this
     * is a privacy-first client with no analytics and no crash reporter. Counters are cumulative
     * for the app session and are never reset here or anywhere else.
     */
    fun startTelemetryLogging() {
        repoScope.launch {
            while (true) {
                delay(CACHE_TELEMETRY_LOG_INTERVAL_MS)
                val stats = cachedEvents.stats
                val currentSize = cachedEventsMutex.withLock { cachedEvents.size }
                logger.d {
                    "Cache telemetry: size=$currentSize/$maxInMemoryEvents " +
                        "hits=${stats.hits} misses=${stats.misses} evictions=${stats.evictions}"
                }
            }
        }
    }
}
