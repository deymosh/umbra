package com.umbra.app.data.nostr

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the two scheduling seams every [AtomicReference]-backed job field in
 * this package (and [NostrSessionManager]'s converted fields) is built on: skip-if-already-active
 * (`launchIfIdle`) and cancel-and-replace (`launchReplacing`). The deterministic group below pins
 * single-threaded ordering behavior; the concurrency group races the two helpers against real
 * threads on `Dispatchers.Default`, because a single-threaded virtual-time dispatcher makes a
 * non-atomic read-then-assign look atomic and would prove nothing about the guarantee this file
 * exists to provide.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AtomicJobSchedulingTest {

    // ---- Deterministic group ----

    @Test
    fun `given idle slot when scheduled then block runs exactly once`() = runTest {
        val slot = AtomicReference<Job?>(null)
        var runCount = 0

        val scheduled = slot.launchIfIdle(this) { runCount++ }
        advanceUntilIdle()

        assertTrue(scheduled)
        assertEquals(1, runCount)
    }

    @Test
    fun `given a still-active slot when scheduled again then the second call is skipped`() = runTest {
        val slot = AtomicReference<Job?>(null)
        var runCount = 0
        val gate = CompletableDeferred<Unit>()

        val first = slot.launchIfIdle(this) {
            runCount++
            gate.await()
        }
        val second = slot.launchIfIdle(this) { runCount++ }

        assertTrue(first)
        assertFalse(second)

        advanceUntilIdle()
        assertEquals(1, runCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, runCount)
    }

    @Test
    fun `given the previous job has finished when scheduled again then a new block runs`() = runTest {
        val slot = AtomicReference<Job?>(null)
        var runCount = 0
        val gate = CompletableDeferred<Unit>()

        slot.launchIfIdle(this) {
            runCount++
            gate.await()
        }
        gate.complete(Unit)
        advanceUntilIdle()

        val third = slot.launchIfIdle(this) { runCount++ }
        advanceUntilIdle()

        assertTrue(third)
        assertEquals(2, runCount)
    }

    @Test
    fun `given an active slot when replacing scheduled then the new block runs and the old job is cancelled`() = runTest {
        val slot = AtomicReference<Job?>(null)
        var firstRan = false
        var secondRan = false
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()

        slot.launchReplacing(this) {
            firstRan = true
            firstGate.await()
        }
        advanceUntilIdle()
        val displaced = slot.get()

        slot.launchReplacing(this) {
            secondRan = true
            secondGate.await()
        }
        advanceUntilIdle()

        assertTrue(firstRan)
        assertTrue(secondRan)
        assertTrue(displaced?.isCancelled == true)
        assertTrue(slot.get()?.isActive == true)

        secondGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `given an empty slot when the take-and-cancel idiom runs then nothing throws and it stays empty`() {
        val slot = AtomicReference<Job?>(null)

        slot.getAndSet(null)?.cancel()

        assertNull(slot.get())
    }

    // ---- Concurrency group: real threads, not the virtual-time test scheduler (see class doc) ----

    private companion object {
        const val CONCURRENCY_ITERATIONS = 200
    }

    @Test
    fun `given eight genuinely parallel schedulers on real threads when racing launchIfIdle then exactly one executes`() =
        runBlocking {
            val targetScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                repeat(CONCURRENCY_ITERATIONS) {
                    val slot = AtomicReference<Job?>(null)
                    val startSignal = CompletableDeferred<Unit>()
                    val bodyStarted = CompletableDeferred<Unit>()
                    val bodyGate = CompletableDeferred<Unit>()
                    val successCount = AtomicInteger(0)
                    val executionCount = AtomicInteger(0)

                    val racers = (1..8).map {
                        targetScope.async {
                            startSignal.await()
                            val won = slot.launchIfIdle(targetScope) {
                                executionCount.incrementAndGet()
                                bodyStarted.complete(Unit)
                                bodyGate.await()
                            }
                            if (won) successCount.incrementAndGet()
                        }
                    }
                    startSignal.complete(Unit)
                    racers.awaitAll()
                    bodyStarted.await()

                    assertEquals(1, successCount.get())
                    assertEquals(1, executionCount.get())
                    assertTrue(slot.get()?.isActive == true)

                    bodyGate.complete(Unit)
                    slot.get()?.join()
                }
            } finally {
                targetScope.cancel()
            }
        }

    @Test
    fun `given eight genuinely parallel replacing schedulers on real threads when racing then exactly one survives`() =
        runBlocking {
            val targetScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                repeat(CONCURRENCY_ITERATIONS) {
                    val slot = AtomicReference<Job?>(null)
                    val startSignal = CompletableDeferred<Unit>()
                    val bodyGate = CompletableDeferred<Unit>()
                    // A cancelled-before-its-first-resume candidate can finish and be dropped from
                    // targetScope's children before a post-hoc snapshot would ever see it -- eight
                    // real threads contending for a limited Dispatchers.Default pool routinely
                    // finish that fast. captureOrder instead serializes only the "call
                    // launchReplacing, then immediately read the resulting slot value" pair per
                    // racer, so each racer's own candidate is captured the instant it's known,
                    // before the next racer's call can displace it -- the eight callers still race
                    // for this lock in genuinely unpredictable order on real threads, and every
                    // candidate's own body still runs (or gets cancelled) fully independently and
                    // concurrently in the background.
                    val captureOrder = Mutex()
                    val created = mutableListOf<Job>()

                    val racers = (1..8).map {
                        targetScope.async {
                            startSignal.await()
                            captureOrder.withLock {
                                slot.launchReplacing(targetScope) { bodyGate.await() }
                                created += requireNotNull(slot.get())
                            }
                        }
                    }
                    startSignal.complete(Unit)
                    racers.awaitAll()

                    // The last entry captured (in mutex-acquisition order) is, by construction,
                    // the last launchReplacing call this iteration made -- exactly the job left
                    // active in the slot, since nothing calls launchReplacing again until the next
                    // iteration.
                    val survivor = created.last()
                    val displaced = created.dropLast(1)

                    assertEquals(8, created.size)
                    assertTrue(survivor.isActive)
                    assertEquals(7, displaced.size)
                    assertTrue(displaced.all { it.isCancelled })

                    bodyGate.complete(Unit)
                    survivor.join()
                }
            } finally {
                targetScope.cancel()
            }
        }
}
