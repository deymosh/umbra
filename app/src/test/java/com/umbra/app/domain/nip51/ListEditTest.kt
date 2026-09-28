package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class ListEditTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private fun muteList(vararg tags: List<String>) =
        Event(id = "i".repeat(64), pubkey = "0".repeat(64), createdAt = 1, kind = Event.KIND_MUTED_USERS, tags = tags.toList(), content = "nip44-private-mutes")

    @Test
    fun `given a list with other tag types when adding a pubkey then everything else is kept`() {
        val base = muteList(listOf("p", alice), listOf("t", "spam"), listOf("word", "airdrop"), listOf("e", "x".repeat(64)))
        val tags = applyListEdit(base, ListEdit("p", add = setOf(bob)))
        assertEquals(
            listOf(listOf("p", alice), listOf("t", "spam"), listOf("word", "airdrop"), listOf("e", "x".repeat(64)), listOf("p", bob)),
            tags
        )
    }

    @Test
    fun `given removal when editing then only that value goes and extra fields on others survive`() {
        val base = muteList(listOf("p", alice, "wss://relay.example", "Al"), listOf("p", bob.uppercase()))
        val tags = applyListEdit(base, ListEdit("p", remove = setOf(bob)))
        assertEquals(listOf(listOf("p", alice, "wss://relay.example", "Al")), tags)
    }

    @Test
    fun `given an existing value when adding it again then it is not duplicated`() {
        val tags = applyListEdit(muteList(listOf("p", alice)), ListEdit("p", add = setOf(alice.uppercase())))
        assertEquals(listOf(listOf("p", alice)), tags)
    }

    @Test
    fun `given no previous list when editing then the fallback set seeds it`() {
        val tags = applyListEdit(null, ListEdit("p", add = setOf(carol)), fallbackValues = setOf(alice))
        assertEquals(listOf(listOf("p", alice), listOf("p", carol)), tags)
    }
}
