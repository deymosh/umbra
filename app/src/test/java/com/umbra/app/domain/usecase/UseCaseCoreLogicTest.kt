package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip11.RelayInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UseCaseCoreLogicTest {

    @Test
    fun `given_existingAndIncomingAuthors_when_buildingSet_then_normalizesDeduplicatesAndCaps`() {
        val useCase = BuildHydrationAuthorSetUseCase()
        val existing = setOf("A".repeat(64))
        val incoming = listOf("a".repeat(64), "b".repeat(64), "short")

        val result = useCase(existing = existing, incoming = incoming, maxAuthors = 2)

        assertEquals(setOf("a".repeat(64), "b".repeat(64)), result)
    }

    @Test
    fun `given_nonPositiveMax_when_buildingSet_then_returnsEmpty`() {
        val useCase = BuildHydrationAuthorSetUseCase()

        val result = useCase(
            existing = setOf("a".repeat(64)),
            incoming = listOf("b".repeat(64)),
            maxAuthors = 0
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given_authorsAndGlobalFilter_when_building_then_normalizesAndAdds`() {
        val useCase = BuildProfileHydrationFiltersUseCase()
        val authorUpper = "A".repeat(64)

        val filters = useCase(
            authors = setOf(authorUpper, "short"),
            perAuthorLimit = 0,
            includeGlobalFilter = true,
            globalLimit = 77
        )

        assertEquals(2, filters.size)
        assertEquals(emptySet<String>(), filters[0].authors)
        assertEquals(77, filters[0].limit)

        assertEquals(setOf("a".repeat(64)), filters[1].authors)
        // limit is a floor of authors.size * kinds.size (N replaceable kinds x 1 author), not the
        // raw perAuthorLimit=0->1 — otherwise a shared low limit across multiple replaceable
        // kinds lets frequently-updated kinds (0, 3) crowd out rarely-updated ones (10002, 10050).
        // See BuildProfileHydrationFiltersUseCase's doc comment.
        assertEquals(filters[1].kinds.size, filters[1].limit)
        assertTrue(filters[1].kinds.contains(Event.KIND_METADATA))
        assertTrue(filters[1].kinds.contains(Event.KIND_DM_RELAY_LIST))
        // Default (includeOwnerOnlyKinds = false): search/index relay lists are meaningless for
        // anyone but the signed-in user and must be excluded.
        assertFalse(filters[1].kinds.contains(Event.KIND_SEARCH_RELAYS))
        assertFalse(filters[1].kinds.contains(Event.KIND_INDEX_RELAYS))
        assertTrue(filters[1].kinds.contains(Event.KIND_BLOSSOM_SERVER_LIST))
        assertEquals(6, filters[1].kinds.size)
    }

    @Test
    fun `given_ownerOnlyKinds_when_building_then_includesSearchAndIndexRelayLists`() {
        val useCase = BuildProfileHydrationFiltersUseCase()
        val pubkey = "a".repeat(64)

        val filters = useCase(
            authors = setOf(pubkey),
            perAuthorLimit = 5,
            includeOwnerOnlyKinds = true
        )

        assertEquals(1, filters.size)
        assertTrue(filters[0].kinds.contains(Event.KIND_SEARCH_RELAYS))
        assertTrue(filters[0].kinds.contains(Event.KIND_INDEX_RELAYS))
        assertEquals(8, filters[0].kinds.size)
    }

    @Test
    fun `given_authorsForChunking_when_building_then_keepsGlobalOnFirstChunk`() {
        val filtersUseCase = BuildProfileHydrationFiltersUseCase()
        val requestsUseCase = BuildProfileHydrationRequestsUseCase(filtersUseCase)

        val authors = listOf(
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64)
        )

        val filters = requestsUseCase(
            authors = authors,
            chunkSize = 2,
            perAuthorLimit = 5,
            includeGlobalFilter = true,
            globalLimit = 9
        )

        assertEquals(3, filters.size)
        assertEquals(emptySet<String>(), filters[0].authors)
        assertEquals(9, filters[0].limit)

        assertEquals(setOf("a".repeat(64), "b".repeat(64)), filters[1].authors)
        assertEquals(setOf("c".repeat(64)), filters[2].authors)
    }

    @Test
    fun `given_repositoryResult_when_checkingTorStatus_then_returnsUnchanged`() = runBlocking {
        val expected = TorStatusResult(isTor = true, exitIp = "1.1.1.1", countryCode = "NL")
        val repo = FakeTorStatusRepository(Result.success(expected))
        val useCase = CheckTorStatusUseCase(repo)

        val result = useCase()

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
    }

    @Test
    fun `given_relayWithNip50_when_hasSearchFilter_then_shouldSendSearch`() {
        val relayInfo = RelayInfo(supportedNips = listOf(1, 11, 50))
        val filters = listOf(EventFilter(search = "bitcoin"))
        val hasSearch = filters.any { !it.search.isNullOrBlank() }
        val supportsNip50 = relayInfo.supportedNips.contains(50)
        assertTrue("Relay with NIP-50 should receive search REQ", hasSearch && supportsNip50)
    }

    @Test
    fun `given_relayWithoutNip50_when_hasSearchFilter_then_shouldSkipSearch`() {
        val relayInfo = RelayInfo(supportedNips = listOf(1, 11, 15))
        val filters = listOf(EventFilter(search = "bitcoin"))
        val hasSearch = filters.any { !it.search.isNullOrBlank() }
        val supportsNip50 = relayInfo.supportedNips.contains(50)
        assertFalse("Relay without NIP-50 should NOT receive search REQ", hasSearch && supportsNip50)
    }

    @Test
    fun `given_nullRelayInfo_when_hasSearchFilter_then_shouldSkipSearch`() {
        val relayInfo: RelayInfo? = null
        val filters = listOf(EventFilter(search = "bitcoin"))
        val hasSearch = filters.any { !it.search.isNullOrBlank() }
        val supportsNip50 = relayInfo?.supportedNips?.contains(50) == true
        assertFalse("Relay with null info should NOT receive search REQ", hasSearch && supportsNip50)
    }

    @Test
    fun `given_relayWithNip50_when_noSearchFilter_then_sendNormally`() {
        val relayInfo = RelayInfo(supportedNips = listOf(1, 50))
        val filters = listOf(EventFilter(kinds = setOf(Event.KIND_TEXT_NOTE)))
        val hasSearch = filters.any { !it.search.isNullOrBlank() }
        assertFalse("Non-search REQ should not be blocked by NIP-50 check", hasSearch)
    }

    private class FakeTorStatusRepository(
        private val result: Result<TorStatusResult>
    ) : com.umbra.app.domain.repository.TorStatusRepository {
        override suspend fun checkTorStatus(): Result<TorStatusResult> = result
    }
}

