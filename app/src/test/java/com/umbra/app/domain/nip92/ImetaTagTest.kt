package com.umbra.app.domain.nip92

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImetaTagTest {

    @Test
    fun `given full imeta tag when extracting then parses every field`() {
        val tags = listOf(
            listOf(
                "imeta",
                "url https://nostr.build/i/my-image.jpg",
                "m image/jpeg",
                "blurhash LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
                "dim 3024x4032",
                "alt A scenic photo overlooking the coast of Costa Rica",
                "x abcd1234",
                "fallback https://nostrcheck.me/alt1.jpg",
                "fallback https://void.cat/alt1.jpg"
            )
        )

        val parsed = extractImetaTags(tags)

        assertEquals(1, parsed.size)
        val imeta = parsed.getValue("https://nostr.build/i/my-image.jpg")
        assertEquals("image/jpeg", imeta.mimeType)
        assertEquals("LKO2?U%2Tw=w]~RBVZRi};RPxuwH", imeta.blurhash)
        assertEquals(3024, imeta.dimensions?.width)
        assertEquals(4032, imeta.dimensions?.height)
        assertEquals("A scenic photo overlooking the coast of Costa Rica", imeta.alt)
        assertEquals("abcd1234", imeta.sha256)
        assertEquals(listOf("https://nostrcheck.me/alt1.jpg", "https://void.cat/alt1.jpg"), imeta.fallbackUrls)
    }

    @Test
    fun `given tag missing url when extracting then dropped`() {
        val tags = listOf(listOf("imeta", "m image/jpeg", "dim 100x100"))

        assertTrue(extractImetaTags(tags).isEmpty())
    }

    @Test
    fun `given non http url when extracting then dropped`() {
        val tags = listOf(listOf("imeta", "url file:///tmp/a.jpg", "m image/jpeg"))

        assertTrue(extractImetaTags(tags).isEmpty())
    }

    @Test
    fun `given malformed dim when extracting then dimensions null but other fields kept`() {
        val tags = listOf(listOf("imeta", "url https://cdn/a.jpg", "dim not-a-size", "m image/jpeg"))

        val imeta = extractImetaTags(tags).getValue("https://cdn/a.jpg")

        assertNull(imeta.dimensions)
        assertEquals("image/jpeg", imeta.mimeType)
    }

    @Test
    fun `given multiple imeta tags when extracting then keys map by url`() {
        val tags = listOf(
            listOf("imeta", "url https://cdn/a.jpg", "m image/jpeg"),
            listOf("imeta", "url https://cdn/b.mp4", "m video/mp4"),
            listOf("p", "a".repeat(64))
        )

        val parsed = extractImetaTags(tags)

        assertEquals(2, parsed.size)
        assertEquals("image/jpeg", parsed["https://cdn/a.jpg"]?.mimeType)
        assertEquals("video/mp4", parsed["https://cdn/b.mp4"]?.mimeType)
    }

    @Test
    fun `given dim ratio when computed then matches width over height`() {
        val dimensions = MediaDimensions(width = 3024, height = 4032)

        assertEquals(3024f / 4032f, dimensions.ratio, 0.0001f)
    }

    @Test
    fun `given full imeta tag when serializing then includes every present field`() {
        val imeta = ImetaTag(
            url = "https://nostr.download/abcd1234.jpg",
            mimeType = "image/jpeg",
            dimensions = MediaDimensions(width = 800, height = 600),
            blurhash = "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
            alt = "A scenic photo",
            sha256 = "abcd1234",
            sizeBytes = 2048L,
            fallbackUrls = listOf("https://mirror.example/abcd1234.jpg")
        )

        val tag = imeta.toTag()

        assertEquals(
            listOf(
                "imeta",
                "url https://nostr.download/abcd1234.jpg",
                "m image/jpeg",
                "dim 800x600",
                "blurhash LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
                "alt A scenic photo",
                "x abcd1234",
                "size 2048",
                "fallback https://mirror.example/abcd1234.jpg"
            ),
            tag
        )
    }

    @Test
    fun `given minimal imeta tag when serializing then omits absent fields`() {
        val imeta = ImetaTag(url = "https://nostr.download/abcd1234.jpg")

        assertEquals(listOf("imeta", "url https://nostr.download/abcd1234.jpg"), imeta.toTag())
    }

    @Test
    fun `given imeta tag when serializing then round trips through extraction`() {
        val original = ImetaTag(
            url = "https://nostr.download/abcd1234.jpg",
            mimeType = "image/jpeg",
            dimensions = MediaDimensions(width = 800, height = 600),
            blurhash = "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
            alt = "A scenic photo",
            sha256 = "abcd1234",
            sizeBytes = 2048L
        )

        val reparsed = extractImetaTags(listOf(original.toTag())).getValue(original.url)

        assertEquals(original, reparsed)
    }
}
