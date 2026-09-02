package com.umbra.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProfileHydrationFiltersUseCaseTest {

    private val useCase = BuildProfileHydrationFiltersUseCase()
    private val author = "a".repeat(64)

    @Test
    fun `given_noSince_when_buildingAuthorFilter_then_sinceIsNull`() {
        val filters = useCase(authors = setOf(author), perAuthorLimit = 5)

        assertEquals(1, filters.size)
        assertEquals(null, filters.single().since)
    }

    @Test
    fun `given_since_when_buildingAuthorFilter_then_sinceIsApplied`() {
        val since = 1_700_000_000L

        val filters = useCase(authors = setOf(author), perAuthorLimit = 5, since = since)

        assertEquals(1, filters.size)
        assertEquals(since, filters.single().since)
    }

    @Test
    fun `given_since_when_buildingGlobalAndAuthorFilters_then_bothCarrySince`() {
        val since = 1_700_000_000L

        val filters = useCase(
            authors = setOf(author),
            perAuthorLimit = 5,
            includeGlobalFilter = true,
            since = since
        )

        assertEquals(2, filters.size)
        assertTrue(filters.all { it.since == since })
    }
}
