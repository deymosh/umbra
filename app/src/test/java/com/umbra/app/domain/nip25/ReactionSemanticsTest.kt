package com.umbra.app.domain.nip25

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionSemanticsTest {

    private fun reactionEvent(tags: List<List<String>>, content: String = "+"): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = Event.KIND_REACTION,
        tags = tags,
        content = content,
        sig = "c".repeat(128)
    )

    @Test
    fun `given reaction with e and p tags when extracting target then returns both`() {
        val event = reactionEvent(tags = listOf(listOf("e", "event-id"), listOf("p", "author")))

        val target = extractReactionTarget(event)

        assertEquals("event-id", target.eventId)
        assertEquals("author", target.authorPubkey)
    }

    @Test
    fun `given non reaction event when extracting target then returns nulls`() {
        val event = reactionEvent(tags = emptyList()).copy(kind = Event.KIND_TEXT_NOTE)

        val target = extractReactionTarget(event)

        assertEquals(null, target.eventId)
        assertEquals(null, target.authorPubkey)
    }

    @Test
    fun `given reaction content when checking positivity then validates expected forms`() {
        assertTrue(isPositiveReactionContent("+"))
        assertTrue(isPositiveReactionContent(" ❤️ "))
        assertTrue(isPositiveReactionContent(""))
        assertFalse(isPositiveReactionContent("-"))
    }
}
