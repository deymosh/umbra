package com.umbra.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogScrubberTest {

    @Test
    fun `given_urlOrBlank_when_scrubbing_then_redactsSchemeOrReturnsDefault`() {
        assertEquals("http://[redacted]", LogScrubber.scrubUrlForLogs("http://example.com/path"))
        assertEquals("wss://[redacted]", LogScrubber.scrubUrlForLogs("wss://relay.example"))
        assertEquals("[url]", LogScrubber.scrubUrlForLogs(" "))
    }

    @Test
    fun `given_pubkeyOrNull_when_scrubbing_then_truncatesOrReturnsDefault`() {
        assertEquals("[pubkey]", LogScrubber.scrubPubkeyForLogs(null))
        assertEquals("12345678...", LogScrubber.scrubPubkeyForLogs("1234567890abcdef"))
    }

    @Test
    fun `given_messageWithSensitiveData_when_scrubbing_then_redactsAll`() {
        val message = "Connect wss://relay.example at 127.0.0.1:9050 with npub1qqqq and key ${"a".repeat(64)} and nostrsigner:payload"

        val scrubbed = LogScrubber.scrubMessageForLogs(message)

        assertTrue(scrubbed.contains("[url]"))
        assertTrue(scrubbed.contains("[endpoint]"))
        assertTrue(scrubbed.contains("[nostr-id]"))
        assertTrue(scrubbed.contains("[hex]"))
        assertTrue(scrubbed.contains("nostrsigner:[redacted]"))
    }

    @Test
    fun `given_throwableWithSensitiveMessage_when_scrubbing_then_extractsAndRedacts`() {
        val scrubbed = LogScrubber.scrubThrowableMessageForLogs(
            IllegalStateException("failed at https://example.com")
        )

        assertTrue(scrubbed.contains("[url]"))
        assertEquals("unknown", LogScrubber.scrubMessageForLogs(""))
    }
}
