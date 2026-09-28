package com.umbra.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanWebUrlTest {
    @Test
    fun `given web link with tracking params when cleaning then they are stripped and others kept`() {
        assertEquals(
            "https://example.com/article?id=42",
            cleanWebUrl("https://example.com/article?id=42&utm_source=nostr&utm_medium=social&fbclid=abc")
        )
    }

    @Test
    fun `given non web uri when cleaning then it is left untouched`() {
        val invoice = "lightning:lnbc10u1qqqqqqqzraexu"
        assertEquals(invoice, cleanWebUrl(invoice))
        assertEquals("bitcoin:bc1qxyz?amount=0.1", cleanWebUrl("bitcoin:bc1qxyz?amount=0.1"))
    }
}
