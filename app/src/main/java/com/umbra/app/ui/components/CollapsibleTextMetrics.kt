package com.umbra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R

private const val MAX_LINES_COLLAPSED = 6
private const val MAX_CHARS_COLLAPSED = 700
private const val MAX_CHARS_COLLAPSED_MULTILINE = 620
private const val MIN_CHARS_FOR_LINE_ONLY_EXPAND = 260

// Stand-in used only to measure how much a Lightning invoice or LNURL string should count toward
// the collapse budget below — both render as one compact card despite being hundreds of
// characters of bech32, so counting their literal length would collapse/truncate notes for no
// visually justified reason. Never itself shown; length is a rough "about one short line" estimate.
private const val INVOICE_BUDGET_PLACEHOLDER = "[lightning invoice]"

/**
 * Collapse/expand metrics for a note's body text, shared by [com.umbra.app.ui.feed.EventCard] (a
 * full note) and [QuotedNoteCard] (a compact embedded note) so both "Show more" behave
 * identically.
 */
internal data class TextRenderMetrics(
    val shouldShowExpandButton: Boolean,
    val collapsedText: String
)

/**
 * Lightning invoices are never split across the collapse cutoff: [computeTextRenderMetrics] first
 * decides whether collapsing is needed at all using a budget where each invoice span is measured
 * as [INVOICE_BUDGET_PLACEHOLDER] rather than its real length (see that constant's doc comment),
 * then — if collapsing *is* needed for other reasons — extends the real cutoff past any invoice
 * span it would otherwise land inside, so an invoice card is always rendered whole or not at all,
 * never as a truncated/corrupted partial match. See NostrTextRenderer's fullLightningInvoices
 * param for the matching fix on the parsing side.
 */
internal fun computeTextRenderMetrics(normalizedContent: String): TextRenderMetrics {
    if (normalizedContent.isBlank()) {
        return TextRenderMetrics(
            shouldShowExpandButton = false,
            collapsedText = ""
        )
    }

    val invoiceRanges = (
        LIGHTNING_INVOICE_REGEX.findAll(normalizedContent).map { it.range } +
            LNURL_REGEX.findAll(normalizedContent).map { it.range }
        ).sortedBy { it.first }.toList()
    val budgetContent = if (invoiceRanges.isEmpty()) {
        normalizedContent
    } else {
        buildString {
            var cursor = 0
            for (range in invoiceRanges) {
                append(normalizedContent, cursor, range.first)
                append(INVOICE_BUDGET_PLACEHOLDER)
                cursor = range.last + 1
            }
            append(normalizedContent, cursor, normalizedContent.length)
        }
    }

    val budgetLines = budgetContent.lines()
    val adaptiveCharLimit = if (budgetLines.size >= 4) {
        MAX_CHARS_COLLAPSED_MULTILINE
    } else {
        MAX_CHARS_COLLAPSED
    }
    val exceedsLineLimit = budgetLines.size > MAX_LINES_COLLAPSED
    val exceedsCharLimit = budgetContent.length > adaptiveCharLimit
    val shouldShowExpandButton = exceedsCharLimit ||
        (exceedsLineLimit && budgetContent.length > MIN_CHARS_FOR_LINE_ONLY_EXPAND)

    if (!shouldShowExpandButton) {
        return TextRenderMetrics(
            shouldShowExpandButton = false,
            collapsedText = normalizedContent
        )
    }

    // From here on, collapse against the REAL content/lines — budgetContent above exists only to
    // decide *whether* to collapse, not what the collapsed text itself should contain.
    val realLines = normalizedContent.lines()
    val collapsedByLines = realLines.take(MAX_LINES_COLLAPSED).joinToString("\n")
    var collapsedText = if (collapsedByLines.length > adaptiveCharLimit) {
        trimAtWordBoundary(collapsedByLines, adaptiveCharLimit)
    } else {
        collapsedByLines
    }

    val cutoffIndex = collapsedText.length
    val straddledInvoice = invoiceRanges.firstOrNull { cutoffIndex in (it.first + 1)..it.last }
    if (straddledInvoice != null) {
        collapsedText = normalizedContent.substring(0, straddledInvoice.last + 1)
    }

    return TextRenderMetrics(
        shouldShowExpandButton = true,
        collapsedText = collapsedText
    )
}

private fun trimAtWordBoundary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val hardCut = text.take(maxChars)
    val lastSpace = hardCut.lastIndexOf(' ')
    if (lastSpace < (maxChars * 0.55f).toInt()) {
        return hardCut
    }
    return hardCut.take(lastSpace)
}

/** "Show more"/"Show less" text toggle, shared by EventCard and QuotedNoteCard. */
@Composable
internal fun ShowMoreLessToggle(isExpanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = if (isExpanded) {
            stringResource(R.string.event_show_less)
        } else {
            stringResource(R.string.event_show_more)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = 8.dp, start = 4.dp)
            .clickable(onClick = onToggle)
    )
}
