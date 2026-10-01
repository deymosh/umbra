package com.umbra.app.util.coroutines

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time coverage for [throttleLatest]'s contract as documented on the operator: first
 * value immediate, at most one emission per window afterwards, and the latest value of a
 * burst surviving supersession (CONFLATED hold cell).
 */
class ThrottleLatestTest {

    @Test
    fun `given a single value when throttled then it is emitted immediately with no window wait`() = runTest {
        val emitted = mutableListOf<Int>()
        val job = launch {
            flowOf(1).throttleLatest(300).collect { emitted += it }
        }
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(1), emitted)
    }

    @Test
    fun `given two rapid values when throttled then the first is immediate and the latest survives the window`() = runTest {
        val emitted = mutableListOf<Int>()
        val job = launch {
            flow {
                emit(1)
                emit(2)
            }.throttleLatest(300).collect { emitted += it }
        }
        // Only long enough for the immediate first emission — not for the 300ms window.
        advanceTimeBy(100)
        assertEquals(listOf(1), emitted)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(1, 2), emitted)
    }

    @Test
    fun `given a burst inside one window when the window closes then only the latest is emitted`() = runTest {
        val emitted = mutableListOf<Int>()
        val job = launch {
            flow {
                emit(1)
                // 2..5 all land inside the window opened after 1's immediate emission.
                emit(2)
                emit(3)
                emit(4)
                emit(5)
            }.throttleLatest(300).collect { emitted += it }
        }
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(1, 5), emitted)
    }

    @Test
    fun `given values spaced further apart than the interval when throttled then each is emitted without extra delay`() = runTest {
        val emitted = mutableListOf<Int>()
        val job = launch {
            flow {
                emit(1)
                kotlinx.coroutines.delay(1_000)
                emit(2)
                kotlinx.coroutines.delay(1_000)
                emit(3)
            }.throttleLatest(300).collect { emitted += it }
        }
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(1, 2, 3), emitted)
    }

    @Test
    fun `given a hot continuous stream when throttled then emissions settle to the interval cadence`() = runTest {
        val source = MutableStateFlow(0)
        val emitted = mutableListOf<Int>()
        val scheduler = testScheduler
        val job = launch {
            source.throttleLatest(300).collect { emitted += it }
        }
        advanceUntilIdle() // drain the immediate first emission (0)

        // One value per virtual tick for 1.5s — bursts faster than the interval must still
        // keep flowing at a cadence no slower than the interval per emission.
        for (i in 1..15) {
            source.value = i
            scheduler.advanceTimeBy(100)
        }
        scheduler.advanceUntilIdle()

        // 1.5s of 100ms-ticks across a 300ms interval: at least 4 emissions after the first
        // (0), and all of them are the latest-known value at their window close.
        assertTrue("emitted=${emitted.size}: $emitted", emitted.size >= 5)
        assertEquals("newest snapshot must win: $emitted", 15, emitted.last())
        job.cancel()
    }
}
