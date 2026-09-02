package com.umbra.app.domain.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayIssueLogTest {

    private fun issue(relayUrl: String, message: String) =
        RelayIssue(relayUrl = relayUrl, kind = RelayIssueKind.NOTICE, rawMessage = message)

    @Test
    fun `given entries under the per-relay cap when appending then nothing is evicted`() {
        var log = listOf(issue("wss://a.relay", "1"))
        log = appendBoundedRelayIssue(log, issue("wss://a.relay", "2"), maxPerRelay = 5)
        log = appendBoundedRelayIssue(log, issue("wss://b.relay", "1"), maxPerRelay = 5)

        assertEquals(3, log.size)
        assertEquals(listOf("1", "2", "1"), log.map { it.rawMessage })
    }

    @Test
    fun `given a relay at its cap when appending then only that relay's oldest entry is evicted`() {
        var log = listOf(
            issue("wss://a.relay", "a1"),
            issue("wss://b.relay", "b1"),
            issue("wss://a.relay", "a2")
        )
        log = appendBoundedRelayIssue(log, issue("wss://a.relay", "a3"), maxPerRelay = 2)

        // a.relay was at its cap (a1, a2) — a1 (oldest) is evicted, b.relay is untouched.
        assertEquals(listOf("b1", "a2", "a3"), log.map { it.rawMessage })
    }

    @Test
    fun `given many relays each near their own cap when appending then no relay evicts another's entries`() {
        var log = emptyList<RelayIssue>()
        val relays = (1..50).map { "wss://relay$it.example" }
        relays.forEach { url ->
            repeat(5) { n -> log = appendBoundedRelayIssue(log, issue(url, "msg$n"), maxPerRelay = 5) }
        }

        // Every one of the 50 relays should still have exactly 5 entries — none evicted another's.
        relays.forEach { url ->
            assertEquals(5, log.count { it.relayUrl == url })
        }
        assertEquals(250, log.size)
    }

    @Test
    fun `given url casing differences when appending then still grouped as the same relay`() {
        var log = listOf(issue("WSS://Relay.Example.com/", "1"), issue("wss://relay.example.com", "2"))
        log = appendBoundedRelayIssue(log, issue("wss://relay.example.com/", "3"), maxPerRelay = 2)

        assertTrue(log.none { it.rawMessage == "1" })
        assertEquals(listOf("2", "3"), log.map { it.rawMessage })
    }

    @Test
    fun `given a batch when applied then each relay keeps only its own last maxPerRelay entries`() {
        val batch = (1..50).flatMap { n -> listOf(issue("wss://a.relay", "a$n"), issue("wss://b.relay", "b$n")) }
        val log = appendBoundedRelayIssues(emptyList(), batch, maxPerRelay = 5)

        assertEquals(listOf("a46", "a47", "a48", "a49", "a50"), log.filter { it.relayUrl == "wss://a.relay" }.map { it.rawMessage })
        assertEquals(listOf("b46", "b47", "b48", "b49", "b50"), log.filter { it.relayUrl == "wss://b.relay" }.map { it.rawMessage })
        assertEquals(10, log.size)
    }

    @Test
    fun `given a batch when applied then result matches folding one item at a time`() {
        val relays = (1..20).map { "wss://relay$it.example" }
        val batch = relays.flatMap { url -> (1..8).map { n -> issue(url, "msg$n") } }

        val batched = appendBoundedRelayIssues(emptyList(), batch, maxPerRelay = 5)
        val folded = batch.fold(emptyList<RelayIssue>()) { acc, i -> appendBoundedRelayIssue(acc, i, maxPerRelay = 5) }

        relays.forEach { url ->
            assertEquals(
                folded.filter { it.relayUrl == url }.map { it.rawMessage },
                batched.filter { it.relayUrl == url }.map { it.rawMessage }
            )
        }
    }

    @Test
    fun `given an empty batch when applied then current is returned unchanged`() {
        val log = listOf(issue("wss://a.relay", "1"))
        assertEquals(log, appendBoundedRelayIssues(log, emptyList(), maxPerRelay = 5))
    }
}
