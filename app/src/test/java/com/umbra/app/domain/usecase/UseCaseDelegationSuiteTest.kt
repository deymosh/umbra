package com.umbra.app.domain.usecase

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.FeedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UseCaseDelegationSuiteTest {

    @Test
    fun `given_relayRepository_when_executingUseCases_then_delegatesAndExposes`() = runBlocking {
        val relayA = Relay(id = "1", url = "wss://one.example")
        val relayB = Relay(id = "2", url = "wss://two.onion", isOnion = true)
        val repo = FakeRelayRepository(
            allRelaysFlow = flowOf(listOf(relayA, relayB))
        )

        val all = GetAllRelaysUseCase(repo)().first()

        assertEquals(listOf(relayA, relayB), all)

        AddRelayUseCase(repo)(relayA)
        UpdateRelayUseCase(repo)(relayB)
        RemoveRelayUseCase(repo)("1")

        assertEquals(relayA, repo.addedRelay)
        assertEquals(relayB, repo.updatedRelay)
        assertEquals("1", repo.removedRelayId)
    }

    @Test
    fun `given_feedRepository_when_executingUseCases_then_delegatesAndExposes`() = runBlocking {
        val base = sampleFilter("f1")
        val repo = FakeFeedRepository(
            allFiltersFlow = flowOf(listOf(base)),
            activeFiltersFlow = flowOf(listOf(base.copy(isActive = true)))
        )

        val all = GetAllFiltersUseCase(repo)().first()
        val active = GetActiveFilterUseCase(repo)().first()

        assertEquals(1, all.size)
        assertEquals("f1", all.first().id)
        assertEquals(1, active.size)
        assertTrue(active.first().isActive)

        val updated = base.copy(name = "Updated")
        AddFeedFilterUseCase(repo)(base)
        UpdateFeedFilterUseCase(repo)(updated)
        RemoveFeedFilterUseCase(repo)("f1")
        SetFilterActiveUseCase(repo)("f1", active = true)
        AddMutedAuthorUseCase(repo)("f1", "a".repeat(64))
        RemoveMutedAuthorUseCase(repo)("f1", "a".repeat(64))
        ResetFeedFiltersUseCase(repo)()

        assertEquals(base, repo.addedFilter)
        assertEquals(updated, repo.updatedFilter)
        assertEquals("f1", repo.removedFilterId)
        assertEquals("f1" to true, repo.filterActiveState)
        assertEquals("f1" to "a".repeat(64), repo.mutedAdded)
        assertEquals("f1" to "a".repeat(64), repo.mutedRemoved)
        assertTrue(repo.resetCalled)
    }

    private fun sampleFilter(id: String): FeedFilter {
        return FeedFilter(
            id = id,
            name = "Home",
            hideNsfw = true,
            mutedPubkeys = emptySet(),
            excludedTags = emptySet(),
            excludedHashtags = emptySet(),
            isActive = false
        )
    }

    private class FakeRelayRepository(
        private val allRelaysFlow: Flow<List<Relay>>
    ) : RelayRepository {
        var addedRelay: Relay? = null
        var updatedRelay: Relay? = null
        var removedRelayId: String? = null

        override fun getAllRelays(): Flow<List<Relay>> = allRelaysFlow
        override suspend fun getRelayById(id: String): Relay? = null
        override suspend fun addRelay(relay: Relay) { addedRelay = relay }
        override suspend fun updateRelay(relay: Relay) { updatedRelay = relay }
        override suspend fun removeRelay(id: String) { removedRelayId = id }
        override suspend fun bootstrapDefaultsOnFirstLogin() = Unit
        override suspend fun clearUserRelayConfig() = Unit
    }

    private class FakeFeedRepository(
        private val allFiltersFlow: Flow<List<FeedFilter>>,
        private val activeFiltersFlow: Flow<List<FeedFilter>>
    ) : FeedRepository {
        var addedFilter: FeedFilter? = null
        var updatedFilter: FeedFilter? = null
        var removedFilterId: String? = null
        var filterActiveState: Pair<String, Boolean>? = null
        var mutedAdded: Pair<String, String>? = null
        var mutedRemoved: Pair<String, String>? = null
        var resetCalled: Boolean = false

        override fun getAllFilters(): Flow<List<FeedFilter>> = allFiltersFlow
        override fun getActiveFilters(): Flow<List<FeedFilter>> = activeFiltersFlow
        override suspend fun getFilterById(id: String): FeedFilter? = null
        override suspend fun addFilter(filter: FeedFilter) { addedFilter = filter }
        override suspend fun updateFilter(filter: FeedFilter) { updatedFilter = filter }
        override suspend fun removeFilter(id: String) { removedFilterId = id }
        override suspend fun setFilterActive(id: String, active: Boolean) { filterActiveState = id to active }
        override suspend fun addMutedAuthor(filterId: String, pubkey: String) { mutedAdded = filterId to pubkey }
        override suspend fun removeMutedAuthor(filterId: String, pubkey: String) { mutedRemoved = filterId to pubkey }
        override suspend fun updateMutedAuthors(filterId: String, mutedPubkeys: Set<String>) = Unit
        override suspend fun resetToDefaults() { resetCalled = true }
        override suspend fun ensureDefaultFiltersSeeded() = Unit
    }
}

