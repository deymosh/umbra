package com.umbra.app.domain.model

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val author = "a".repeat(64)
    private val wallet = "w".repeat(64)
    private val note = event(Event.KIND_TEXT_NOTE, emptyList())
    private fun zap(recipient: String = author, signer: String = wallet) =
        EngagementLink(note.id, EngagementType.ZAP, sats = 21, zapRecipient = recipient, zapSigner = signer)
    private val nobodySignedIn: (String) -> Boolean = { false }

    @Test
    fun `given a zap to the author signed by their wallet key when checking then it counts`() {
        assertTrue(zapCountsToward(zap(), note, recipientSigner = wallet, isSignedInUser = nobodySignedIn))
    }

    @Test
    fun `given a zap signed by any other key when checking then it does not count`() {
        assertFalse(zapCountsToward(zap(signer = "f".repeat(64)), note, recipientSigner = wallet, isSignedInUser = nobodySignedIn))
    }

    @Test
    fun `given the recipient's wallet key is not known yet when checking then it does not count`() {
        assertFalse(zapCountsToward(zap(), note, recipientSigner = null, isSignedInUser = nobodySignedIn))
    }

    @Test
    fun `given a forger naming themselves as recipient with their own wallet when checking then it does not count`() {
        val forger = "b".repeat(64)
        assertFalse(zapCountsToward(zap(recipient = forger), note, recipientSigner = wallet, isSignedInUser = nobodySignedIn))
    }

    @Test
    fun `given a recipient named in the note's zap split when checking then it counts`() {
        val splitRecipient = "c".repeat(64)
        val splitNote = event(Event.KIND_TEXT_NOTE, listOf(listOf("zap", splitRecipient, "wss://relay.example", "1")))

        assertTrue(zapCountsToward(zap(recipient = splitRecipient), splitNote, recipientSigner = wallet, isSignedInUser = nobodySignedIn))
    }

    @Test
    fun `given the note is not known when checking then only a zap to the signed-in user counts`() {
        assertTrue(zapCountsToward(zap(), target = null, recipientSigner = wallet, isSignedInUser = { it == author }))
        assertFalse(zapCountsToward(zap(), target = null, recipientSigner = wallet, isSignedInUser = nobodySignedIn))
    }
}
