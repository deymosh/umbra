package com.umbra.app.data.repository

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eviction/insertion-order/dedup tests for the bounded dedup set behind
 * [EventRepositoryImpl]'s seenEventIds — the set's own contract, independent of the repository
 * (no Robolectric, plain JVM).
 */
class BoundedInsertionOrderedSetTest {

    @Test
    fun `given adds past capacity when evicting then the eldest is dropped and the newest survives`() {
        val set = BoundedInsertionOrderedSet<String>(3)

        set.add("a")
        set.add("b")
        set.add("c")
        set.add("d")

        assertEquals(3, set.size)
        // Eldest ("a") evicted to make room for "d"; everything else survives.
        assertFalse(set.contains("a"))
        assertTrue(set.contains("b"))
        assertTrue(set.contains("c"))
        assertTrue(set.contains("d"))
    }

    @Test
    fun `given a re-add of an already-tracked element when added again then it is a dup and order is untouched`() {
        val set = BoundedInsertionOrderedSet<String>(3)
        set.add("a")
        set.add("b")

        assertFalse(set.add("a"))
        assertEquals(2, set.size)
        // Iteration still insertion-ordered: a's re-add did not refresh it (FIFO, not LRU).
        assertEquals(listOf("a", "b"), set.toList())
    }

    @Test
    fun `given a re-add at capacity when added again then it does not evict anything`() {
        val set = BoundedInsertionOrderedSet<String>(3)
        set.add("a")
        set.add("b")
        set.add("c")

        assertFalse(set.add("b"))

        assertEquals(listOf("a", "b", "c"), set.toList())
    }

    @Test
    fun `given concurrent adders when the set is driven past capacity then evictions are staggered not wholesale`() {
        val set = BoundedInsertionOrderedSet<Int>(100)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val totalIds = 2000

        val futures = (0 until 7).map { worker ->
            pool.submit {
                start.await()
                repeat(totalIds / 7) { i ->
                    set.add(worker * totalIds / 7 + i)
                }
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()

        assertEquals(100, set.size)
        // Wholesale drain semantics (the old clear-at-max) would have emptied other workers'
        // additions; staggered eviction keeps exactly capacity distinct elements.
        val survivors = set.toHashSet()
        assertEquals(100, survivors.size)
        // The FIFO property that matters under concurrency: a re-add of an already-tracked
        // element is a no-op — it evicts nothing, and the eldest survivor survives.
        val eldest = set.first()
        set.add(eldest)
        assertEquals(100, set.size)
        assertTrue(set.contains(eldest))
        assertTrue(set.last() in survivors)
    }
}
