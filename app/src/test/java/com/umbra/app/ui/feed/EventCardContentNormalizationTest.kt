package com.umbra.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class EventCardContentNormalizationTest {

    @Test
    fun `given content with leading and trailing whitespace when normalizing then returns trimmed content`() {
        val raw = "\n\n   hello nostr   \t"

        val normalized = normalizeNoteContentForDisplay(raw)

        assertEquals("hello nostr", normalized)
    }

    @Test
    fun `given content with internal line breaks when normalizing then preserves internal formatting`() {
        val raw = "\n  first line\nsecond line  \n"

        val normalized = normalizeNoteContentForDisplay(raw)

        assertEquals("first line\nsecond line", normalized)
    }
}
