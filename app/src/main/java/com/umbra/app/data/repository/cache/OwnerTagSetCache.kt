package com.umbra.app.data.repository.cache

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared caching/ingestion engine behind Umbra's "single owner, one Set<String> of hex values
 * from a fixed tag name" NIP-51-shaped lists — ContactList (kind 3, "p" tags), MuteList (kind
 * 10000, "p" tags), PinList (kind 10001, "e" tags). Each concrete RepositoryImpl composes one
 * instance instead of reimplementing the identical StateFlow-cache / bootstrap-guard / ingest /
 * resolve-with-fallback machinery, keeping only its own interface-shaped method names
 * (follow/unfollow, mute/unmute, pin/unpin) as thin wrappers around [resolve]/[updateCache].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class OwnerTagSetCache<T>(
    private val kind: Int,
    private val tagName: String,
    private val scope: CoroutineScope,
    private val eventRepository: EventRepository,
    private val build: (ownerPubkey: String, values: Set<String>, updatedAt: Long) -> T,
    private val ownerOf: (T) -> String,
    private val updatedAtOf: (T) -> Long
) {
    private val state = MutableStateFlow<Map<String, T>>(emptyMap())
    private val bootstrapInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Ingests every matching-kind event from the shared recent-events stream. */
    fun startCollecting(limit: Int = 4000) {
        scope.launch {
            eventRepository.observeRecentEvents(limit = limit).collect { events -> ingest(events) }
        }
    }

    /**
     * Keeps the logged-in owner's own list hot from the authoritative per-owner stream, which
     * resolves to encrypted storage for the authenticated account.
     */
    fun startOwnerSync(ownerPubkeyFlow: Flow<String?>) {
        scope.launch {
            ownerPubkeyFlow
                .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
                .filterNotNull()
                .distinctUntilChanged()
                .flatMapLatest { ownerPubkey ->
                    eventRepository.observeEventsByPubkeyAndKind(pubkey = ownerPubkey, kind = kind, limit = 32)
                }
                .collect { ownerEvents -> ingest(ownerEvents) }
        }
    }

    /** Number of distinct owners currently cached — see ResourceUsageRepositoryImpl. */
    fun cachedOwnerCount(): Int = state.value.size

    fun observe(ownerPubkey: String): Flow<T?> {
        val normalized = ownerPubkey.lowercase()
        ensureHydrated(normalized)
        return state.map { cache -> cache[normalized] }.distinctUntilChanged()
    }

    suspend fun resolve(ownerPubkey: String): T {
        state.value[ownerPubkey]?.let { return it }

        // Bootstrap from encrypted/public event archive if available (latest matching-kind event
        // for owner).
        val fromEvent = runCatching {
            eventRepository.observeEventsByPubkeyAndKind(ownerPubkey, kind, limit = 1)
                .first()
                .firstOrNull()
                ?.let { event -> build(ownerPubkey, extractValues(event), event.createdAt) }
        }.getOrNull()

        if (fromEvent != null) {
            updateCache(fromEvent)
            return fromEvent
        }

        return build(ownerPubkey, emptySet(), 0L)
    }

    /**
     * Unconditional overwrite — intentionally so. Callers fall into two categories: [ingest]
     * (relay-sourced, guarded against staleness before it ever calls this — see [ingest]'s doc
     * comment) and the concrete repositories' follow/unfollow/mute/unmute/pin/unpin, which are
     * local, just-performed actions using `System.currentTimeMillis()/1000` as `updatedAt` — those
     * must always win immediately (optimistic local update, ahead of the signed event round-
     * tripping back from a relay), so no staleness check belongs here.
     */
    fun updateCache(list: T) {
        state.update { cache -> cache + (ownerOf(list).lowercase() to list) }
    }

    /**
     * Wipes every owner's cached list, not just the currently-active one — `state` is additive and
     * never evicts past owners (see [startOwnerSync]'s flatMapLatest, which re-ingests for a new
     * owner without removing the previous one's entry), so a logout/account switch that didn't call
     * this would leave the previous identity's contact/mute/pin list resident in memory for the
     * rest of the process's life.
     */
    fun clearAll() {
        state.value = emptyMap()
        bootstrapInFlight.clear()
    }

    /**
     * Drops every cached owner except [currentOwnerPubkey] (or everything, if `null` — i.e. no
     * one is signed in). Unlike [clearAll], never drops the signed-in owner's own list — this is
     * for a real-memory-pressure trim (see `TrimMemoryCachesUseCase`), where the whole point of
     * [state] being additive (see this class's own doc comment) is the problem to fix, but the
     * currently-active identity's contact/mute/pin list must stay hot.
     */
    fun trimToOwner(currentOwnerPubkey: String?) {
        val normalized = currentOwnerPubkey?.lowercase()
        state.update { cache -> if (normalized == null) emptyMap() else cache.filterKeys { it == normalized } }
    }

    /**
     * [events] is only ever a bounded top-N-by-created_at window over the id-keyed, access-order
     * [com.umbra.app.data.repository.cache.EventLruCache] (for any author other than the signed-in
     * user), which evicts by access recency, not by replaceable-kind semantics — it can and does
     * hold multiple revisions of the same (owner, kind) as separate entries. That means a newer
     * event correctly ingested in one batch can have its cache entry evicted by unrelated feed
     * activity, and an older event for the same owner can then surface as a "new" entry in a
     * later, independent batch. Without comparing against what's already cached for that owner
     * before overwriting, that later batch would silently downgrade the cache back to stale
     * content — mirrors the created_at guard [com.umbra.app.data.repository.UserRepositoryImpl]
     * already applies to profile/relay-list saves; this cache never had the equivalent.
     *
     * Only applied here, not inside [updateCache] itself — see that function's doc comment for why
     * local follow/unfollow/mute/unmute/pin/unpin writes must stay unconditional.
     */
    private fun ingest(events: List<Event>) {
        val latestByOwner = events
            .asSequence()
            .filter { it.kind == kind }
            .filter { it.pubkey.length == 64 }
            .groupBy { it.pubkey.lowercase() }
            .mapValues { (_, ownerEvents) ->
                // NIP-01 tie-break on an exact created_at tie: lowest id wins. maxWithOrNull needs
                // thenByDescending here (not thenBy) to select the lowest id as the max element.
                ownerEvents.maxWithOrNull(
                    compareBy<Event> { it.createdAt }
                        .thenBy { it.tags.size }
                        .thenByDescending { it.id }
                )
            }

        if (latestByOwner.isEmpty()) return

        val currentState = state.value
        latestByOwner.values.filterNotNull().forEach { event ->
            val candidate = build(event.pubkey.lowercase(), extractValues(event), event.createdAt)
            val owner = ownerOf(candidate).lowercase()
            val existing = currentState[owner]
            if (existing == null || updatedAtOf(existing) < updatedAtOf(candidate)) {
                updateCache(candidate)
            }
        }
    }

    private fun extractValues(event: Event): Set<String> =
        event.getTagValues(tagName)
            .asSequence()
            .map { it.lowercase() }
            .filter { it.length == 64 }
            .toSet()

    private fun ensureHydrated(ownerPubkey: String) {
        if (!bootstrapInFlight.add(ownerPubkey)) return
        scope.launch {
            try {
                updateCache(resolve(ownerPubkey))
            } finally {
                bootstrapInFlight.remove(ownerPubkey)
            }
        }
    }
}
