package com.umbra.app.ui.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadOlderFeedDecisionTest {

    private val sameAnchorCooldownMs = 8_000L
    private val differentAnchorCooldownMs = 1_200L
    // Large enough that none of the cooldown-focused tests below ever cross the exhaustion floor
    // by accident — exhaustion has its own dedicated tests further down.
    private val maxLookbackSecs = 1_000_000_000L

    @Test
    fun `given first call ever when deciding then fetches immediately`() {
        val decision = decideLoadOlderFeed(
            oldest = 1000L,
            lastAnchor = null,
            lastLoadMoreAtMs = 0L,
            nowMs = 100_000L,
            sameAnchorCooldownMs = sameAnchorCooldownMs,
            differentAnchorCooldownMs = differentAnchorCooldownMs,
            maxLookbackSecs = maxLookbackSecs
        )

        assertTrue(decision.shouldFetch)
        assertFalse(decision.isExhausted)
    }

    @Test
    fun `given same anchor twice in a row past its cooldown when deciding then still fetches both times`() {
        // Regression for the permanent pagination freeze: a relay returning nothing new (anchor
        // doesn't move) must never permanently stop future fetches — only throttle until the
        // (longer) same-anchor cooldown elapses.
        val first = decideLoadOlderFeed(
            oldest = 1000L,
            lastAnchor = null,
            lastLoadMoreAtMs = 0L,
            nowMs = 100_000L,
            sameAnchorCooldownMs = sameAnchorCooldownMs,
            differentAnchorCooldownMs = differentAnchorCooldownMs,
            maxLookbackSecs = maxLookbackSecs
        )
        assertTrue(first.shouldFetch)

        val second = decideLoadOlderFeed(
            oldest = 1000L, // same anchor as the first call
            lastAnchor = 1000L,
            lastLoadMoreAtMs = 100_000L,
            nowMs = 100_000L + sameAnchorCooldownMs + 1L, // past the same-anchor cooldown
            sameAnchorCooldownMs = sameAnchorCooldownMs,
            differentAnchorCooldownMs = differentAnchorCooldownMs,
            maxLookbackSecs = maxLookbackSecs
        )
        assertTrue(second.shouldFetch)
    }

    @Test
    fun `given same anchor within its cooldown when deciding then does not fetch yet`() {
        val decision = decideLoadOlderFeed(
            oldest = 1000L,
            lastAnchor = 1000L,
            lastLoadMoreAtMs = 100_000L,
            nowMs = 100_000L + sameAnchorCooldownMs - 1L, // just short of the same-anchor cooldown
            sameAnchorCooldownMs = sameAnchorCooldownMs,
            differentAnchorCooldownMs = differentAnchorCooldownMs,
            maxLookbackSecs = maxLookbackSecs
        )

        assertFalse(decision.shouldFetch)
    }

    @Test
    fun `given a different anchor within the shorter cooldown when deciding then does not fetch yet`() {
        val decision = decideLoadOlderFeed(
            oldest = 2000L,
            lastAnchor = 1000L,
            lastLoadMoreAtMs = 100_000L,
            nowMs = 100_000L + differentAnchorCooldownMs - 1L,
            sameAnchorCooldownMs = sameAnchorCooldownMs,
            differentAnchorCooldownMs = differentAnchorCooldownMs,
            maxLookbackSecs = maxLookbackSecs
        )

        assertFalse(decision.shouldFetch)
    }

    @Test
    fun `given a different anchor past the shorter cooldown when deciding then fetches`() {
        val decision = decideLoadOlderFeed(
            oldest = 2000L,
            lastAnchor = 1000L,
            lastLoadMoreAtMs = 100_000L,
            nowMs = 100_000L + differentAnchorCooldownMs + 1L,
            sameAnchorCooldownMs = sameAnchorCooldownMs,
            differentAnchorCooldownMs = differentAnchorCooldownMs,
            maxLookbackSecs = maxLookbackSecs
        )

        assertTrue(decision.shouldFetch)
    }

    @Test
    fun `given oldest already past the lookback floor when deciding then reports exhausted and does not fetch`() {
        val nowMs = 100_000_000L // nowSecs = 100_000L
        val floorSecs = 1_000L
        val decision = decideLoadOlderFeed(
            oldest = 50_000L, // <= nowSecs(100_000) - floorSecs(1_000) = 99_000
            lastAnchor = null,
            lastLoadMoreAtMs = 0L,
            nowMs = nowMs,
            sameAnchorCooldownMs = sameAnchorCooldownMs,
            differentAnchorCooldownMs = differentAnchorCooldownMs,
            maxLookbackSecs = floorSecs
        )

        assertTrue(decision.isExhausted)
        assertFalse(decision.shouldFetch)
    }

    @Test
    fun `given oldest still within the lookback floor when deciding then not exhausted`() {
        val nowMs = 100_000_000L // nowSecs = 100_000L
        val floorSecs = 1_000L
        val decision = decideLoadOlderFeed(
            oldest = 99_500L, // > nowSecs(100_000) - floorSecs(1_000) = 99_000
            lastAnchor = null,
            lastLoadMoreAtMs = 0L,
            nowMs = nowMs,
            sameAnchorCooldownMs = sameAnchorCooldownMs,
            differentAnchorCooldownMs = differentAnchorCooldownMs,
            maxLookbackSecs = floorSecs
        )

        assertFalse(decision.isExhausted)
        assertTrue(decision.shouldFetch)
    }
}
