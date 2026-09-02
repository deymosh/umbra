package com.umbra.app.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayBackoffPolicyTest {

    @Test
    fun `given attempt numbers when computing orbot wait delay then follows the ladder then caps`() {
        val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
        assertEquals(1_000L, orbotWaitDelayMs(1, delays))
        assertEquals(2_000L, orbotWaitDelayMs(2, delays))
        assertEquals(4_000L, orbotWaitDelayMs(3, delays))
        assertEquals(8_000L, orbotWaitDelayMs(4, delays))
        assertEquals(15_000L, orbotWaitDelayMs(5, delays))
        // Past the ladder's length, stays capped at the last tier.
        assertEquals(15_000L, orbotWaitDelayMs(6, delays))
        assertEquals(15_000L, orbotWaitDelayMs(100, delays))
    }

    @Test
    fun `given attempt number below one when computing orbot wait delay then clamped to first tier`() {
        val delays = longArrayOf(1_000, 2_000)
        assertEquals(1_000L, orbotWaitDelayMs(0, delays))
        assertEquals(1_000L, orbotWaitDelayMs(-5, delays))
    }

    @Test
    fun `given message naming the proxy host when checking local proxy refusal then true`() {
        assertTrue(isLocalProxyRefusal("Failed to connect to /127.0.0.1:9050", "127.0.0.1"))
        assertTrue(isLocalProxyRefusal("Connection refused: 127.0.0.1", "127.0.0.1"))
    }

    @Test
    fun `given message naming an unrelated host when checking local proxy refusal then false`() {
        assertFalse(isLocalProxyRefusal("Failed to connect to relay.example.com", "127.0.0.1"))
        assertFalse(isLocalProxyRefusal(null, "127.0.0.1"))
        assertFalse(isLocalProxyRefusal("some error", ""))
    }
}
