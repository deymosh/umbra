package com.umbra.app.domain.usecase

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProfileHydrationRequestsUseCaseTest {

    private val useCase = BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase())

    @Test
    fun `given_noSince_when_buildingChunkedRequests_then_everyFilterHasNullSince`() {
        val authors = (0 until 5).map { it.toString().repeat(64) }

        val filters = useCase(authors = authors, chunkSize = 2, perAuthorLimit = 5)

        assertTrue(filters.isNotEmpty())
        assertTrue(filters.all { it.since == null })
    }

    @Test
    fun `given_since_when_buildingChunkedRequests_then_everyChunkCarriesSince`() {
        val authors = (0 until 5).map { it.toString().repeat(64) }
        val since = 1_700_000_000L

        val filters = useCase(authors = authors, chunkSize = 2, perAuthorLimit = 5, since = since)

        assertTrue(filters.isNotEmpty())
        assertTrue(filters.all { it.since == since })
    }
}
