package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.AddressCoordinate
import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarksTest {

    private val author = "a".repeat(64)
    private val note = "1".repeat(64)
    private val secret = "2".repeat(64)
    private val article = "30023:$author:my-article"

    private val list = Event(
        id = "b".repeat(64),
        pubkey = author,
        createdAt = 10,
        kind = Event.KIND_BOOKMARK_LIST,
        tags = listOf(listOf("e", note), listOf("a", article), listOf("t", "nostr"), listOf("e", secret)),
        content = "",
        sig = "s".repeat(128)
    )

    @Test
    fun `given public and private items when listing then notes and articles are read and a duplicate counts as private`() {
        val bookmarks = bookmarksOf(list, privateTags = listOf(listOf("e", secret)))

        assertEquals(
            listOf(
                Bookmark(BookmarkTarget.Note(secret), isPrivate = true),
                Bookmark(BookmarkTarget.Note(note), isPrivate = false),
                Bookmark(BookmarkTarget.Address(AddressCoordinate(30023, author, "my-article")), isPrivate = false)
            ),
            bookmarks
        )
    }

    @Test
    fun `given an article when picking its target then it is saved by address`() {
        val articleEvent = Event(
            id = "c".repeat(64), pubkey = author, createdAt = 5, kind = 30023,
            tags = listOf(listOf("d", "my-article")), content = "", sig = "s".repeat(128)
        )

        assertEquals(article, BookmarkTarget.of(articleEvent).value)
        assertEquals("a", BookmarkTarget.of(articleEvent).tagName)
    }

    @Test
    fun `given malformed coordinates when parsing then they are rejected`() {
        assertNull(AddressCoordinate.parse("1:$author:x"))
        assertNull(AddressCoordinate.parse("30023:nothex:x"))
        assertEquals(AddressCoordinate(30023, author, ""), AddressCoordinate.parse("30023:$author:"))
    }

    @Test
    fun `given private tags when encoding and parsing then they round trip and junk is rejected`() {
        val tags = listOf(listOf("e", note), listOf("a", article, "wss://relay.example"))

        assertEquals(tags, parsePrivateTags(encodePrivateTags(tags)))
        assertNull(parsePrivateTags("not json"))
        assertEquals(false, looksLikeNip44Payload("""{"wss://relay.example":{}}"""))
    }
}
