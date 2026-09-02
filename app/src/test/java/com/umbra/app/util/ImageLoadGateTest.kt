package com.umbra.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLoadGateTest {

    @Test
    fun `given permits available when acquiring then completes immediately`() = runTest {
        val gate = ImageLoadGate()

        gate.acquire()

        // No assertion needed beyond "didn't suspend forever" — runTest would hang otherwise.
        gate.release()
    }

    @Test
    fun `given all permits held when a new acquire is requested then it suspends until release`() = runTest {
        val gate = ImageLoadGate()
        repeat(6) { gate.acquire() }

        var acquired = false
        val waiter = async { gate.acquire(); acquired = true }
        yield()

        assertFalse(acquired)

        gate.release()
        waiter.await()

        assertTrue(acquired)
    }

    @Test
    fun `given a released permit when acquiring again then it is reusable`() = runTest {
        val gate = ImageLoadGate()
        repeat(6) { gate.acquire() }
        repeat(6) { gate.release() }

        gate.acquire()
        gate.release()
    }

    // Regression coverage for the rememberRetryingAsyncImagePainter fix (NostrImageComponents.kt):
    // the whole acquire/await/release lifecycle now lives in one coroutine's try/finally, which
    // depends on two guarantees from ImageLoadGate/kotlinx.coroutines.sync.Semaphore that the two
    // tests below pin down explicitly.

    @Test
    fun `given all permits held when a suspended acquirer is cancelled then no permit is leaked`() = runTest {
        val gate = ImageLoadGate()
        repeat(6) { gate.acquire() }

        val waiter = launch { gate.acquire() }
        yield()
        waiter.cancel()
        waiter.join()

        // If the cancelled acquire() had still consumed a permit, only 5 (not 6) releases would be
        // valid here and a 6th acquire below would suspend forever.
        repeat(6) { gate.release() }

        var acquiredCount = 0
        repeat(6) {
            launch {
                gate.acquire()
                acquiredCount++
            }
        }
        yield()
        assertEquals(6, acquiredCount)
    }

    @Test
    fun `given an acquire inside try-finally when cancelled mid-suspend then the permit is still released exactly once`() = runTest {
        val gate = ImageLoadGate()
        val enteredTry = CompletableDeferred<Unit>()

        val holder = launch {
            gate.acquire()
            try {
                enteredTry.complete(Unit)
                CompletableDeferred<Unit>().await() // suspend forever until cancelled
            } finally {
                gate.release()
            }
        }
        enteredTry.await()
        holder.cancel()
        holder.join()

        // The permit acquired above must be back in the pool exactly once — acquiring all 6 must
        // succeed without suspending, and a 7th must still block.
        repeat(6) { gate.acquire() }
        var seventhAcquired = false
        val seventh = launch { gate.acquire(); seventhAcquired = true }
        yield()
        assertFalse(seventhAcquired)
        seventh.cancel()
    }

    // GateStats coverage — mirrors EventLruCacheTest's fresh-cache-stats shape.

    @Test
    fun `given a fresh gate when stats read then all counters are zero`() = runTest {
        val gate = ImageLoadGate()

        val stats = gate.stats

        assertEquals(GateStats(totalAcquires = 0, totalWaitTimeMs = 0, maxWaitTimeMs = 0, currentInFlight = 0), stats)
    }

    @Test
    fun `given an acquire and release when stats read then counters reflect the operation`() = runTest {
        val gate = ImageLoadGate()

        val lease = gate.acquire()
        val afterAcquire = gate.stats
        assertEquals(1L, afterAcquire.totalAcquires)
        assertEquals(1, afterAcquire.currentInFlight)

        gate.release(lease)
        val afterRelease = gate.stats
        assertEquals(1L, afterRelease.totalAcquires)
        assertEquals(0, afterRelease.currentInFlight)
    }

    // Reserved-pool exclusivity/fallthrough/cancellation coverage — a plain
    // MediaLoadPriorityGate() plus beginInteractiveLoad() is enough to flip isInteractiveLoadActive
    // true, matching this project's plain-JUnit4 testing convention (no mocking library).

    @Test
    fun `given isInteractiveLoadActive true and both reserved permits free when 2 interactive acquires happen then both draw reserved leases and the normal pool stays fully available`() = runTest {
        val priorityGate = MediaLoadPriorityGate()
        priorityGate.beginInteractiveLoad()
        val gate = ImageLoadGate(priorityGate)

        val lease1 = gate.acquire()
        val lease2 = gate.acquire()

        assertTrue(lease1.fromReservedPool)
        assertTrue(lease2.fromReservedPool)

        // The normal pool's 6 permits were never touched by the two reserved acquires above, so
        // all 6 must still acquire immediately without suspending.
        var acquiredCount = 0
        repeat(6) {
            launch { gate.acquire(); acquiredCount++ }
        }
        yield()
        assertEquals(6, acquiredCount)
    }

    @Test
    fun `given isInteractiveLoadActive true and both reserved permits already held when a 3rd interactive acquire happens then it falls through to the normal pool`() = runTest {
        val priorityGate = MediaLoadPriorityGate()
        priorityGate.beginInteractiveLoad()
        val gate = ImageLoadGate(priorityGate)

        gate.acquire()
        gate.acquire()

        val thirdLease = gate.acquire()

        assertFalse(thirdLease.fromReservedPool)
    }

    @Test
    fun `given an interactive acquire cancelled mid-suspend on the normal-pool fallthrough then no permit is leaked from either pool`() = runTest {
        val priorityGate = MediaLoadPriorityGate()
        priorityGate.beginInteractiveLoad()
        val gate = ImageLoadGate(priorityGate)

        // Exhaust both pools: the 2 reserved permits, then all 6 normal permits.
        val reservedLease1 = gate.acquire()
        val reservedLease2 = gate.acquire()
        val normalLeases = (1..6).map { gate.acquire() }
        assertEquals(8, gate.stats.currentInFlight)

        val waiter = launch { gate.acquire() }
        yield()
        waiter.cancel()
        waiter.join()

        // The cancelled acquire() suspended on the normal pool (both reserved permits were
        // already held) and never obtained a permit — currentInFlight/totalAcquires must not
        // reflect it.
        assertEquals(8, gate.stats.currentInFlight)
        assertEquals(8L, gate.stats.totalAcquires)

        gate.release(reservedLease1)
        gate.release(reservedLease2)
        normalLeases.forEach { gate.release(it) }
        assertEquals(0, gate.stats.currentInFlight)

        // No permit was leaked by the cancellation: the full 8 (2 reserved + 6 normal) can be
        // reacquired without suspending.
        val reacquired = mutableListOf<AcquireLease>()
        repeat(2) { reacquired.add(gate.acquire()) }
        repeat(6) { reacquired.add(gate.acquire()) }
        assertEquals(8, reacquired.size)
    }
}
