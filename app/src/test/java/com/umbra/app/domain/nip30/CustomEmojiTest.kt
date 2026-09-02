package com.umbra.app.domain.nip30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEmojiTest {

    @Test
    fun `given emoji tags when extracting then keys map by shortcode`() {
        val tags = listOf(
            listOf("emoji", "zap", "https://cdn/zap.png"),
            listOf("emoji", "ok", "https://cdn/ok.png")
        )

        val parsed = extractCustomEmojis(tags)

        assertEquals(2, parsed.size)
        assertEquals("https://cdn/zap.png", parsed["zap"]?.url)
        assertEquals("https://cdn/ok.png", parsed["ok"]?.url)
    }

    @Test
    fun `given emoji tags with mixed schemes when extracting then keeps only http s urls`() {
        val tags = listOf(
            listOf("emoji", "smile", "https://cdn.example/smile.png"),
            listOf("emoji", "bad", "file:///tmp/a.png"),
            listOf("emoji", "", "https://cdn.example/empty.png")
        )

        val emojis = extractCustomEmojis(tags)

        assertEquals(1, emojis.size)
        assertTrue(emojis.containsKey("smile"))
    }

    @Test
    fun `given duplicate shortcode when extracting then last tag wins`() {
        val tags = listOf(
            listOf("emoji", "zap", "https://cdn/zap-v1.png"),
            listOf("emoji", "zap", "https://cdn/zap-v2.png")
        )

        val parsed = extractCustomEmojis(tags)

        assertEquals(1, parsed.size)
        assertEquals("https://cdn/zap-v2.png", parsed["zap"]?.url)
    }

    @Test
    fun `given non emoji tags when extracting then ignores them`() {
        val tags = listOf(
            listOf("p", "a".repeat(64)),
            listOf("emoji", "ok", "https://cdn/ok.png")
        )

        val parsed = extractCustomEmojis(tags)

        assertEquals(1, parsed.size)
        assertTrue(parsed.containsKey("ok"))
    }
}
