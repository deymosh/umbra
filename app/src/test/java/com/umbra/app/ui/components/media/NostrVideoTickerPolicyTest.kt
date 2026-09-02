package com.umbra.app.ui.components.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrVideoTickerPolicyTest {

    @Test
    fun `given player is actively playing when checking ticker then ticker should run`() {
        assertTrue(
            shouldRunVideoProgressTicker(
                isPlaying = true,
                isBuffering = false,
                isUserSeeking = false
            )
        )
    }

    @Test
    fun `given player is idle without seek when checking ticker then ticker should stop`() {
        assertFalse(
            shouldRunVideoProgressTicker(
                isPlaying = false,
                isBuffering = false,
                isUserSeeking = false
            )
        )
    }

    @Test
    fun `given ticker delay policy when evaluating playback state then uses faster interval for playing`() {
        assertEquals(500L, videoProgressTickerDelayMs(isPlaying = true))
        assertEquals(1_000L, videoProgressTickerDelayMs(isPlaying = false))
    }
}
