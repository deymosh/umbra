package com.umbra.app.data.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReconnectPolicyTest {

    @Test
    fun `given no relays connected when evaluating reconnect then reconnects`() {
        val shouldReconnect = SessionReconnectPolicy.shouldReconnect(
            relaysConnected = false,
            relaysChanged = false
        )

        assertTrue(shouldReconnect)
    }

    @Test
    fun `given relays connected and unchanged when evaluating reconnect then skips`() {
        val shouldReconnect = SessionReconnectPolicy.shouldReconnect(
            relaysConnected = true,
            relaysChanged = false
        )

        assertFalse(shouldReconnect)
    }

    @Test
    fun `given relays connected but changed when evaluating reconnect then reconnects`() {
        val shouldReconnect = SessionReconnectPolicy.shouldReconnect(
            relaysConnected = true,
            relaysChanged = true
        )

        assertTrue(shouldReconnect)
    }
}
