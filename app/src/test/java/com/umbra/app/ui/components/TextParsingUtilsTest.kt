package com.umbra.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextParsingUtilsTest {

    @Test
    fun `given_urlWithQueryParams_when_extractingImages_then_includesParams`() {
        val text = "Image: https://example.com/path/img.jpg?param=1&other=2 end"
        val urls = extractImageUrls(text)
        assertEquals(1, urls.size)
        assertEquals("https://example.com/path/img.jpg?param=1&other=2", urls[0])
    }

    @Test
    fun `given_urlWithQueryParams_when_extractingVideos_then_includesParams`() {
        val text = "Video: https://example.com/video.mp4?token=abc#t=10"
        val urls = extractVideoUrls(text)
        assertEquals(1, urls.size)
        assertEquals("https://example.com/video.mp4?token=abc#t=10", urls[0])
    }

    @Test
    fun `given_urlWithTrailingDelimiters_when_sanitizing_then_trims`() {
        val raw = "https://example.com/img.jpg,)"
        val sanitized = sanitizeDetectedUrl(raw)
        assertEquals("https://example.com/img.jpg", sanitized)
    }

    @Test
    fun `given_urlWrappedInQuotesAndBrace_when_sanitizing_then_trimsLeadingAndTrailing`() {
        val raw = "\"https://example.com/path\"}"
        val sanitized = sanitizeDetectedUrl(raw)
        assertEquals("https://example.com/path", sanitized)
    }

    @Test
    fun `given_urlWithEscapedJsonTail_when_parsingCandidate_then_extractsOnlyValidUrl`() {
        val raw = "https:\\/\\/cdn.jsdelivr.net\\/gh\\/jdecked\\/twemoji@17.0.2\\/assets\\/72x72\\/1f9ab.png\\\"},{\\\"zeroWidth\\\":false"
        val parsed = parseExternalUrlCandidate(raw)

        requireNotNull(parsed)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/jdecked/twemoji@17.0.2/assets/72x72/1f9ab.png",
            parsed.displayUrl
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/jdecked/twemoji@17.0.2/assets/72x72/1f9ab.png",
            parsed.normalizedUrl
        )
    }

    @Test
    fun `given_bareDomainOrLocalPath_when_normalizing_then_acceptsDomainRejectsPath`() {
        assertEquals(
            "https://example.com/path",
            normalizeAndValidateExternalUrl("example.com/path")
        )
        assertNull(normalizeAndValidateExternalUrl("/home/user/project/index.js"))
        assertNull(normalizeAndValidateExternalUrl("C:\\Users\\sebas\\file.png"))
    }

    @Test
    fun `given_mixedValidAndInvalidUrls_when_extractingFirst_then_returnsFirstValid`() {
        val text = "bad /home/user/file then example.com/docs and later https://second.com"
        assertEquals("https://example.com/docs", extractFirstUrl(text))
    }

    @Test
    fun `given_urlWithBalancedParentheses_when_sanitizing_then_keepsClosingParen`() {
        val raw = "https://en.wikipedia.org/wiki/Function_(mathematics)"
        val sanitized = sanitizeDetectedUrl(raw)
        assertEquals("https://en.wikipedia.org/wiki/Function_(mathematics)", sanitized)
    }

    @Test
    fun `given_urlWrappedInParentheses_when_sanitizing_then_trimsOnlyWrapper`() {
        val raw = "(https://en.wikipedia.org/wiki/Function_(mathematics))"
        val sanitized = sanitizeDetectedUrl(raw)
        assertEquals("https://en.wikipedia.org/wiki/Function_(mathematics)", sanitized)
    }

    @Test
    fun `given_urlFollowedByExtraClosingParen_when_parsing_then_keepsBalancedPart`() {
        val raw = "https://en.wikipedia.org/wiki/Function_(mathematics))"
        val parsed = parseExternalUrlCandidate(raw)

        requireNotNull(parsed)
        assertEquals("https://en.wikipedia.org/wiki/Function_(mathematics)", parsed.displayUrl)
        assertEquals("https://en.wikipedia.org/wiki/Function_(mathematics)", parsed.normalizedUrl)
    }

    @Test
    fun `given_bareDomainFileExtensions_when_normalizing_then_rejectsAllAsFilenames`() {
        // Archives
        assertNull(normalizeAndValidateExternalUrl("file.tar.gz"))
        assertNull(normalizeAndValidateExternalUrl("archive.zip"))
        // Source code files
        assertNull(normalizeAndValidateExternalUrl("script.py"))
        assertNull(normalizeAndValidateExternalUrl("Main.kt"))
        assertNull(normalizeAndValidateExternalUrl("App.java"))
        assertNull(normalizeAndValidateExternalUrl("index.js"))
        // Docs
        assertNull(normalizeAndValidateExternalUrl("readme.md"))
        assertNull(normalizeAndValidateExternalUrl("report.pdf"))
        // Multi-extension
        assertNull(normalizeAndValidateExternalUrl("app.debug.apk"))
    }

    @Test
    fun `given_versionedFilename_when_parsing_then_neitherFullNorTruncatedUrlReturned`() {
        // Regression: "456456.tar.gz" was being shown as
        // "456456.tar.g" because the retry loop dropped the 'z',
        // leaving TLD "g" which previously passed the single-char check.
        val raw = "456456.tar.gz"
        assertNull(parseExternalUrlCandidate(raw))
    }

    @Test
    fun `given_realDomainsAndUrlsWithFilePathSegments_when_normalizing_then_acceptsAll`() {
        // Real TLD domains must not be rejected
        assertEquals("https://example.com", normalizeAndValidateExternalUrl("example.com"))
        assertEquals("https://example.io", normalizeAndValidateExternalUrl("example.io"))
        // Explicit https:// with a file extension in the path is always valid
        assertEquals(
            "https://example.com/file.tar.gz",
            normalizeAndValidateExternalUrl("https://example.com/file.tar.gz")
        )
        assertEquals(
            "https://example.com/script.py",
            normalizeAndValidateExternalUrl("https://example.com/script.py")
        )
    }
}
