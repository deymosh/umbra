package com.umbra.app.domain.nip18

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepostTest {

    private fun repostEvent(
        kind: Int = Event.KIND_REPOST,
        tags: List<List<String>> = emptyList(),
        content: String = ""
    ): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = kind,
        tags = tags,
        content = content,
        sig = "c".repeat(128)
    )

    @Test
    fun `given repost with e and p tags when extracting target then returns both`() {
        val event = repostEvent(tags = listOf(listOf("e", "event-id"), listOf("p", "author")))

        val target = extractRepostTarget(event)

        assertEquals("event-id", target.eventId)
        assertEquals("author", target.authorPubkey)
    }

    @Test
    fun `given e tag with a relay hint when extracting target then returns it`() {
        val event = repostEvent(tags = listOf(listOf("e", "event-id", "wss://relay.example")))

        val target = extractRepostTarget(event)

        assertEquals("wss://relay.example", target.relayHint)
    }

    @Test
    fun `given e tag with no relay hint when extracting target then relay hint is null`() {
        val event = repostEvent(tags = listOf(listOf("e", "event-id")))

        val target = extractRepostTarget(event)

        assertNull(target.relayHint)
    }

    @Test
    fun `given e tag with a blank relay hint when extracting target then relay hint is null`() {
        val event = repostEvent(tags = listOf(listOf("e", "event-id", "")))

        val target = extractRepostTarget(event)

        assertNull(target.relayHint)
    }

    @Test
    fun `given generic repost when extracting target then still resolves`() {
        val event = repostEvent(kind = Event.KIND_GENERIC_REPOST, tags = listOf(listOf("e", "event-id")))

        val target = extractRepostTarget(event)

        assertEquals("event-id", target.eventId)
    }

    @Test
    fun `given non repost event when extracting target then returns nulls`() {
        val event = repostEvent().copy(kind = Event.KIND_TEXT_NOTE)

        val target = extractRepostTarget(event)

        assertNull(target.eventId)
        assertNull(target.authorPubkey)
    }

    @Test
    fun `given repost with multiple e tags when extracting target then uses the last one`() {
        // NIP-18: the last "e" tag is the repost target when more than one is present.
        val event = repostEvent(tags = listOf(listOf("e", "first"), listOf("e", "second")))

        val target = extractRepostTarget(event)

        assertEquals("second", target.eventId)
    }

    @Test
    fun `given blank content when parsing reposted event then returns null`() {
        val event = repostEvent(content = "")

        assertNull(parseRepostedEvent(event))
    }

    @Test
    fun `given malformed json content when parsing reposted event then returns null`() {
        val event = repostEvent(content = "not json")

        assertNull(parseRepostedEvent(event))
    }

    @Test
    fun `given content missing a required field when parsing reposted event then returns null`() {
        val json = """{"id":"${"d".repeat(64)}","pubkey":"${"e".repeat(64)}","kind":1,"tags":[],"content":"hi"}"""
        val event = repostEvent(content = json)

        assertNull(parseRepostedEvent(event))
    }

    @Test
    fun `given valid embedded event json when parsing reposted event then reconstructs it`() {
        val targetId = "d".repeat(64)
        val targetPubkey = "e".repeat(64)
        val json = """
            {"id":"$targetId","pubkey":"$targetPubkey","created_at":1700000000,"kind":1,
             "tags":[["t","nostr"]],"content":"hello world","sig":"${"f".repeat(128)}"}
        """.trimIndent()
        val event = repostEvent(content = json)

        val target = parseRepostedEvent(event)

        assertEquals(targetId, target?.id)
        assertEquals(targetPubkey, target?.pubkey)
        assertEquals(1700000000L, target?.createdAt)
        assertEquals(1, target?.kind)
        assertEquals("hello world", target?.content)
        assertEquals(listOf(listOf("t", "nostr")), target?.tags)
    }

    @Test
    fun `given non repost event when parsing reposted event then returns null regardless of content`() {
        val json = """{"id":"${"d".repeat(64)}","pubkey":"${"e".repeat(64)}","created_at":1,"kind":1,"tags":[],"content":"x","sig":"${"f".repeat(128)}"}"""
        val event = repostEvent(content = json).copy(kind = Event.KIND_TEXT_NOTE)

        assertNull(parseRepostedEvent(event))
    }
}
