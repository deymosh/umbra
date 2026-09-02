package com.umbra.app.data.repository.policy

import com.umbra.app.domain.nip01.EventFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxInboxRelaySincePolicyTest {

    @Test
    fun `given no per-relay watermark when overriding since then filters are returned unchanged`() {
        val filters = listOf(EventFilter(since = 100L), EventFilter(since = null))

        val result = OutboxInboxRelaySincePolicy.overrideSince(filters, perRelaySince = null)

        assertEquals(filters, result)
    }

    @Test
    fun `given a per-relay watermark when overriding since then filters with a since are overridden`() {
        val filters = listOf(EventFilter(since = 100L), EventFilter(since = 200L))

        val result = OutboxInboxRelaySincePolicy.overrideSince(filters, perRelaySince = 999L)

        assertEquals(listOf(999L, 999L), result.map { it.since })
    }

    @Test
    fun `given a per-relay watermark when overriding since then a filter without a since gains one`() {
        val filters = listOf(EventFilter(since = null))

        val result = OutboxInboxRelaySincePolicy.overrideSince(filters, perRelaySince = 999L)

        assertEquals(999L, result.single().since)
    }

    @Test
    fun `given a filter carrying tagFilters when overriding since then it is left unchanged even with a per-relay watermark`() {
        val filters = listOf(EventFilter(since = 100L, tagFilters = mapOf("e" to setOf("abc"))))

        val result = OutboxInboxRelaySincePolicy.overrideSince(filters, perRelaySince = 999L)

        assertEquals(100L, result.single().since)
    }
}
