package com.umbra.app.domain.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRequestLedgerTest {

    private fun request(relayUrl: String, subId: String, updatedAtMillis: Long) = RelayRequestInfo(
        relayUrl = relayUrl,
        subscriptionId = subId,
        updatedAtMillis = updatedAtMillis
    )

    @Test
    fun `given a new relay plus subscription id when upserting then it is simply added`() {
        val current = emptyList<RelayRequestInfo>()
        val result = upsertBoundedRelayRequest(current, request("wss://a.relay", "sub1", 100), maxPerRelay = 10)

        assertEquals(1, result.size)
        assertEquals("sub1", result.single().subscriptionId)
    }

    @Test
    fun `given the same relay and subscription id when upserting then the existing entry is replaced not duplicated`() {
        val current = listOf(request("wss://a.relay", "sub1", 100))
        val result = upsertBoundedRelayRequest(current, request("wss://a.relay", "sub1", 200), maxPerRelay = 10)

        assertEquals(1, result.size)
        assertEquals(200L, result.single().updatedAtMillis)
    }

    @Test
    fun `given one relay at its cap when upserting a new subscription then only that relay's oldest entry is evicted`() {
        val current = listOf(
            request("wss://a.relay", "sub1", 100),
            request("wss://b.relay", "sub1", 150),
            request("wss://a.relay", "sub2", 200)
        )
        val result = upsertBoundedRelayRequest(current, request("wss://a.relay", "sub3", 300), maxPerRelay = 2)

        // a.relay was at its cap (sub1@100, sub2@200) — sub1 (oldest) evicted; b.relay untouched.
        assertEquals(3, result.size)
        assertTrue(result.none { it.relayUrl == "wss://a.relay" && it.subscriptionId == "sub1" })
        assertTrue(result.any { it.relayUrl == "wss://b.relay" && it.subscriptionId == "sub1" })
        assertTrue(result.any { it.relayUrl == "wss://a.relay" && it.subscriptionId == "sub3" })
    }

    @Test
    fun `given many relays each near their own cap when upserting then no relay evicts another's subscriptions`() {
        var ledger = emptyList<RelayRequestInfo>()
        val relays = (1..40).map { "wss://relay$it.example" }
        relays.forEach { url ->
            repeat(10) { n -> ledger = upsertBoundedRelayRequest(ledger, request(url, "chan$n", n.toLong()), maxPerRelay = 10) }
        }

        relays.forEach { url ->
            assertEquals(10, ledger.count { it.relayUrl == url })
        }
        assertEquals(400, ledger.size)
    }
}
