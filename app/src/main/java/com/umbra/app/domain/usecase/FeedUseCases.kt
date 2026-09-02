package com.umbra.app.domain.usecase

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.repository.FeedRepository
import kotlinx.coroutines.flow.Flow

/**
 * Get all feed filters as a reactive flow
 */
class GetAllFiltersUseCase(
    private val feedRepository: FeedRepository
) {
    operator fun invoke(): Flow<List<FeedFilter>> = feedRepository.getAllFilters()
}

/**
 * Get the currently active feed filter
 */
class GetActiveFilterUseCase(
    private val feedRepository: FeedRepository
) {
    operator fun invoke(): Flow<List<FeedFilter>> = feedRepository.getActiveFilters()
}

class SetFilterActiveUseCase(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(filterId: String, active: Boolean) {
        feedRepository.setFilterActive(filterId, active)
    }
}

/**
 * Add a new feed filter configuration
 */
class AddFeedFilterUseCase(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(filter: FeedFilter) {
        feedRepository.addFilter(filter)
    }
}

/**
 * Update an existing feed filter
 */
class UpdateFeedFilterUseCase(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(filter: FeedFilter) {
        feedRepository.updateFilter(filter)
    }
}

/**
 * Remove a feed filter
 */
class RemoveFeedFilterUseCase(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(filterId: String) {
        feedRepository.removeFilter(filterId)
    }
}

/**
 * Add a muted author to a filter
 */
class AddMutedAuthorUseCase(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(filterId: String, pubkey: String) {
        feedRepository.addMutedAuthor(filterId, pubkey)
    }
}

/**
 * Remove a muted author from a filter
 */
class RemoveMutedAuthorUseCase(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(filterId: String, pubkey: String) {
        feedRepository.removeMutedAuthor(filterId, pubkey)
    }
}

/**
 * Reset feed filters to defaults
 */
class ResetFeedFiltersUseCase(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke() {
        feedRepository.resetToDefaults()
    }
}

