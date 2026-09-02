package com.umbra.app.ui.feed

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.ui.common.ImmutableMapSnapshot
import com.umbra.app.ui.common.toImmutableSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedEventFilteringTest {

    @Test
    fun `given blank query when building searchable events then returns only feed events`() {
        val feedEvents = listOf(testEvent(id = "a".repeat(64)))
        val relayResults = listOf(testEvent(id = "b".repeat(64)))

        val result = buildSearchableFeedEvents(feedEvents, relayResults, query = "")

        assertEquals(feedEvents, result)
    }

    @Test
    fun `given non blank query when building searchable events then deduplicates by id`() {
        val shared = testEvent(id = "a".repeat(64))
        val feedEvents = listOf(shared)
        val relayResults = listOf(shared, testEvent(id = "b".repeat(64)))

        val result = buildSearchableFeedEvents(feedEvents, relayResults, query = "nostr")

        assertEquals(2, result.size)
        assertEquals(setOf("a".repeat(64), "b".repeat(64)), result.map { it.id }.toSet())
    }

    @Test
    fun `given blank query when filtering then keeps only top level notes`() {
        val top = testEvent(id = "c".repeat(64), tags = emptyList())
        val reply = testEvent(id = "d".repeat(64), tags = listOf(listOf("e", "c".repeat(64))))

        val result = filterFeedEventsForQuery(
            events = listOf(top, reply),
            normalizedQuery = "",
            profiles = ImmutableMapSnapshot()
        )

        assertEquals(listOf(top), result)
    }

    @Test
    fun `given query matching profile when filtering then event is included`() {
        val event = testEvent(id = "e".repeat(64), pubkey = "f".repeat(64), content = "hello")
        val profiles = mapOf(
            event.pubkey to UserProfile(pubkey = event.pubkey, displayName = "Alice")
        ).toImmutableSnapshot()

        val result = filterFeedEventsForQuery(
            events = listOf(event),
            normalizedQuery = "alice",
            profiles = profiles
        )

        assertTrue(result.contains(event))
    }

    private fun testEvent(
        id: String,
        pubkey: String = "1".repeat(64),
        content: String = "note",
        tags: List<List<String>> = emptyList()
    ): Event {
        return Event(
            id = id,
            pubkey = pubkey,
            createdAt = 1_700_000_000L,
            kind = Event.KIND_TEXT_NOTE,
            tags = tags,
            content = content,
            sig = "2".repeat(128)
        )
    }
}

