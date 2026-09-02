package com.umbra.app.domain.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayUrlNormalizerTest {

    @Test
    fun `given loopback ipv4 host when checking local network then it is flagged`() {
        assertTrue(isLocalNetworkRelayUrl("ws://127.0.0.1:7777"))
        assertTrue(isLocalNetworkRelayUrl("wss://127.5.5.5"))
    }

    @Test
    fun `given private ipv4 ranges when checking local network then all are flagged`() {
        assertTrue(isLocalNetworkRelayUrl("ws://10.0.0.5:4848"))
        assertTrue(isLocalNetworkRelayUrl("ws://172.16.0.1"))
        assertTrue(isLocalNetworkRelayUrl("ws://172.31.255.255"))
        assertTrue(isLocalNetworkRelayUrl("wss://192.168.1.1:4848"))
        assertTrue(isLocalNetworkRelayUrl("ws://169.254.1.1")) // link-local
        assertTrue(isLocalNetworkRelayUrl("ws://0.0.0.0"))
    }

    @Test
    fun `given public ipv4 host when checking local network then it is not flagged`() {
        assertFalse(isLocalNetworkRelayUrl("wss://172.15.0.1")) // just outside 172.16.0.0/12
        assertFalse(isLocalNetworkRelayUrl("wss://172.32.0.1")) // just outside 172.16.0.0/12
        assertFalse(isLocalNetworkRelayUrl("wss://1.1.1.1"))
        assertFalse(isLocalNetworkRelayUrl("wss://relay.damus.io"))
    }

    @Test
    fun `given localhost or mdns hostname when checking local network then it is flagged`() {
        assertTrue(isLocalNetworkRelayUrl("ws://localhost:4848"))
        assertTrue(isLocalNetworkRelayUrl("ws://LOCALHOST"))
        assertTrue(isLocalNetworkRelayUrl("ws://my-relay.local"))
    }

    @Test
    fun `given ipv6 loopback and private ranges when checking local network then all are flagged`() {
        assertTrue(isLocalNetworkRelayUrl("ws://[::1]:4848"))
        assertTrue(isLocalNetworkRelayUrl("ws://[fe80::1]"))
        assertTrue(isLocalNetworkRelayUrl("ws://[fc00::1]"))
        assertTrue(isLocalNetworkRelayUrl("ws://[fd12:3456::1]"))
        assertTrue(isLocalNetworkRelayUrl("ws://[::ffff:192.168.1.5]"))
    }

    @Test
    fun `given public ipv6 host when checking local network then it is not flagged`() {
        assertFalse(isLocalNetworkRelayUrl("ws://[2001:4860:4860::8888]"))
    }

    @Test
    fun `given onion host when checking local network then it is not flagged`() {
        assertFalse(isLocalNetworkRelayUrl("ws://somelongonionaddress1234567890abcdefghijklmnopqrstuvwxyz.onion"))
    }

    @Test
    fun `given candidates with local and duplicate urls when selecting then only new public urls are kept`() {
        val result = selectNewDiscoverableRelayUrls(
            outboxRelayUrls = listOf(
                "wss://public.relay",
                "ws://192.168.1.1:4848",
                "WSS://Public.Relay/", // duplicate after normalization
                "wss://already-have.relay",
                "wss://another.relay"
            ),
            existingUrls = setOf("wss://already-have.relay"),
            budget = 10
        )

        assertEquals(listOf("wss://public.relay", "wss://another.relay"), result)
    }

    @Test
    fun `given more candidates than budget when selecting then result is capped`() {
        val result = selectNewDiscoverableRelayUrls(
            outboxRelayUrls = listOf("wss://r1", "wss://r2", "wss://r3"),
            existingUrls = emptySet(),
            budget = 2
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `given zero budget when selecting then nothing is returned`() {
        val result = selectNewDiscoverableRelayUrls(
            outboxRelayUrls = listOf("wss://r1"),
            existingUrls = emptySet(),
            budget = 0
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given explicit default port when normalizing then port is stripped`() {
        assertEquals("wss://relay.example.com", normalizeRelayUrl("wss://relay.example.com:443"))
        assertEquals("ws://relay.example.com", normalizeRelayUrl("ws://relay.example.com:80"))
    }

    @Test
    fun `given explicit default port with trailing slash when normalizing then both are stripped`() {
        assertEquals("wss://relay.example.com", normalizeRelayUrl("wss://relay.example.com:443/"))
    }

    @Test
    fun `given explicit default port with path when normalizing then port is stripped but path kept`() {
        assertEquals(
            "wss://relay.example.com/nostr",
            normalizeRelayUrl("wss://relay.example.com:443/nostr")
        )
    }

    @Test
    fun `given non-default explicit port when normalizing then port is kept`() {
        assertEquals("wss://relay.example.com:4433", normalizeRelayUrl("wss://relay.example.com:4433"))
        // wss's default is 443, not 80 — an explicit :80 on a wss:// URL is unusual but must
        // not be silently dropped, since that would actually change which port gets dialed.
        assertEquals("wss://relay.example.com:80", normalizeRelayUrl("wss://relay.example.com:80"))
    }

    @Test
    fun `given no explicit port when normalizing then url is unchanged`() {
        assertEquals("wss://relay.example.com", normalizeRelayUrl("wss://relay.example.com"))
    }

    @Test
    fun `given bracketed ipv6 host with default port when normalizing then port is stripped`() {
        assertEquals("wss://[2001:db8::1]", normalizeRelayUrl("wss://[2001:db8::1]:443"))
    }

    @Test
    fun `given bracketed ipv6 host without explicit port when normalizing then it is unchanged`() {
        assertEquals("wss://[2001:db8::443]", normalizeRelayUrl("wss://[2001:db8::443]"))
    }

    @Test
    fun `given peer-declared url with explicit default port and user-typed url without when comparing then they normalize the same`() {
        assertEquals(
            normalizeRelayUrl("wss://relay.damus.io:443"),
            normalizeRelayUrl("wss://relay.damus.io")
        )
    }

    @Test
    fun `given onion host declared as wss when normalizing then scheme is rewritten to ws`() {
        assertEquals(
            "ws://somelongonionaddress1234567890abcdefghijklmnopqrstuvwxyz.onion",
            normalizeRelayUrl("wss://somelongonionaddress1234567890abcdefghijklmnopqrstuvwxyz.onion")
        )
    }

    @Test
    fun `given onion host declared as wss with path when normalizing then scheme is rewritten and path kept`() {
        assertEquals(
            "ws://somelongonionaddress1234567890abcdefghijklmnopqrstuvwxyz.onion/nostr",
            normalizeRelayUrl("WSS://somelongonionaddress1234567890abcdefghijklmnopqrstuvwxyz.onion/nostr/")
        )
    }

    @Test
    fun `given onion host already declared as ws when normalizing then scheme is unchanged`() {
        assertEquals(
            "ws://somelongonionaddress1234567890abcdefghijklmnopqrstuvwxyz.onion",
            normalizeRelayUrl("ws://somelongonionaddress1234567890abcdefghijklmnopqrstuvwxyz.onion")
        )
    }

    @Test
    fun `given clearnet host declared as wss when normalizing then scheme is unchanged`() {
        assertEquals("wss://relay.damus.io", normalizeRelayUrl("wss://relay.damus.io"))
    }
}
