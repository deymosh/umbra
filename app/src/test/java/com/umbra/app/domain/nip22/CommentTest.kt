package com.umbra.app.domain.nip22

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentTest {

    private fun comment(tags: List<List<String>>, kind: Int = Event.KIND_COMMENT): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = kind,
        tags = tags,
        content = "comment",
        sig = "c".repeat(128)
    )

    @Test
    fun `given top level comment on regular event when extracting then root and parent both point to it`() {
        val event = comment(
            tags = listOf(
                listOf("E", "root-id", "wss://relay", "root-author"),
                listOf("K", "1063"),
                listOf("P", "root-author"),
                listOf("e", "root-id", "wss://relay", "root-author"),
                listOf("k", "1063"),
                listOf("p", "root-author")
            )
        )

        val target = extractCommentTarget(event)!!

        val rootPointer = target.root?.pointer as CommentPointer.EventPointer
        assertEquals("root-id", rootPointer.eventId)
        assertEquals("1063", target.root.kind)
        assertEquals("root-author", target.root.authorPubkey)

        val parentPointer = target.parent?.pointer as CommentPointer.EventPointer
        assertEquals("root-id", parentPointer.eventId)
        assertEquals("1063", target.parent.kind)
    }

    @Test
    fun `given reply to a comment when extracting then root and parent differ`() {
        val event = comment(
            tags = listOf(
                listOf("E", "root-id"),
                listOf("K", "1063"),
                listOf("P", "root-author"),
                listOf("e", "comment-id", "wss://relay", "comment-author"),
                listOf("k", "1111"),
                listOf("p", "comment-author")
            )
        )

        val target = extractCommentTarget(event)!!

        assertEquals("root-id", (target.root?.pointer as CommentPointer.EventPointer).eventId)
        assertEquals("comment-id", (target.parent?.pointer as CommentPointer.EventPointer).eventId)
        assertEquals("1111", target.parent.kind)
    }

    @Test
    fun `given addressable root when extracting then uses address pointer`() {
        val event = comment(
            tags = listOf(
                listOf("A", "30023:author:my-article", "wss://relay"),
                listOf("K", "30023"),
                listOf("P", "author"),
                listOf("a", "30023:author:my-article"),
                listOf("k", "30023")
            )
        )

        val target = extractCommentTarget(event)!!

        val rootPointer = target.root?.pointer as CommentPointer.AddressPointer
        assertEquals("30023:author:my-article", rootPointer.address)
    }

    @Test
    fun `given external identifier scope when extracting then uses external pointer`() {
        val event = comment(
            tags = listOf(
                listOf("I", "https://abc.com/articles/1"),
                listOf("K", "web"),
                listOf("i", "https://abc.com/articles/1"),
                listOf("k", "web")
            )
        )

        val target = extractCommentTarget(event)!!

        assertEquals("https://abc.com/articles/1", (target.root?.pointer as CommentPointer.ExternalPointer).identifier)
        assertEquals("web", target.root.kind)
    }

    @Test
    fun `given scope missing required k tag when extracting then that scope is null`() {
        val event = comment(
            tags = listOf(
                listOf("E", "root-id")
                // no K tag — spec says K/k MUST be present
            )
        )

        val target = extractCommentTarget(event)!!

        assertNull(target.root)
    }

    @Test
    fun `given non comment kind when extracting then returns null`() {
        val event = comment(tags = emptyList(), kind = Event.KIND_TEXT_NOTE)

        assertNull(extractCommentTarget(event))
    }
}
