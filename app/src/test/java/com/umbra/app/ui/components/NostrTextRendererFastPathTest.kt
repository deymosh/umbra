package com.umbra.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrTextRendererFastPathTest {

    @Test
    fun `given plain text when evaluating fast path then returns true`() {
        assertTrue(
            shouldUseSimpleTextPath(
                text = "hello world from umbra",
                tags = emptyList()
            )
        )
    }

    @Test
    fun `given text with external url when evaluating fast path then returns false`() {
        assertFalse(
            shouldUseSimpleTextPath(
                text = "check https://example.com",
                tags = emptyList()
            )
        )
    }

    @Test
    fun `given text with mention marker when evaluating fast path then returns false`() {
        assertFalse(
            shouldUseSimpleTextPath(
                text = "hey @alice",
                tags = emptyList()
            )
        )
    }

    @Test
    fun `given a lightning invoice with no other trigger when evaluating fast path then returns false`() {
        assertFalse(
            shouldUseSimpleTextPath(
                text = "zap me\nlnbc330u1" + "q".repeat(40),
                tags = emptyList()
            )
        )
    }

    @Test
    fun `given a bare lnurl with no other trigger when evaluating fast path then returns false`() {
        assertFalse(
            shouldUseSimpleTextPath(
                text = "withdraw here lnurl1dp68gurn8ghj7um9wfmxjcm99e3k7mf0v9cxj0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexejxxepnxscrvwfnv9nxzcn9xq6xyefhvgcxxcmyxymnserxfq5fns",
                tags = emptyList()
            )
        )
    }

    @Test
    fun `given json looking content when evaluating fast path then returns false`() {
        assertFalse(
            shouldUseSimpleTextPath(
                text = "{\"kind\":1}",
                tags = emptyList()
            )
        )
    }

    @Test
    fun `given custom emoji tags when evaluating fast path then returns false`() {
        val tags = listOf(listOf("emoji", "wave", "https://cdn.example/wave.png"))

        assertFalse(
            shouldUseSimpleTextPath(
                text = "hello :wave:",
                tags = tags
            )
        )
    }
}
