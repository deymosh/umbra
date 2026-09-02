package com.umbra.app.data.repository.cache

import com.umbra.app.domain.nip01.Event
import java.util.concurrent.atomic.AtomicLong

/**
 * Size-bounded, access-order cache of in-memory-only events (everyone except the signed-in
 * user, whose own events persist to the encrypted Room archive instead — see AUDIT.md).
 * Eviction is by recency of *access*, not insertion order, so content a user has scrolled back
 * to and is actively viewing survives unrelated live-feed churn instead of being evicted just
 * because it arrived first.
 *
 * Implemented directly on `LinkedHashMap(accessOrder = true)` + `removeEldestEntry` rather than
 * `android.util.LruCache` (which does exactly this internally) so it stays plain-JVM testable —
 * this project's unit tests run against the unmocked Android stub jar, which throws for any
 * `android.*` framework method call at runtime (no Robolectric configured).
 *
 * Not internally thread-safe (same as the plain `HashMap` it replaces) — callers must coordinate
 * access with their own lock, as [EventRepositoryImpl] already does via `cachedEventsMutex`.
 *
 * **Considered and deliberately deferred:** replacing this fixed-count LRU with a
 * `WeakReference`-based, GC/heap-driven cache with no fixed ceiling — entries would vanish as
 * soon as nothing else holds a strong ref, not just under memory pressure. That kind of cache
 * only gets useful retention by pairing it with a separate "pinning" layer (explicit
 * strong refs kept for follows/bookmarks/the active thread/visible feed window) — without that
 * layer, a raw weak-ref swap would likely retain *less* than this class does today for ordinary
 * scroll-back-through-feed use, not more. It would also need to move `onEvicted` from a
 * synchronous callback (which [EventRepositoryImpl] depends on to keep `cachedEngagementIndex` in
 * sync) to an async/best-effort sweep, and it would break every deterministic test in
 * `EventLruCacheTest.kt` (you cannot reliably force GC to reclaim one specific weak reference and
 * not another from a plain JVM unit test). Real effort if revisited: a multi-day initiative
 * (build the pinning layer, rework eviction-driven bookkeeping, accept some behavior becomes
 * untestable outside an instrumented device test), not a quick swap — see the corresponding
 * AUDIT.md Part 4 entry.
 */
class EventLruCache(
    val maxSize: Int,
    private val onEvicted: (Event) -> Unit
) {
    // Cumulative for the process lifetime — never reset, including by clear(). Atomic because
    // the onEvicted callback fires synchronously from removeEldestEntry while a caller may
    // already hold its own coordinating lock, and trimTo is reached from a different dispatcher
    // (trimMemory) than put's ingest path — plain Long would let telemetry itself introduce a
    // race. AtomicLong is java.util.concurrent, not android.*, so this stays plain-JVM testable.
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)

    private val map = object : LinkedHashMap<String, Event>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Event>): Boolean {
            val shouldEvict = size > maxSize
            // Fires synchronously while the caller may already be holding its own coordinating
            // lock — must stay a plain, synchronous callback and must NEVER itself try to
            // acquire that lock, or every future eviction deadlocks.
            if (shouldEvict) {
                relaysByEventId.remove(eldest.value.id)
                evictionCount.incrementAndGet()
                onEvicted(eldest.value)
            }
            return shouldEvict
        }
    }

    // Which relay(s) delivered each cached event — transport provenance, not part of the Nostr
    // event itself, so it's tracked here rather than on the domain Event. Bounded to exactly the
    // ids currently in [map] (see [recordRelay]'s guard and the eviction/removal cleanup below),
    // so this can never outlive or outgrow the main cache.
    private val relaysByEventId = mutableMapOf<String, MutableSet<String>>()

    fun get(id: String): Event? {
        val event = map[id]
        if (event != null) hitCount.incrementAndGet() else missCount.incrementAndGet()
        return event
    }

    fun put(event: Event) {
        map[event.id] = event
    }

    /** Records that [relayUrl] delivered [eventId] — a no-op if [eventId] isn't (or is no longer)
     * cached, so a repeat delivery of an event this cache never stored (or already evicted) can't
     * leak an entry into [relaysByEventId] with nothing in [map] to bound its lifetime. */
    fun recordRelay(eventId: String, relayUrl: String) {
        if (!map.containsKey(eventId)) return
        relaysByEventId.getOrPut(eventId) { mutableSetOf() }.add(relayUrl)
    }

    /** Relay(s) known to have delivered [eventId], if any — empty if unrecorded or evicted. */
    fun getRelays(eventId: String): Set<String> = relaysByEventId[eventId].orEmpty()

    fun remove(id: String): Event? {
        relaysByEventId.remove(id)
        return map.remove(id)
    }

    fun snapshot(): List<Event> = map.values.toList()

    fun clear() {
        map.clear()
        relaysByEventId.clear()
    }

    /**
     * Evicts least-recently-accessed entries (same order [removeEldestEntry] uses, since
     * `accessOrder = true` iterates the backing map oldest-first) down to [target], running
     * [onEvicted] for each removal exactly as automatic eviction does. Does not change [maxSize]
     * — this is a temporary shrink for real memory pressure, not a redesign of the cache's normal
     * ceiling; the cache is free to grow back to [maxSize] afterward via ordinary [put] calls.
     */
    fun trimTo(target: Int) {
        val iterator = map.entries.iterator()
        while (map.size > target && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            relaysByEventId.remove(eldest.value.id)
            evictionCount.incrementAndGet()
            onEvicted(eldest.value)
        }
    }

    val size: Int get() = map.size

    /**
     * A fresh, immutable snapshot of this cache's cumulative hit/miss/eviction counts, taken at
     * the moment of the call. Counts only — no event content, pubkeys, relay URLs, or event ids
     * ever flow through here. Read in-process for a local debug log only; never sent off-device.
     */
    val stats: CacheStats
        get() = CacheStats(
            hits = hitCount.get(),
            misses = missCount.get(),
            evictions = evictionCount.get()
        )
}

/**
 * Immutable snapshot of [EventLruCache]'s cumulative hit/miss/eviction counters, taken via
 * [EventLruCache.stats]. Counts only — no event content, pubkeys, relay URLs, or event ids.
 * Read in-process for a local debug log; never sent off-device (Umbra ships no analytics or
 * crash reporter, and this type must never become the first).
 */
data class CacheStats(val hits: Long, val misses: Long, val evictions: Long)
