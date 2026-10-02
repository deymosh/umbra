package com.umbra.app.domain.model

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngagementTest {

    private fun event(kind: Int, tags: List<List<String>>, content: String = "") = Event(
        id = "e".repeat(64),
        pubkey = "a".repeat(64),
        createdAt = 10,
        kind = kind,
        tags = tags,
        content = content,
        sig = "s".repeat(128)
    )

    private val accept: (Event) -> Boolean = { true }

    @Test
    fun `given a reaction when linking then only its last e tag is the target`() {
        val links = engagementLinksOf(
            event(Event.KIND_REACTION, listOf(listOf("e", "root"), listOf("e", "target")), "+"),
            accept
        )

        assertEquals(listOf(EngagementLink("target", EngagementType.REACTION)), links)
    }

    @Test
    fun `given a dislike when linking then it counts nowhere`() {
        assertTrue(engagementLinksOf(event(Event.KIND_REACTION, listOf(listOf("e", "t")), "-"), accept).isEmpty())
    }

    @Test
    fun `given a nip10 reply when linking then root and parent count but not a mention`() {
        val reply = event(
            Event.KIND_TEXT_NOTE,
            listOf(
                listOf("e", "root", "", "root"),
                listOf("e", "parent", "", "reply"),
                listOf("e", "quoted", "", "mention")
            )
        )

        val targets = engagementLinksOf(reply, accept).map { it.targetId to it.type }

        assertEquals(listOf("root" to EngagementType.REPLY, "parent" to EngagementType.REPLY), targets)
    }

    @Test
    fun `given a nip22 comment when linking then its parent and root both count as replies`() {
        val comment = event(
            Event.KIND_COMMENT,
            listOf(listOf("E", "root"), listOf("K", "1"), listOf("e", "parent"), listOf("k", "1111"))
        )

        val targets = engagementLinksOf(comment, accept).map { it.targetId }.toSet()

        assertEquals(setOf("parent", "root"), targets)
    }

    @Test
    fun `given a zap receipt that does not validate when linking then it adds no sats`() {
        val forged = event(Event.KIND_ZAP_RECEIPT, listOf(listOf("e", "note"), listOf("bolt11", "lnbc1")))

        assertTrue(engagementLinksOf(forged, accept).isEmpty())
    }

    @Test
    fun `given links for several targets when folding then counts and sats add up per target`() {
        val counts = listOf(
            EngagementLink("a", EngagementType.REACTION),
            EngagementLink("a", EngagementType.ZAP, sats = 21),
            EngagementLink("a", EngagementType.ZAP, sats = 100),
            EngagementLink("b", EngagementType.REPLY)
        ).toEngagementCounts()

        assertEquals(EngagementCounts(reactions = 1, zapSats = 121), counts["a"])
        assertEquals(EngagementCounts(replies = 1), counts["b"])
    }
}
