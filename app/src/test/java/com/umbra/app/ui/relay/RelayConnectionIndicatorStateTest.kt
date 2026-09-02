package com.umbra.app.ui.relay

import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayConnectionIndicatorStateTest {

    @Test
    fun `given relay url is currently connected when resolving indicator then returns connected`() {
        val state = resolveRelayConnectionIndicatorState(
            relayUrl = "wss://relay.example.com/",
            isEnabled = true,
            connectedRelayUrls = setOf("wss://relay.example.com"),
            relayIssues = emptyList()
        )

        assertEquals(RelayConnectionIndicatorState.CONNECTED, state)
    }

    @Test
    fun `given latest issue is connection failure when resolving indicator then returns failed`() {
        val issues = listOf(
            RelayIssue(
                relayUrl = "wss://relay.example.com",
                kind = RelayIssueKind.CONNECTED,
                rawMessage = "connected"
            ),
            RelayIssue(
                relayUrl = "wss://relay.example.com",
                kind = RelayIssueKind.NETWORK,
                rawMessage = "network error"
            )
        )

        val state = resolveRelayConnectionIndicatorState(
            relayUrl = "wss://relay.example.com",
            isEnabled = true,
            connectedRelayUrls = emptySet(),
            relayIssues = issues
        )

        assertEquals(RelayConnectionIndicatorState.FAILED, state)
    }

    @Test
    fun `given relay is not connected and no failure issue when resolving indicator then returns connecting`() {
        val issues = listOf(
            RelayIssue(
                relayUrl = "wss://relay.example.com",
                kind = RelayIssueKind.NOTICE,
                rawMessage = "notice"
            )
        )

        val state = resolveRelayConnectionIndicatorState(
            relayUrl = "wss://relay.example.com",
            isEnabled = true,
            connectedRelayUrls = emptySet(),
            relayIssues = issues
        )

        assertEquals(RelayConnectionIndicatorState.CONNECTING, state)
    }

    @Test
    fun `given relay is disabled but still momentarily connected when resolving indicator then returns disabled`() {
        val state = resolveRelayConnectionIndicatorState(
            relayUrl = "wss://relay.example.com",
            isEnabled = false,
            connectedRelayUrls = setOf("wss://relay.example.com"),
            relayIssues = emptyList()
        )

        assertEquals(RelayConnectionIndicatorState.DISABLED, state)
    }

    @Test
    fun `given relay is disabled with a stale connected issue when resolving indicator then returns disabled not connecting`() {
        val issues = listOf(
            RelayIssue(
                relayUrl = "wss://relay.example.com",
                kind = RelayIssueKind.CONNECTED,
                rawMessage = "connected"
            )
        )

        val state = resolveRelayConnectionIndicatorState(
            relayUrl = "wss://relay.example.com",
            isEnabled = false,
            connectedRelayUrls = emptySet(),
            relayIssues = issues
        )

        assertEquals(RelayConnectionIndicatorState.DISABLED, state)
    }
}
