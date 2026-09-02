package com.umbra.app.ui.feed

import com.umbra.app.ui.components.computeTextRenderMetrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCardMetricsTest {

    @Test
    fun `given blank content when computing metrics then no expand and empty collapsed`() {
        val result = computeTextRenderMetrics("   ")

        assertFalse(result.shouldShowExpandButton)
        assertTrue(result.collapsedText.isEmpty())
    }

    @Test
    fun `given short single line content when computing metrics then no expand and same text`() {
        val content = "short note"

        val result = computeTextRenderMetrics(content)

        assertFalse(result.shouldShowExpandButton)
        assertTrue(result.collapsedText == content)
    }

    @Test
    fun `given long multiline content when computing metrics then enables expand`() {
        val line = "lorem ipsum dolor sit amet consectetur adipiscing elit"
        val content = List(8) { line }.joinToString("\n")

        val result = computeTextRenderMetrics(content)

        assertTrue(result.shouldShowExpandButton)
        assertTrue(result.collapsedText.length <= content.length)
    }

    // Synthetic bech32-shaped string matching LIGHTNING_INVOICE_REGEX's structure — not a real
    // invoice, just long enough (300+ chars) to stand in for one in these truncation-budget tests.
    private fun fakeInvoice(chars: Int = 300): String {
        val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        return "lnbc10u1p" + alphabet.repeat((chars / alphabet.length) + 1).take(chars)
    }

    @Test
    fun `given short caption with one long invoice when computing metrics then does not enable expand`() {
        val content = "Here's an invoice: ${fakeInvoice()}"

        val result = computeTextRenderMetrics(content)

        assertFalse(result.shouldShowExpandButton)
        assertTrue(result.collapsedText == content)
    }

    @Test
    fun `given long multiline text with invoice straddling the cutoff when computing metrics then invoice is kept whole`() {
        val fillerLine = "A".repeat(100)
        val invoice = fakeInvoice(300)
        val lines = List(5) { fillerLine } + listOf(invoice) + List(2) { fillerLine }
        val content = lines.joinToString("\n")

        val result = computeTextRenderMetrics(content)

        assertTrue(result.shouldShowExpandButton)
        assertTrue(result.collapsedText.endsWith(invoice))
    }
}
