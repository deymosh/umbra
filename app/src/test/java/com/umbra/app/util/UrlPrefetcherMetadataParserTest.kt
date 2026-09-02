package com.umbra.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UrlPrefetcherMetadataParserTest {

    @Test
    fun `given_ogTagsWithMixedAttributeOrder_when_extracting_then_readsMetadata`() {
        val html = """
            <html>
              <head>
                <meta content="Umbra Preview" property="og:title" />
                <meta property="og:description" content="Preview description" />
                <meta content="https://cdn.example.com/cover.jpg" property="og:image" />
              </head>
            </html>
        """.trimIndent()

        val metadata = extractUrlMetadataFromHtml("https://example.com/note", html)

        assertNotNull(metadata)
        assertEquals("Umbra Preview", metadata?.title)
        assertEquals("Preview description", metadata?.description)
        assertEquals("https://cdn.example.com/cover.jpg", metadata?.imageUrl)
        assertEquals("example.com", metadata?.host)
    }

    @Test
    fun `given_relativeImageAndNoOgTitle_when_extracting_then_resolvesImageAndUsesTitleTag`() {
        val html = """
            <html>
              <head>
                <title>Spotify Track</title>
                <meta name="twitter:description" content="Track description" />
                <meta property="og:image" content="/images/track.png" />
              </head>
            </html>
        """.trimIndent()

        val metadata = extractUrlMetadataFromHtml("https://open.spotify.com/track/abc123", html)

        assertNotNull(metadata)
        assertEquals("Spotify Track", metadata?.title)
        assertEquals("Track description", metadata?.description)
        assertEquals("https://open.spotify.com/images/track.png", metadata?.imageUrl)
        assertEquals("open.spotify.com", metadata?.host)
    }

    @Test
    fun `given_htmlWithoutPreviewMetadata_when_extracting_then_returnsNull`() {
        val html = """
            <html>
              <head>
                <meta charset="utf-8" />
              </head>
              <body>No metadata</body>
            </html>
        """.trimIndent()

        val metadata = extractUrlMetadataFromHtml("https://example.com/plain", html)

        assertNull(metadata)
    }

    @Test
    fun `given_htmlContentTypes_when_checking_then_onlyHtmlVariantsReturnTrue`() {
        assertEquals(true, isHtmlContentType("text/html"))
        assertEquals(true, isHtmlContentType("text/html; charset=utf-8"))
        assertEquals(true, isHtmlContentType("application/xhtml+xml"))
        assertEquals(false, isHtmlContentType("image/png"))
        assertEquals(false, isHtmlContentType(null))
    }

    @Test
    fun `given_imageContentTypes_when_checking_then_onlyImageVariantsReturnTrue`() {
      assertEquals(true, isImageContentType("image/png"))
      assertEquals(true, isImageContentType("image/jpeg; charset=binary"))
      assertEquals(false, isImageContentType("text/html"))
      assertEquals(false, isImageContentType(null))
    }

    @Test
    fun `given_redirectedImageUrl_when_buildingImageOnlyMetadata_then_usesResolvedImageUrl`() {
      val metadata = buildImageOnlyMetadata(
        sourceUrl = "https://short.example/abc",
        resolvedUrl = "https://cdn.example.com/path/image.webp"
      )

      assertEquals("https://short.example/abc", metadata.url)
      assertEquals("https://cdn.example.com/path/image.webp", metadata.imageUrl)
      assertEquals("cdn.example.com", metadata.host)
      assertEquals(true, metadata.hasMetadata)
    }
}
