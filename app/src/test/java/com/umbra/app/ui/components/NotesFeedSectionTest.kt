package com.umbra.app.ui.components

import com.umbra.app.domain.model.PendingRepost
import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesFeedSectionTest {

    private fun event(id: String, createdAt: Long) = Event(
        id = id,
        pubkey = "f".repeat(64),
        createdAt = createdAt,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = "x",
        sig = "e".repeat(128)
    )

    private fun pending(repostId: String, repostedAt: Long) = PendingRepost(
        repostId = repostId,
        repostedByPubkey = "b".repeat(64),
        repostedAt = repostedAt,
        targetId = "t".repeat(64)
    )

    @Test
    fun `given no pending reposts when merging then rows are just the notes in order`() {
        val notes = listOf(event("a", 30), event("b", 20))

        val rows = mergeFeedRows(notes, emptyList())

        assertEquals(
            listOf(FeedRow.NoteRow(notes[0]), FeedRow.NoteRow(notes[1])),
            rows
        )
    }

    @Test
    fun `given a pending repost newer than every note when merging then it comes first`() {
        val notes = listOf(event("a", 20), event("b", 10))
        val pendingRepost = pending("p", 30)

        val rows = mergeFeedRows(notes, listOf(pendingRepost))

        assertEquals(
            listOf(FeedRow.PendingRow(pendingRepost), FeedRow.NoteRow(notes[0]), FeedRow.NoteRow(notes[1])),
            rows
        )
    }

    @Test
    fun `given a pending repost older than every note when merging then it comes last`() {
        val notes = listOf(event("a", 30), event("b", 20))
        val pendingRepost = pending("p", 5)

        val rows = mergeFeedRows(notes, listOf(pendingRepost))

        assertEquals(
            listOf(FeedRow.NoteRow(notes[0]), FeedRow.NoteRow(notes[1]), FeedRow.PendingRow(pendingRepost)),
            rows
        )
    }

    @Test
    fun `given a pending repost between two notes when merging then it is inserted between them`() {
        val notes = listOf(event("a", 30), event("b", 10))
        val pendingRepost = pending("p", 20)

        val rows = mergeFeedRows(notes, listOf(pendingRepost))

        assertEquals(
            listOf(FeedRow.NoteRow(notes[0]), FeedRow.PendingRow(pendingRepost), FeedRow.NoteRow(notes[1])),
            rows
        )
    }

    @Test
    fun `given multiple pending reposts when merging then they are sorted newest first among themselves`() {
        val notes = listOf(event("a", 5))
        val older = pending("older", 10)
        val newer = pending("newer", 20)

        // Passed in arbitrary order — merge must not just preserve input order.
        val rows = mergeFeedRows(notes, listOf(older, newer))

        assertEquals(
            listOf(FeedRow.PendingRow(newer), FeedRow.PendingRow(older), FeedRow.NoteRow(notes[0])),
            rows
        )
    }
}
