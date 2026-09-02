package com.umbra.app.domain.repository

import com.umbra.app.domain.feed.FeedFilter
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for feed configuration.
 * Manages feed filters and display preferences.
 */
interface FeedRepository {

    /**
     * Get all feed filters as a stream of updates
     */
    fun getAllFilters(): Flow<List<FeedFilter>>

    /**
     * Get the currently active filters (multiple allowed)
     */
    fun getActiveFilters(): Flow<List<FeedFilter>>

    /**
     * Get a specific filter by ID
     */
    suspend fun getFilterById(id: String): FeedFilter?

    /**
     * Add a new feed filter
     */
    suspend fun addFilter(filter: FeedFilter)

    /**
     * Update an existing filter
     */
    suspend fun updateFilter(filter: FeedFilter)

    /**
     * Remove a filter by ID
     */
    suspend fun removeFilter(id: String)

    /**
     * Set a single filter active/inactive
     */
    suspend fun setFilterActive(id: String, active: Boolean)

    /**
     * Create a new muted pubkey entry
     */
    suspend fun addMutedAuthor(filterId: String, pubkey: String)

    /**
     * Remove a muted pubkey
     */
    suspend fun removeMutedAuthor(filterId: String, pubkey: String)

    suspend fun updateMutedAuthors(filterId: String, mutedPubkeys: Set<String>)

    /**
     * Reset to default filters
     */
    suspend fun resetToDefaults()

    /**
     * Idempotently seeds the default feed filter set if none exist yet — safe to call any number
     * of times (a no-op once at least one filter exists). Runs once automatically at process start
     * (see FeedRepositoryImpl's init block); also called explicitly at session activation so a
     * logout (which wipes the feed_filter table) followed by a same-process re-login re-seeds it,
     * since the process-init path only ever fires once per process lifetime.
     */
    suspend fun ensureDefaultFiltersSeeded()
}
