package com.umbra.app.domain.nip7d

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForumThreadTest {

    private fun threadEvent(tags: List<List<String>>, kind: Int = Event.KIND_THREAD): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = kind,
        tags = tags,
        content = "Good morning",
        sig = "c".repeat(128)
    )

    @Test
    fun `given kind11 event with title when extracting then returns title`() {
        val event = threadEvent(tags = listOf(listOf("title", "GM")))

        assertEquals("GM", extractForumThread(event)?.title)
    }

    @Test
    fun `given kind11 event without title when extracting then title is null`() {
        val event = threadEvent(tags = emptyList())

        assertNull(extractForumThread(event)?.title)
    }

    @Test
    fun `given non kind11 event when extracting then returns null`() {
        val event = threadEvent(tags = emptyList(), kind = Event.KIND_TEXT_NOTE)

        assertNull(extractForumThread(event))
    }
}
