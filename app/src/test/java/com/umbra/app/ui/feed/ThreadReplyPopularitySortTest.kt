package com.umbra.app.ui.feed

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadReplyPopularitySortTest {

    private fun reply(id: String, parentId: String, createdAt: Long): Event = Event(
        id = id,
        pubkey = "a".repeat(64),
        createdAt = createdAt,
        kind = Event.KIND_TEXT_NOTE,
        tags = listOf(listOf("e", parentId)),
        content = "reply $id",
        sig = "s".repeat(128)
    )

    @Test
    fun `given_topLevelRepliesWithDifferentEngagement_when_reordering_then_mostPopularFirst`() {
        val anchorId = "anchor"
        // r1: least popular, r2: most popular (via reposts), r3: middling (via reactions)
        val r1 = reply("r1", anchorId, createdAt = 300)
        val r2 = reply("r2", anchorId, createdAt = 100)
        val r3 = reply("r3", anchorId, createdAt = 200)
        val descendants = listOf(r1, r2, r3)

        val result = reorderTopLevelDescendantsByPopularity(
            descendants = descendants,
            anchorId = anchorId,
            replyCounts = mapOf("r3" to 1),
            reactionCounts = mapOf("r3" to 1),
            repostCounts = mapOf("r2" to 10)
        )

        assertEquals(listOf("r2", "r3", "r1"), result.map { it.id })
    }

    @Test
    fun `given_tiedPopularity_when_reordering_then_newestFirst`() {
        val anchorId = "anchor"
        val older = reply("older", anchorId, createdAt = 100)
        val newer = reply("newer", anchorId, createdAt = 200)

        val result = reorderTopLevelDescendantsByPopularity(
            descendants = listOf(older, newer),
            anchorId = anchorId,
            replyCounts = emptyMap(),
            reactionCounts = emptyMap(),
            repostCounts = emptyMap()
        )

        assertEquals(listOf("newer", "older"), result.map { it.id })
    }

    @Test
    fun `given_nestedReplies_when_reordering_then_branchSubtreesStayContiguousAndInternalOrderUnchanged`() {
        val anchorId = "anchor"
        // Branch A (root "a1") is less popular than branch B (root "b1") but was posted first —
        // popularity must still win, and each branch's own nested reply order must be preserved.
        val a1 = reply("a1", anchorId, createdAt = 100)
        val a2 = reply("a2", "a1", createdAt = 110) // nested under a1
        val b1 = reply("b1", anchorId, createdAt = 200)
        val b2 = reply("b2", "b1", createdAt = 210) // nested under b1
        // DFS pre-order input, as collectDescendants would produce it: a1, a2, b1, b2
        val descendants = listOf(a1, a2, b1, b2)

        val result = reorderTopLevelDescendantsByPopularity(
            descendants = descendants,
            anchorId = anchorId,
            replyCounts = emptyMap(),
            reactionCounts = mapOf("b1" to 5),
            repostCounts = emptyMap()
        )

        assertEquals(listOf("b1", "b2", "a1", "a2"), result.map { it.id })
    }

    @Test
    fun `given_zeroOrOneDescendants_when_reordering_then_returnsUnchanged`() {
        val anchorId = "anchor"
        assertEquals(emptyList<Event>(), reorderTopLevelDescendantsByPopularity(emptyList(), anchorId, emptyMap(), emptyMap(), emptyMap()))

        val single = reply("only", anchorId, createdAt = 100)
        val result = reorderTopLevelDescendantsByPopularity(listOf(single), anchorId, emptyMap(), emptyMap(), emptyMap())
        assertEquals(listOf("only"), result.map { it.id })
    }
}
