package com.umbra.app.data.nostr

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BoundedEventCacheTest {

    private fun event(id: String) = Event(id = id, pubkey = "pk", createdAt = 0L, kind = 1)

    @Test
    fun `given same id computed twice when getOrPut then compute only runs once and same instance is reused`() {
        val cache = BoundedEventCache(maxSize = 10)
        var computeCount = 0
        val first = cache.getOrPut("abc") { computeCount++; event("abc") }
        val second = cache.getOrPut("abc") { computeCount++; event("abc") }

        assertEquals(1, computeCount)
        assertSame(first, second)
    }

    @Test
    fun `given different ids when getOrPut then each computes independently`() {
        val cache = BoundedEventCache(maxSize = 10)
        val a = cache.getOrPut("a") { event("a") }
        val b = cache.getOrPut("b") { event("b") }

        assertEquals("a", a.id)
        assertEquals("b", b.id)
        assertEquals(2, cache.size())
    }

    @Test
    fun `given more entries than maxSize when getOrPut then oldest entries are evicted`() {
        val cache = BoundedEventCache(maxSize = 3)
        cache.getOrPut("1") { event("1") }
        cache.getOrPut("2") { event("2") }
        cache.getOrPut("3") { event("3") }
        cache.getOrPut("4") { event("4") }

        assertEquals(3, cache.size())
        // "1" was the least-recently-used and should have been evicted, so re-fetching it
        // computes a fresh instance rather than reusing anything.
        var recomputed = false
        cache.getOrPut("1") { recomputed = true; event("1") }
        assertEquals(true, recomputed)
    }

    @Test
    fun `given an id already cached when get then returns it without computing`() {
        val cache = BoundedEventCache(maxSize = 10)
        cache.getOrPut("abc") { event("abc") }

        assertEquals("abc", cache.get("abc")?.id)
    }

    @Test
    fun `given an id never cached when get then returns null`() {
        val cache = BoundedEventCache(maxSize = 10)

        assertNull(cache.get("missing"))
    }
}
