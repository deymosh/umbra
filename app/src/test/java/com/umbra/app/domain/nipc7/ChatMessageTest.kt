package com.umbra.app.domain.nipc7

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageTest {

    private fun event(kind: Int, tags: List<List<String>>): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = kind,
        tags = tags,
        content = "GM",
        sig = "c".repeat(128)
    )

    @Test
    fun `given non chat kind when extracting then returns null`() {
        assertNull(extractChatMessage(event(kind = Event.KIND_TEXT_NOTE, tags = emptyList())))
    }

    @Test
    fun `given no q tag when extracting then quoted fields are null`() {
        val message = extractChatMessage(event(kind = Event.KIND_CHAT_MESSAGE, tags = emptyList()))

        assertNull(message?.quotedEventId)
        assertNull(message?.quotedRelayUrl)
    }

    @Test
    fun `given q tag when extracting then returns quoted event id and relay`() {
        val eventId = "d".repeat(64)
        val target = event(
            kind = Event.KIND_CHAT_MESSAGE,
            tags = listOf(listOf("q", eventId, "wss://relay.example", "e".repeat(64)))
        )

        val message = extractChatMessage(target)

        assertEquals(eventId, message?.quotedEventId)
        assertEquals("wss://relay.example", message?.quotedRelayUrl)
    }
}
