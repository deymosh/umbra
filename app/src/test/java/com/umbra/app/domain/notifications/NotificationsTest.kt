package com.umbra.app.domain.notifications

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsTest {
    private val me = "0".repeat(64)
    private val note = "n".repeat(64)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun ev(id: String, pubkey: String, kind: Int, at: Long, tags: List<List<String>>, content: String = "") =
        Event(id = id.padEnd(64, '0'), pubkey = pubkey, createdAt = at, kind = kind, tags = tags, content = content)

    @Test
    fun `given reactions on one note when grouping then one row lists every reactor newest first`() {
        val groups = groupNotifications(
            listOf(
                ev("r1", alice, Event.KIND_REACTION, 10, listOf(listOf("e", note), listOf("p", me)), "+"),
                ev("r2", bob, Event.KIND_REACTION, 20, listOf(listOf("e", note), listOf("p", me)), "🔥"),
                ev("r3", alice, Event.KIND_REACTION, 5, listOf(listOf("e", note), listOf("p", me)), "+")
            ),
            mutedPubkeys = emptySet()
        )
        val group = groups.single()
        assertEquals(NotificationType.REACTION, group.type)
        assertEquals(listOf(bob, alice), group.actorPubkeys)
        assertEquals(note, group.targetEventId)
        assertEquals(20L, group.latestAt)
        assertEquals(listOf("🔥", "+"), group.reactions)
    }

    @Test
    fun `given replies and mentions when grouping then each stays its own row`() {
        val groups = groupNotifications(
            listOf(
                ev("m1", alice, Event.KIND_TEXT_NOTE, 30, listOf(listOf("p", me)), "hey"),
                ev("p1", bob, Event.KIND_TEXT_NOTE, 40, listOf(listOf("e", note, "", "reply"), listOf("p", me)), "agreed")
            ),
            mutedPubkeys = emptySet()
        )
        assertEquals(listOf(NotificationType.REPLY, NotificationType.MENTION), groups.map { it.type })
    }

    @Test
    fun `given muted author when grouping then their events are dropped`() {
        val groups = groupNotifications(
            listOf(ev("m1", alice, Event.KIND_TEXT_NOTE, 30, listOf(listOf("p", me)), "spam")),
            mutedPubkeys = setOf(alice)
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `given zap receipt when parsing then payer amount and comment come from the embedded request`() {
        val request = """{"pubkey":"$alice","kind":9734,"content":"great shot","tags":[]}"""
        val receipt = ev(
            "z1", "w".repeat(64), Event.KIND_ZAP_RECEIPT, 50,
            listOf(
                listOf("p", me),
                listOf("e", note),
                listOf("bolt11", "lnbc10u1qqqqqqqzraexu"),
                listOf("description", request)
            )
        )
        val parsed = parseZapReceipt(receipt)!!
        assertEquals(alice, parsed.senderPubkey)
        assertEquals(1_000L, parsed.amountSats)
        assertEquals("great shot", parsed.comment)
        val group = groupNotifications(listOf(receipt), emptySet()).single()
        assertEquals(NotificationType.ZAP, group.type)
        assertEquals(1_000L, group.zapTotalSats)
        assertEquals(listOf(alice), group.actorPubkeys)
    }
}
