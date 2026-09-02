package com.umbra.app.data.repository.cache

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLruCacheTest {

    private fun event(id: String): Event = Event(
        id = id,
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = "text",
        sig = "c".repeat(128)
    )

    @Test
    fun `given cache at capacity when put exceeds max then oldest entry evicted and callback invoked`() {
        val evicted = mutableListOf<Event>()
        val cache = EventLruCache(maxSize = 3, onEvicted = { evicted.add(it) })
        val (e1, e2, e3, e4) = listOf(event("1"), event("2"), event("3"), event("4"))

        cache.put(e1)
        cache.put(e2)
        cache.put(e3)
        assertEquals(emptyList<Event>(), evicted)

        cache.put(e4)

        assertEquals(listOf(e1), evicted)
        assertNull(cache.get(e1.id))
        assertEquals(e4, cache.get(e4.id))
    }

    @Test
    fun `given a recently read entry when capacity exceeded then least recently used evicted not the recently read one`() {
        val evicted = mutableListOf<Event>()
        val cache = EventLruCache(maxSize = 3, onEvicted = { evicted.add(it) })
        val (e1, e2, e3, e4) = listOf(event("1"), event("2"), event("3"), event("4"))

        cache.put(e1)
        cache.put(e2)
        cache.put(e3)
        // Touch e1 so it's the most-recently-accessed, not e3 -- a plain FIFO cache would still
        // evict e1 next (oldest inserted); LRU must evict e2 instead (least recently accessed).
        cache.get(e1.id)

        cache.put(e4)

        assertEquals(listOf(e2), evicted)
        assertEquals(e1, cache.get(e1.id))
        assertNull(cache.get(e2.id))
    }

    @Test
    fun `given eviction callback when it fires then mutation is visible synchronously with no lock needed`() {
        var callbackRanBeforePutReturned = false
        val cache = EventLruCache(maxSize = 1, onEvicted = { callbackRanBeforePutReturned = true })

        cache.put(event("1"))
        cache.put(event("2"))

        assertTrue(callbackRanBeforePutReturned)
    }

    @Test
    fun `given explicit remove when called then does not invoke eviction callback`() {
        val evicted = mutableListOf<Event>()
        val cache = EventLruCache(maxSize = 3, onEvicted = { evicted.add(it) })
        val e1 = event("1")
        cache.put(e1)

        cache.remove(e1.id)

        assertEquals(emptyList<Event>(), evicted)
        assertNull(cache.get(e1.id))
    }

    @Test
    fun `given several events when snapshot then returns all cached values`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})
        val events = listOf(event("1"), event("2"), event("3"))
        events.forEach(cache::put)

        assertEquals(events.toSet(), cache.snapshot().toSet())
    }

    @Test
    fun `given populated cache when cleared then snapshot is empty`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})
        cache.put(event("1"))

        cache.clear()

        assertTrue(cache.snapshot().isEmpty())
        assertEquals(0, cache.size)
    }

    @Test
    fun `given cache over target when trimTo then evicts least recently accessed down to target`() {
        val evicted = mutableListOf<Event>()
        val cache = EventLruCache(maxSize = 10, onEvicted = { evicted.add(it) })
        val (e1, e2, e3, e4) = listOf(event("1"), event("2"), event("3"), event("4"))
        listOf(e1, e2, e3, e4).forEach(cache::put)
        // Touch e1 so it's most-recently-accessed -- trimTo must still evict by access order,
        // not insertion order.
        cache.get(e1.id)

        cache.trimTo(2)

        assertEquals(2, cache.size)
        assertEquals(listOf(e2, e3), evicted)
        assertEquals(e1, cache.get(e1.id))
        assertEquals(e4, cache.get(e4.id))
    }

    @Test
    fun `given cache already at or under target when trimTo then does nothing`() {
        val evicted = mutableListOf<Event>()
        val cache = EventLruCache(maxSize = 10, onEvicted = { evicted.add(it) })
        cache.put(event("1"))
        cache.put(event("2"))

        cache.trimTo(5)

        assertEquals(2, cache.size)
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun `given trimTo does not change maxSize when cache grows again then original ceiling still applies`() {
        val evicted = mutableListOf<Event>()
        val cache = EventLruCache(maxSize = 3, onEvicted = { evicted.add(it) })
        listOf(event("1"), event("2"), event("3")).forEach(cache::put)

        cache.trimTo(1)
        assertEquals(1, cache.size)
        assertEquals(3, cache.maxSize)

        cache.put(event("4"))
        cache.put(event("5"))
        assertEquals(3, cache.size)
    }

    @Test
    fun `given an event delivered by two relays when recording relays then getRelays returns both`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})
        val e1 = event("1")
        cache.put(e1)

        cache.recordRelay(e1.id, "wss://relay-a.example")
        cache.recordRelay(e1.id, "wss://relay-b.example")

        assertEquals(setOf("wss://relay-a.example", "wss://relay-b.example"), cache.getRelays(e1.id))
    }

    @Test
    fun `given an event never cached when recording a relay for it then it is not tracked`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})

        cache.recordRelay("never-cached", "wss://relay-a.example")

        assertTrue(cache.getRelays("never-cached").isEmpty())
    }

    @Test
    fun `given an evicted event when getRelays queried then previously recorded relays are gone`() {
        val cache = EventLruCache(maxSize = 1, onEvicted = {})
        val e1 = event("1")
        cache.put(e1)
        cache.recordRelay(e1.id, "wss://relay-a.example")

        cache.put(event("2"))

        assertTrue(cache.getRelays(e1.id).isEmpty())
    }

    @Test
    fun `given an explicitly removed event when getRelays queried then previously recorded relays are gone`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})
        val e1 = event("1")
        cache.put(e1)
        cache.recordRelay(e1.id, "wss://relay-a.example")

        cache.remove(e1.id)

        assertTrue(cache.getRelays(e1.id).isEmpty())
    }

    @Test
    fun `given a cleared cache when getRelays queried then previously recorded relays are gone`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})
        val e1 = event("1")
        cache.put(e1)
        cache.recordRelay(e1.id, "wss://relay-a.example")

        cache.clear()

        assertTrue(cache.getRelays(e1.id).isEmpty())
    }

    @Test
    fun `given a fresh cache when stats read then all counters are zero`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})

        val stats = cache.stats

        assertEquals(CacheStats(hits = 0, misses = 0, evictions = 0), stats)
    }

    @Test
    fun `given a resident id when get called then hits increments and misses unchanged`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})
        val e1 = event("1")
        cache.put(e1)

        cache.get(e1.id)

        assertEquals(CacheStats(hits = 1, misses = 0, evictions = 0), cache.stats)
    }

    @Test
    fun `given an absent id when get called then misses increments and hits unchanged`() {
        val cache = EventLruCache(maxSize = 5, onEvicted = {})

        cache.get("never-cached")

        assertEquals(CacheStats(hits = 0, misses = 1, evictions = 0), cache.stats)
    }

    @Test
    fun `given cache at capacity when put exceeds max then evictions increments by number of callback invocations`() {
        val evicted = mutableListOf<Event>()
        val cache = EventLruCache(maxSize = 3, onEvicted = { evicted.add(it) })
        listOf(event("1"), event("2"), event("3")).forEach(cache::put)

        cache.put(event("4"))
        cache.put(event("5"))

        assertEquals(evicted.size.toLong(), cache.stats.evictions)
        assertEquals(CacheStats(hits = 0, misses = 0, evictions = 2), cache.stats)
    }

    @Test
    fun `given cache over target when trimTo then evictions increments once per removed entry`() {
        val evicted = mutableListOf<Event>()
        val cache = EventLruCache(maxSize = 10, onEvicted = { evicted.add(it) })
        listOf(event("1"), event("2"), event("3"), event("4")).forEach(cache::put)

        cache.trimTo(2)

        assertEquals(evicted.size.toLong(), cache.stats.evictions)
        assertEquals(CacheStats(hits = 0, misses = 0, evictions = 2), cache.stats)
    }

    @Test
    fun `given explicit remove when called then evictions unchanged`() {
        val cache = EventLruCache(maxSize = 3, onEvicted = {})
        val e1 = event("1")
        cache.put(e1)

        cache.remove(e1.id)

        assertEquals(CacheStats(hits = 0, misses = 0, evictions = 0), cache.stats)
    }

    @Test
    fun `given hits misses and an eviction when cleared then all counters remain unchanged`() {
        val cache = EventLruCache(maxSize = 1, onEvicted = {})
        val e1 = event("1")
        cache.put(e1)
        cache.get(e1.id) // hit
        cache.get("never-cached") // miss
        cache.put(event("2")) // evicts e1
        val beforeClear = cache.stats

        cache.clear()

        assertEquals(beforeClear, cache.stats)
        assertEquals(CacheStats(hits = 1, misses = 1, evictions = 1), cache.stats)
    }
}
