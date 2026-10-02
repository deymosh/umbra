package com.umbra.app.ui.feed

import com.umbra.app.domain.model.EngagementCounts
import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadReplyPopularitySortTest {

    private val author = "a".repeat(64)
    private val other = "b".repeat(64)
    private val anchor = Event(
        id = "anchor",
        pubkey = author,
        createdAt = 1,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = "post",
        sig = "s".repeat(128)
    )

    private fun reply(id: String, parentId: String, createdAt: Long, pubkey: String = other): Event = Event(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = Event.KIND_TEXT_NOTE,
        tags = listOf(listOf("e", parentId)),
        content = "reply $id",
        sig = "s".repeat(128)
    )

    @Test
    fun `given_topLevelRepliesWithDifferentEngagement_when_reordering_then_mostPopularFirst`() {
        // r1: least popular, r2: most popular (via reposts), r3: middling (via reactions)
        val r1 = reply("r1", anchor.id, createdAt = 300)
        val r2 = reply("r2", anchor.id, createdAt = 100)
        val r3 = reply("r3", anchor.id, createdAt = 200)

        val result = reorderTopLevelDescendants(
            descendants = listOf(r1, r2, r3),
            anchor = anchor,
            engagement = mapOf(
                "r3" to EngagementCounts(replies = 1, reactions = 1),
                "r2" to EngagementCounts(reposts = 10)
            )
        )

        assertEquals(listOf("r2", "r3", "r1"), result.map { it.id })
    }

    @Test
    fun `given_tiedPopularity_when_reordering_then_newestFirst`() {
        val older = reply("older", anchor.id, createdAt = 100)
        val newer = reply("newer", anchor.id, createdAt = 200)

        val result = reorderTopLevelDescendants(listOf(older, newer), anchor, emptyMap())

        assertEquals(listOf("newer", "older"), result.map { it.id })
    }

    @Test
    fun `given_nestedReplies_when_reordering_then_branchSubtreesStayContiguousAndInternalOrderUnchanged`() {
        // Branch A (root "a1") is less popular than branch B (root "b1") but was posted first —
        // popularity must still win, and each branch's own nested reply order must be preserved.
        val a1 = reply("a1", anchor.id, createdAt = 100)
        val a2 = reply("a2", "a1", createdAt = 110) // nested under a1
        val b1 = reply("b1", anchor.id, createdAt = 200)
        val b2 = reply("b2", "b1", createdAt = 210) // nested under b1

        val result = reorderTopLevelDescendants(
            descendants = listOf(a1, a2, b1, b2),
            anchor = anchor,
            engagement = mapOf("b1" to EngagementCounts(reactions = 5))
        )

        assertEquals(listOf("b1", "b2", "a1", "a2"), result.map { it.id })
    }

    @Test
    fun `given_authorRepliesToOwnNote_when_reordering_then_theyComeFirstOldestFirst`() {
        val popular = reply("popular", anchor.id, createdAt = 50)
        val authorLater = reply("authorLater", anchor.id, createdAt = 300, pubkey = author)
        val authorFirst = reply("authorFirst", anchor.id, createdAt = 200, pubkey = author)

        val result = reorderTopLevelDescendants(
            descendants = listOf(popular, authorLater, authorFirst),
            anchor = anchor,
            engagement = mapOf("popular" to EngagementCounts(reactions = 50))
        )

        assertEquals(listOf("authorFirst", "authorLater", "popular"), result.map { it.id })
    }

    @Test
    fun `given_aZappedReply_when_reordering_then_zapsCountTowardPopularity`() {
        val liked = reply("liked", anchor.id, createdAt = 300)
        val zapped = reply("zapped", anchor.id, createdAt = 100)

        val result = reorderTopLevelDescendants(
            descendants = listOf(liked, zapped),
            anchor = anchor,
            engagement = mapOf(
                "liked" to EngagementCounts(reactions = 2),
                "zapped" to EngagementCounts(zapSats = 1_000)
            )
        )

        assertEquals(listOf("zapped", "liked"), result.map { it.id })
    }

    @Test
    fun `given_anySmallZap_when_scoring_then_itCountsAsOne`() {
        assertEquals(1L, replyPopularity(EngagementCounts(zapSats = 21)))
        assertEquals(0L, replyPopularity(null))
    }

    @Test
    fun `given_zeroOrOneDescendants_when_reordering_then_returnsUnchanged`() {
        assertEquals(emptyList<Event>(), reorderTopLevelDescendants(emptyList(), anchor, emptyMap()))

        val single = reply("only", anchor.id, createdAt = 100)
        val result = reorderTopLevelDescendants(listOf(single), anchor, emptyMap())
        assertEquals(listOf("only"), result.map { it.id })
    }
}
