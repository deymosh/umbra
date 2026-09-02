package com.umbra.app.domain.nipa4

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicMessageTest {

    private fun event(kind: Int, tags: List<List<String>>): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = kind,
        tags = tags,
        content = "hello",
        sig = "c".repeat(128)
    )

    @Test
    fun `given non public message kind when extracting then returns null`() {
        assertNull(extractPublicMessage(event(kind = Event.KIND_TEXT_NOTE, tags = emptyList())))
    }

    @Test
    fun `given receivers and quote when extracting then returns both`() {
        val receiver = "d".repeat(64)
        val target = event(
            kind = Event.KIND_PUBLIC_MESSAGE,
            tags = listOf(listOf("p", receiver), listOf("q", "e".repeat(64)))
        )

        val message = extractPublicMessage(target)

        assertEquals(listOf(receiver), message?.receiverPubkeys)
        assertEquals("e".repeat(64), message?.quotedRef)
    }

    @Test
    fun `given no tags when extracting then empty receivers and null quote`() {
        val message = extractPublicMessage(event(kind = Event.KIND_PUBLIC_MESSAGE, tags = emptyList()))

        assertEquals(emptyList<String>(), message?.receiverPubkeys)
        assertNull(message?.quotedRef)
    }
}
