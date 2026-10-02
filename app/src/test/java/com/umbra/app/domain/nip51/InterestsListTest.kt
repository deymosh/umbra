package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class InterestsListTest {

    private val me = "a".repeat(64)
    private val someoneElse = "b".repeat(64)

    private fun event(kind: Int, pubkey: String, createdAt: Long, tags: List<List<String>>) = Event(
        id = "$kind$pubkey$createdAt".take(64).padEnd(64, '0'),
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = "",
        sig = "s".repeat(128)
    )

    private fun interestSet(pubkey: String, d: String, createdAt: Long, vararg hashtags: String) =
        event(Event.KIND_INTEREST_SET, pubkey, createdAt, listOf(listOf("d", d)) + hashtags.map { listOf("t", it) })

    @Test
    fun `given public and private hashtags when listing then both count, normalized`() {
        val list = event(Event.KIND_INTERESTS_LIST, me, 1, listOf(listOf("t", "Nostr"), listOf("t", "#bitcoin"), listOf("t", " ")))

        assertEquals(setOf("nostr", "bitcoin", "photography"), listedHashtagsOf(list, listOf(listOf("t", "Photography"))))
    }

    @Test
    fun `given set pointers when following then only the newest revision of the user's own pointed sets counts`() {
        val list = event(
            Event.KIND_INTERESTS_LIST, me, 1,
            listOf(
                listOf("t", "nostr"),
                listOf("a", "${Event.KIND_INTEREST_SET}:$me:food"),
                listOf("a", "${Event.KIND_INTEREST_SET}:$someoneElse:music")
            )
        )
        val sets = listOf(
            interestSet(me, "food", 1, "pasta"),
            interestSet(me, "food", 2, "ramen"),
            interestSet(me, "travel", 3, "japan"),
            interestSet(someoneElse, "music", 4, "jazz")
        )

        assertEquals(setOf("nostr", "ramen"), followedHashtagsOf(list, privateTags = null, ownInterestSets = sets))
    }
}
