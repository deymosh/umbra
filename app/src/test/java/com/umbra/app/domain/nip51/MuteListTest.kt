package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteListTest {

    private val me = "a".repeat(64)
    private val author = "b".repeat(64)
    private val thread = "c".repeat(64)

    private fun note(content: String, tags: List<List<String>> = emptyList(), id: String = "1".repeat(64)) = Event(
        id = id, pubkey = author, createdAt = 1, kind = Event.KIND_TEXT_NOTE, tags = tags, content = content, sig = "s".repeat(128)
    )

    private fun list(vararg tags: List<String>, privateTags: List<List<String>>? = emptyList(), content: String = "") = muteListOf(
        Event(id = "9".repeat(64), pubkey = me, createdAt = 5, kind = Event.KIND_MUTED_USERS, tags = tags.toList(), content = content, sig = "s".repeat(128)),
        privateTags
    )

    @Test
    fun `given a muted word when checking notes then only whole words or phrases match, in any case`() {
        val mutes = list(listOf("word", "cat"), listOf("word", "Free Money"))

        assertTrue(mutes.hides(note("My CAT sleeps")))
        assertTrue(mutes.hides(note("get free money now")))
        assertFalse(mutes.hides(note("a category of things")))
    }

    @Test
    fun `given muted hashtags and threads when checking notes then tagged notes and replies in the thread are hidden`() {
        val mutes = list(listOf("t", "#Spam"), listOf("e", thread))

        assertTrue(mutes.hides(note("hello", listOf(listOf("t", "spam")))))
        assertTrue(mutes.hides(note("a reply", listOf(listOf("e", thread, "", "root")))))
        assertTrue(mutes.hides(note("the root itself", id = thread)))
        assertFalse(mutes.hides(note("unrelated", listOf(listOf("t", "nostr")))))
    }

    @Test
    fun `given an item muted publicly and privately when reading then it counts once, as private`() {
        val mutes = list(listOf("p", author), privateTags = listOf(listOf("p", author), listOf("t", "drama")))

        assertEquals(listOf(MuteItem(MuteItem.Kind.PERSON, author, isPrivate = true), MuteItem(MuteItem.Kind.HASHTAG, "drama", isPrivate = true)), mutes.items)
        assertTrue(mutes.hides(note("anything")))
    }

    @Test
    fun `given private content the signer hasn't opened when reading then the list says so`() {
        assertTrue(list(listOf("p", author), privateTags = null, content = "x".repeat(140)).privateLocked)
        assertFalse(list(listOf("p", author)).privateLocked)
    }
}
