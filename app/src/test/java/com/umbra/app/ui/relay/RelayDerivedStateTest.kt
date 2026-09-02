package com.umbra.app.ui.relay

import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.RelayRequestInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayDerivedStateTest {

    @Test
    fun `given a non-discovered relay with a mix of role flags when computing derived state then buckets populate correctly`() {
        val relay = Relay(
            id = "relay_1",
            url = "wss://relay.example.com",
            isEnabled = true,
            isReadEnabled = true,
            isWriteEnabled = true,
            isDmEnabled = false,
            isSearchEnabled = true,
            isIndexEnabled = false
        )

        val derived = computeRelayDerivedState(
            RelayDerivedStateInputs(
                relays = listOf(relay),
                connectedRelayUrls = emptySet(),
                relayIssues = emptyList(),
                relayRequests = emptyList()
            )
        )

        assertEquals(listOf(relay), derived.buckets.outbox)
        assertEquals(listOf(relay), derived.buckets.inbox)
        assertEquals(emptyList<Relay>(), derived.buckets.dm)
        assertEquals(listOf(relay), derived.buckets.search)
        assertEquals(emptyList<Relay>(), derived.buckets.index)
        assertEquals(1, derived.buckets.active)
    }

    @Test
    fun `given discovered relays in different connection states when computing derived state then they split by enabled and connected`() {
        val connectedDiscovered = Relay(
            id = "relay_connected",
            url = "wss://connected.example.com",
            isEnabled = true,
            isDiscovered = true
        )
        val otherDiscovered = Relay(
            id = "relay_other",
            url = "wss://other.example.com",
            isEnabled = true,
            isDiscovered = true
        )
        val disabledDiscovered = Relay(
            id = "relay_disabled",
            url = "wss://disabled.example.com",
            isEnabled = false,
            isDiscovered = true
        )

        val derived = computeRelayDerivedState(
            RelayDerivedStateInputs(
                relays = listOf(connectedDiscovered, otherDiscovered, disabledDiscovered),
                connectedRelayUrls = setOf("wss://connected.example.com"),
                relayIssues = emptyList(),
                relayRequests = emptyList()
            )
        )

        assertEquals(listOf(connectedDiscovered), derived.buckets.discoveredConnected)
        assertEquals(listOf(otherDiscovered), derived.buckets.discoveredOther)
        assertEquals(listOf(disabledDiscovered), derived.buckets.discoveredDisabled)
    }

    @Test
    fun `given issues including a CONNECTED kind when computing telemetry then nonConnectedIssues excludes it`() {
        val relay = Relay(id = "relay_1", url = "wss://relay.example.com")
        val issues = listOf(
            RelayIssue(relayUrl = relay.url, kind = RelayIssueKind.CONNECTED, rawMessage = "connected"),
            RelayIssue(relayUrl = relay.url, kind = RelayIssueKind.NETWORK, rawMessage = "network error"),
            RelayIssue(relayUrl = relay.url, kind = RelayIssueKind.NOTICE, rawMessage = "notice")
        )

        val derived = computeRelayDerivedState(
            RelayDerivedStateInputs(
                relays = listOf(relay),
                connectedRelayUrls = emptySet(),
                relayIssues = issues,
                relayRequests = emptyList()
            )
        )

        assertEquals(2, derived.telemetry.nonConnectedIssues)
    }

    @Test
    fun `given relay inputs when computing connection states then they match resolveRelayConnectionIndicatorState independently`() {
        val connected = Relay(id = "relay_connected", url = "wss://connected.example.com", isEnabled = true)
        val failed = Relay(id = "relay_failed", url = "wss://failed.example.com", isEnabled = true)
        val disabled = Relay(id = "relay_disabled", url = "wss://disabled.example.com", isEnabled = false)
        val relays = listOf(connected, failed, disabled)
        val connectedRelayUrls = setOf("wss://connected.example.com")
        val relayIssues = listOf(
            RelayIssue(relayUrl = failed.url, kind = RelayIssueKind.NETWORK, rawMessage = "network error")
        )

        val derived = computeRelayDerivedState(
            RelayDerivedStateInputs(
                relays = relays,
                connectedRelayUrls = connectedRelayUrls,
                relayIssues = relayIssues,
                relayRequests = emptyList()
            )
        )

        relays.forEach { relay ->
            val expected = resolveRelayConnectionIndicatorState(
                relayUrl = relay.url,
                isEnabled = relay.isEnabled,
                connectedRelayUrls = connectedRelayUrls,
                relayIssues = relayIssues
            )
            assertEquals(expected, derived.connectionStates[relay.url])
        }
    }

    @Test
    fun `given relay requests when computing telemetry then liveSubscriptions and totalReceivedEvents are summed`() {
        val relay = Relay(id = "relay_1", url = "wss://relay.example.com")
        val requests = listOf(
            RelayRequestInfo(relayUrl = relay.url, subscriptionId = "sub1", receivedEventCount = 3),
            RelayRequestInfo(relayUrl = relay.url, subscriptionId = "sub2", receivedEventCount = 7)
        )

        val derived = computeRelayDerivedState(
            RelayDerivedStateInputs(
                relays = listOf(relay),
                connectedRelayUrls = emptySet(),
                relayIssues = emptyList(),
                relayRequests = requests
            )
        )

        assertEquals(2, derived.telemetry.liveSubscriptions)
        assertEquals(10, derived.telemetry.totalReceivedEvents)
    }
}
