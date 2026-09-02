package com.umbra.app.data.repository.policy

import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip67.EoseCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRelaySincePolicyTest {

    @Test
    fun `given no per-relay watermark when overriding since then filters are returned unchanged`() {
        val filters = listOf(EventFilter(since = 100L), EventFilter(since = 200L))

        val result = FeedRelaySincePolicy.overrideSince(filters, perRelaySince = null)

        assertEquals(filters, result)
    }

    @Test
    fun `given a per-relay watermark when overriding since then filters with a since are overridden`() {
        val filters = listOf(EventFilter(since = 100L), EventFilter(since = 200L))

        val result = FeedRelaySincePolicy.overrideSince(filters, perRelaySince = 999L)

        assertEquals(listOf(999L, 999L), result.map { it.since })
    }

    @Test
    fun `given a per-relay watermark when overriding since then filters without a since are left alone`() {
        val filters = listOf(EventFilter(since = null))

        val result = FeedRelaySincePolicy.overrideSince(filters, perRelaySince = 999L)

        assertNull(result.single().since)
    }

    @Test
    fun `given a filter carrying tagFilters when overriding since then it is left unchanged even with a per-relay watermark`() {
        val filters = listOf(EventFilter(since = 100L, tagFilters = mapOf("e" to setOf("abc"))))

        val result = FeedRelaySincePolicy.overrideSince(filters, perRelaySince = 999L)

        assertEquals(100L, result.single().since)
    }

    @Test
    fun `given a mix of tagged and untagged filters when overriding since then only the untagged filter is overridden`() {
        val filters = listOf(
            EventFilter(since = 100L),
            EventFilter(since = 200L, tagFilters = mapOf("e" to setOf("abc")))
        )

        val result = FeedRelaySincePolicy.overrideSince(filters, perRelaySince = 999L)

        assertEquals(listOf(999L, 200L), result.map { it.since })
    }

    @Test
    fun `given more completeness hint when deciding whether to advance watermark then does not advance`() {
        // NIP-67: a relay reporting it truncated the result set must not have its watermark
        // pushed to now — that would silently skip whatever it didn't send.
        assertFalse(FeedRelaySincePolicy.shouldAdvanceWatermark(EoseCompleteness.MORE))
    }

    @Test
    fun `given finish completeness hint when deciding whether to advance watermark then advances`() {
        assertTrue(FeedRelaySincePolicy.shouldAdvanceWatermark(EoseCompleteness.FINISH))
    }

    @Test
    fun `given unspecified completeness hint when deciding whether to advance watermark then advances`() {
        // The overwhelming majority of relays don't send NIP-67 hints at all — this must keep
        // today's pre-NIP-67 behavior unchanged, a regression guard for the common case.
        assertTrue(FeedRelaySincePolicy.shouldAdvanceWatermark(EoseCompleteness.UNSPECIFIED))
    }
}
