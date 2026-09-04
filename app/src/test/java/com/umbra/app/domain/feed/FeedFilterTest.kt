package com.umbra.app.domain.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFilterTest {

    @Test
    fun `given default filter when seeded then excludedContentPrefixes matches FilterDefaults`() {
        assertEquals(FilterDefaults.DEFAULT_EXCLUDED_CONTENT_PREFIXES, DefaultFeedFilters.DEFAULT.excludedContentPrefixes)
    }

    @Test
    fun `given a newly created filter when built then excludedContentPrefixes starts empty`() {
        val filter = DefaultFeedFilters.create(name = "Custom")

        assertTrue(filter.excludedContentPrefixes.isEmpty())
    }

    @Test
    fun `given a filter with excludedContentPrefixes removed when the user overrides it then the override is preserved`() {
        // Mirrors how FeedConfigScreen lets a user clear/edit any default exclusion — the
        // default is a starting point, not something baked in and unremovable.
        val userOverridden = DefaultFeedFilters.DEFAULT.copy(excludedContentPrefixes = emptySet())

        assertTrue(userOverridden.excludedContentPrefixes.isEmpty())
    }

    @Test
    fun `given multiple active filters when merging then excludedContentPrefixes is the union`() {
        val a = DefaultFeedFilters.create(name = "A").copy(excludedContentPrefixes = setOf("nlogpost:"))
        val b = DefaultFeedFilters.create(name = "B").copy(excludedContentPrefixes = setOf("ncomment:"))

        val merged = mergeActiveFeedFilters(listOf(a, b))

        assertEquals(setOf("nlogpost:", "ncomment:"), merged.excludedContentPrefixes)
    }

    @Test
    fun `given no active filters when merging then falls back to DEFAULT`() {
        val merged = mergeActiveFeedFilters(emptyList())

        assertEquals(DefaultFeedFilters.DEFAULT.excludedContentPrefixes, merged.excludedContentPrefixes)
    }

    @Test
    fun `given a filter with a persisted-looking id when merging then the merged id is the fixed synthetic id, never the input's`() {
        val persisted = DefaultFeedFilters.create(name = "Persisted").copy(id = "filter_1700000000000_1234")

        val merged = mergeActiveFeedFilters(listOf(persisted))

        // The merged result always carries the fixed synthetic id, regardless of what was merged
        // in — a lookup keyed on this id against a real, persisted filter table can never match.
        assertEquals("merged_active", merged.id)
        assertTrue(merged.id != persisted.id)
        // The single-filter case still carries the input's name through, just not its id.
        assertEquals(persisted.name, merged.name)
    }
}
