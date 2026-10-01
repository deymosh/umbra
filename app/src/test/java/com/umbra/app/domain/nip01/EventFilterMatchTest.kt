package com.umbra.app.domain.nip01

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFilterMatchTest {

    private val author = "a".repeat(64)

    private fun event(kind: Int, tags: List<List<String>> = emptyList(), pubkey: String = author) = Event(
        id = "e".repeat(64),
        pubkey = pubkey,
        createdAt = 1_700_000_000L,
        kind = kind,
        tags = tags,
        content = "",
        sig = "f".repeat(128)
    )

    @Test
    fun `given matching kind author and d tag when matched then true`() {
        val filter = EventFilter(authors = setOf(author.uppercase()), kinds = setOf(30030), tagFilters = mapOf("d" to setOf("cats")))

        assertTrue(filter.matchesTagsAndIds(event(30030, listOf(listOf("d", "cats")))))
    }

    @Test
    fun `given a different d tag when matched then false`() {
        val filter = EventFilter(kinds = setOf(30030), tagFilters = mapOf("d" to setOf("cats")))

        assertFalse(filter.matchesTagsAndIds(event(30030, listOf(listOf("d", "dogs")))))
    }

    @Test
    fun `given uppercase and lowercase tag filters when matched then tag names are case sensitive`() {
        val filter = EventFilter(kinds = setOf(1111), tagFilters = mapOf("E" to setOf("root")))

        assertTrue(filter.matchesTagsAndIds(event(1111, listOf(listOf("E", "root")))))
        assertFalse(filter.matchesTagsAndIds(event(1111, listOf(listOf("e", "root")))))
    }

    @Test
    fun `given another author or kind when matched then false`() {
        val filter = EventFilter(authors = setOf(author), kinds = setOf(10133))

        assertFalse(filter.matchesTagsAndIds(event(10133, pubkey = "b".repeat(64))))
        assertFalse(filter.matchesTagsAndIds(event(10030)))
    }
}
