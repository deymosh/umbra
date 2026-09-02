package com.umbra.app.domain.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorCircuitHealthTrackerTest {

    @Test
    fun `given fewer failures than threshold when recording then never triggers`() {
        val tracker = TorCircuitHealthTracker(failureStreakThreshold = 8, minSpanMs = 30_000L)
        var now = 0L
        repeat(7) {
            now += 1_000L
            assertFalse(tracker.recordFailure(now))
        }
    }

    @Test
    fun `given enough failures but too short a span when recording then does not trigger`() {
        val tracker = TorCircuitHealthTracker(failureStreakThreshold = 8, minSpanMs = 30_000L)
        var now = 0L
        // 8 failures within 7ms total span — count threshold met, span threshold not.
        repeat(8) {
            assertFalse(tracker.recordFailure(now))
            now += 1L
        }
    }

    @Test
    fun `given enough failures spanning enough time when recording then triggers once`() {
        val tracker = TorCircuitHealthTracker(failureStreakThreshold = 8, minSpanMs = 30_000L)
        var now = 0L
        var triggered = false
        repeat(10) {
            now += 5_000L
            if (tracker.recordFailure(now)) {
                triggered = true
            }
        }
        assertTrue(triggered)
    }

    @Test
    fun `given already signaled when recording more failures then does not trigger again`() {
        val tracker = TorCircuitHealthTracker(failureStreakThreshold = 3, minSpanMs = 1_000L)
        var now = 0L
        repeat(3) { now += 1_000L; tracker.recordFailure(now) }
        // Third call above should have triggered; further failures must not re-trigger.
        now += 1_000L
        assertFalse(tracker.recordFailure(now))
    }

    @Test
    fun `given a success when recording then streak resets and can trigger again later`() {
        val tracker = TorCircuitHealthTracker(failureStreakThreshold = 3, minSpanMs = 1_000L)
        var now = 0L
        repeat(3) { now += 1_000L; tracker.recordFailure(now) }

        tracker.recordSuccess()

        var triggeredAgain = false
        repeat(3) {
            now += 1_000L
            if (tracker.recordFailure(now)) triggeredAgain = true
        }
        assertTrue(triggeredAgain)
    }

    @Test
    fun `given a success after a signaled streak when recording then returns true`() {
        val tracker = TorCircuitHealthTracker(failureStreakThreshold = 3, minSpanMs = 1_000L)
        var now = 0L
        repeat(3) { now += 1_000L; tracker.recordFailure(now) }

        assertTrue(tracker.recordSuccess())
    }

    @Test
    fun `given a success with no prior signaled streak when recording then returns false`() {
        val tracker = TorCircuitHealthTracker(failureStreakThreshold = 8, minSpanMs = 30_000L)
        var now = 0L
        // Fewer than the threshold — never actually signals.
        repeat(2) { now += 1_000L; tracker.recordFailure(now) }

        assertFalse(tracker.recordSuccess())
    }

    @Test
    fun `given no failures at all when recording a success then returns false`() {
        val tracker = TorCircuitHealthTracker()
        assertFalse(tracker.recordSuccess())
    }
}
