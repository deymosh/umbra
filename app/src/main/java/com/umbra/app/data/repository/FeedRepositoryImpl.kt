package com.umbra.app.data.repository

import com.umbra.app.data.db.dao.FeedFilterDao
import com.umbra.app.data.db.mapper.toDomain
import com.umbra.app.data.db.mapper.toEntity
import com.umbra.app.domain.feed.DefaultFeedFilters
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.repository.FeedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Room-backed implementation of FeedRepository (SSoT).
 */
@Singleton
class FeedRepositoryImpl @Inject constructor(
    @Named("encrypted") private val feedFilterDao: FeedFilterDao
) : FeedRepository {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repoScope.launch {
            ensureDefaultFiltersSeeded()
        }
    }

    // flowOn(IO): toDomain() (FeedFilterMapper) JSON-decodes three fields per filter
    // (mutedPubkeysJson/excludedTagsJson/excludedHashtagsJson) — with no flowOn this ran on
    // whatever dispatcher collected the flow, which for every collector here (FeedConfigViewModel,
    // FeedViewModel, NostrSessionManager) is viewModelScope/Main.immediate. Same bug shape already
    // fixed for RelayRepositoryImpl.getAllRelays().
    override fun getAllFilters(): Flow<List<FeedFilter>> =
        feedFilterDao.observeAllFilters()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.IO)

    // No synthetic fallback filter when this is empty — the user is allowed to have zero active
    // filters (nothing is mandatory). Callers (FeedViewModel.mergeFilters/computedFeedFlow,
    // NostrSessionManager) each fall back to DefaultFeedFilters.DEFAULT on an empty list, so zero
    // active filters shows the general default feed rather than nothing.
    override fun getActiveFilters(): Flow<List<FeedFilter>> =
        feedFilterDao.observeActiveFilters()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.IO)

    // withContext(IO): same reasoning as getAllFilters/getActiveFilters above — toDomain()
    // JSON-decodes three fields, which would otherwise run on this suspend fun's caller's own
    // dispatcher (Main.immediate for every current call site).
    override suspend fun getFilterById(id: String): FeedFilter? = withContext(Dispatchers.IO) {
        feedFilterDao.getFilterById(id)?.toDomain()
    }

    override suspend fun addFilter(filter: FeedFilter) {
        if (filter.isActive) {
            feedFilterDao.insertFilter(filter.toEntity().copy(isActive = true, updatedAtMillis = System.currentTimeMillis()))
            return
        }
        feedFilterDao.insertFilter(filter.toEntity().copy(updatedAtMillis = System.currentTimeMillis()))
    }

    override suspend fun updateFilter(filter: FeedFilter) {
        val exists = feedFilterDao.getFilterById(filter.id) != null
        if (!exists) return

        feedFilterDao.insertFilter(filter.toEntity().copy(updatedAtMillis = System.currentTimeMillis()))
    }

    // Deleting the (or an) active filter simply leaves however many filters remain active —
    // possibly zero. No forced fallback: the user is allowed to end up with no active filter.
    override suspend fun removeFilter(id: String) {
        feedFilterDao.deleteFilterById(id)
    }

    override suspend fun setFilterActive(id: String, active: Boolean) {
        feedFilterDao.setFilterActive(id, active, System.currentTimeMillis())
    }

    override suspend fun addMutedAuthor(filterId: String, pubkey: String) {
        val filter = getFilterById(filterId) ?: return
        val newMuted = filter.mutedPubkeys.toMutableSet()
        newMuted.add(pubkey)
        updateFilter(filter.copy(mutedPubkeys = newMuted))
    }

    override suspend fun removeMutedAuthor(filterId: String, pubkey: String) {
        val filter = getFilterById(filterId) ?: return
        val newMuted = filter.mutedPubkeys.toMutableSet()
        newMuted.remove(pubkey)
        updateFilter(filter.copy(mutedPubkeys = newMuted))
    }

    override suspend fun updateMutedAuthors(filterId: String, mutedPubkeys: Set<String>) {
        val filter = getFilterById(filterId) ?: return
        updateFilter(filter.copy(mutedPubkeys = mutedPubkeys))
    }

    override suspend fun resetToDefaults() {
        feedFilterDao.deleteAll()
        feedFilterDao.insertFilters(defaultFilters().map { it.toEntity() })
        feedFilterDao.setFilterActive(DefaultFeedFilters.DEFAULT.id, true, System.currentTimeMillis())
    }

    override suspend fun ensureDefaultFiltersSeeded() {
        val count = feedFilterDao.countFilters()
        if (count == 0) {
            feedFilterDao.insertFilters(defaultFilters().map { it.toEntity() })
            feedFilterDao.setFilterActive(DefaultFeedFilters.DEFAULT.id, true, System.currentTimeMillis())
            return
        }
    }

    private fun defaultFilters(): List<FeedFilter> = listOf(
        DefaultFeedFilters.DEFAULT.copy(isActive = true)
    )
}

