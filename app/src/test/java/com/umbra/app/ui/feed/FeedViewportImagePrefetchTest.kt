package com.umbra.app.ui.feed

import com.umbra.app.domain.nip01.Event
import com.umbra.app.ui.common.collectViewportHttpPrefetchUrls
import com.umbra.app.ui.common.collectViewportImagePrefetchUrls
import com.umbra.app.ui.common.collectViewportOldestCreatedAt
import com.umbra.app.ui.common.extractPrefetchableHttpUrlsFromText
import com.umbra.app.ui.common.extractPrefetchableImageUrlsFromText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedViewportImagePrefetchTest {

    private fun event(idSuffix: Int, content: String): Event {
        return Event(
            id = idSuffix.toString().padStart(64, '0'),
            pubkey = "a".repeat(64),
            createdAt = 1_000L + idSuffix,
            kind = Event.KIND_TEXT_NOTE,
            content = content,
            sig = "b".repeat(128)
        )
    }

    @Test
    fun `given visible and lookahead window when collecting then returns deduplicated image urls in range`() {
        val events = listOf(
            event(1, "first https://cdn.example.com/a.jpg"),
            event(2, "second https://cdn.example.com/b.png"),
            event(3, "third https://cdn.example.com/c.gif"),
            event(4, "fourth https://cdn.example.com/a.jpg"),
            event(5, "fifth https://cdn.example.com/d.webp")
        )

        val urls = collectViewportImagePrefetchUrls(
            events = events,
            firstVisibleIndex = 1,
            visibleCount = 2,
            lookAheadItems = 1,
            maxUrls = 10
        )

        assertEquals(
            listOf(
                "https://cdn.example.com/b.png",
                "https://cdn.example.com/c.gif",
                "https://cdn.example.com/a.jpg"
            ),
            urls
        )
    }

    @Test
    fun `given image urls with query string when extracting then keeps them as prefetchable`() {
        val text = "img https://img.example.com/pic.jpeg?width=1200 and https://img.example.com/x.webp#hash"

        val urls = extractPrefetchableImageUrlsFromText(text)

        assertEquals(
            listOf(
                "https://img.example.com/pic.jpeg?width=1200",
                "https://img.example.com/x.webp#hash"
            ),
            urls
        )
    }

    @Test
    fun `given mixed links when extracting http urls then keeps normalized non-empty list`() {
        val text = "go https://example.com/path?a=1 and https://cdn.example.com/a.jpg"

        val urls = extractPrefetchableHttpUrlsFromText(text)

        assertEquals(
            listOf(
                "https://example.com/path?a=1",
                "https://cdn.example.com/a.jpg"
            ),
            urls
        )
    }

    @Test
    fun `given bareDomainAndBalancedParentheses_when_extracting_http_urls_then_normalizesBoth`() {
        val text = "docs example.com/path and wiki (https://en.wikipedia.org/wiki/Function_(mathematics))"

        val urls = extractPrefetchableHttpUrlsFromText(text)

        assertEquals(
            listOf(
                "https://example.com/path",
                "https://en.wikipedia.org/wiki/Function_(mathematics)"
            ),
            urls
        )
    }

    @Test
    fun `given viewport with links when collecting http urls then skips images by default`() {
        val events = listOf(
            event(1, "news https://example.com/article and image https://cdn.example.com/a.jpg"),
            event(2, "thread https://example.net/topic"),
            event(3, "img only https://cdn.example.com/b.png")
        )

        val urls = collectViewportHttpPrefetchUrls(
            events = events,
            firstVisibleIndex = 0,
            visibleCount = 2,
            lookAheadItems = 1,
            maxUrls = 10
        )

        assertEquals(
            listOf(
                "https://example.com/article",
                "https://example.net/topic"
            ),
            urls
        )
    }

    @Test
    fun `given invalid viewport indices when collecting then returns empty list`() {
        val events = listOf(event(1, "https://cdn.example.com/a.jpg"))

        val urls = collectViewportImagePrefetchUrls(
            events = events,
            firstVisibleIndex = -1,
            visibleCount = 1
        )

        assertTrue(urls.isEmpty())
    }

    @Test
    fun `given a viewport window when collecting oldest created at then returns the minimum within that window only`() {
        val events = listOf(
            event(1, "a"),
            event(2, "b"),
            event(3, "c"),
            event(4, "d"),
            event(5, "e")
        )

        val oldest = collectViewportOldestCreatedAt(
            events = events,
            firstVisibleIndex = 2,
            visibleCount = 2,
            lookAheadItems = 0
        )

        // Window covers indices 2..3 (events 3 and 4); event 1's older createdAt is out of range.
        assertEquals(1_000L + 3, oldest)
    }

    @Test
    fun `given invalid viewport indices when collecting oldest created at then returns null`() {
        val events = listOf(event(1, "a"))

        val oldest = collectViewportOldestCreatedAt(
            events = events,
            firstVisibleIndex = -1,
            visibleCount = 1
        )

        assertNull(oldest)
    }
}

