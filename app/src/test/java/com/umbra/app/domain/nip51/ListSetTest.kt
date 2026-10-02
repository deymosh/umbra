package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class ListSetTest {

    private val me = "a".repeat(64)

    private fun set(d: String, createdAt: Long, vararg tags: List<String>) = Event(
        id = "$d$createdAt".padEnd(64, '0'),
        pubkey = me,
        createdAt = createdAt,
        kind = Event.KIND_BOOKMARK_SET,
        tags = listOf(listOf("d", d)) + tags,
        content = "",
        sig = "s".repeat(128)
    )

    @Test
    fun `given revisions and an emptied set when listing then only the newest live sets remain, sorted by title`() {
        val sets = latestListSets(
            listOf(
                set("x", 1, listOf("title", "Zebra")),
                set("x", 2, listOf("title", "Recipes")),
                set("y", 1, listOf("name", "Articles")),
                set("z", 5)
            )
        )

        assertEquals(listOf("Articles", "Recipes"), sets.map { it.title })
    }

    @Test
    fun `given a set when retitling then the title replaces title and name and sits after d`() {
        val tags = listOf(listOf("d", "x"), listOf("name", "Old"), listOf("e", "1".repeat(64)), listOf("title", "Older"))

        assertEquals(
            listOf(listOf("d", "x"), listOf("title", "New"), listOf("e", "1".repeat(64))),
            retitledListSetTags(tags, " New ")
        )
    }
}
