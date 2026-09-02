package com.umbra.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingTokenSanitizerTest {

    @Test
    fun `given clean url when sanitize text then returns unchanged`() {
        val input = "see https://example.com/page?a=1"

        val output = TrackingTokenSanitizer.sanitizeText(input)

        assertEquals(input, output)
    }

    @Test
    fun `given url with utm params when sanitize text then removes tracking params`() {
        val input = "watch https://www.youtube.com/watch?v=abc123&utm_source=newsletter&si=token"

        val output = TrackingTokenSanitizer.sanitizeText(input)

        assertEquals("watch https://www.youtube.com/watch?v=abc123", output)
    }

    @Test
    fun `given google redirect url when sanitize text then unwraps destination`() {
        val input = "link https://www.google.com/url?q=https%3A%2F%2Fpypi.org%2Fproject%2FUnalix%2F&utm_source=x"

        val output = TrackingTokenSanitizer.sanitizeText(input)

        assertEquals("link https://pypi.org/project/Unalix/", output)
    }

    @Test
    fun `given twitter s param when sanitize text then removes host specific tracking param`() {
        val input = "x https://x.com/user/status/123456?s=20&t=abc"

        val output = TrackingTokenSanitizer.sanitizeText(input)

        assertEquals("x https://x.com/user/status/123456", output)
    }

    @Test
    fun `given youtu be mobile is token when sanitize text then removes is param`() {
        val input = "mobile https://youtu.be/abc123?is=mobileToken&t=30"

        val output = TrackingTokenSanitizer.sanitizeText(input)

        assertEquals("mobile https://youtu.be/abc123?t=30", output)
    }

    @Test
    fun `given youtu be mobile si token when sanitize text then removes si param`() {
        val input = "mobile https://youtu.be/abc123?si=mobileToken&t=30"

        val output = TrackingTokenSanitizer.sanitizeText(input)

        assertEquals("mobile https://youtu.be/abc123?t=30", output)
    }

    @Test
    fun `given text with tracking url when sanitize with result then marks tracking as removed`() {
        val input = "watch https://www.youtube.com/watch?v=abc123&utm_source=newsletter"

        val result = TrackingTokenSanitizer.sanitizeTextWithResult(input)

        assertTrue(result.removedTrackingTokens)
        assertEquals("watch https://www.youtube.com/watch?v=abc123", result.sanitizedText)
    }

    @Test
    fun `given clean text when sanitize with result then marks tracking as not removed`() {
        val input = "watch https://example.com/page?foo=bar"

        val result = TrackingTokenSanitizer.sanitizeTextWithResult(input)

        assertFalse(result.removedTrackingTokens)
        assertEquals(input, result.sanitizedText)
    }
}