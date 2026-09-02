package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter

/**
 * Builds chunked "engagement" filters — replies, reposts, reactions, and zap receipts — for a set
 * of currently-visible note ids. Shared by Feed and Profile (both request the same kind set via
 * an #e tag filter for whatever notes are on screen); [since] is left to the caller since Feed
 * only wants a recent window on a live-scrolling timeline while Profile wants a specific person's
 * full engagement history regardless of age.
 */
class BuildEngagementFiltersUseCase {
    companion object {
        private const val ENGAGEMENT_KINDS_CHUNK_SIZE = 20
        private val ENGAGEMENT_KINDS = setOf(
            Event.KIND_TEXT_NOTE,
            Event.KIND_REPOST,
            Event.KIND_REACTION,
            Event.KIND_ZAP_RECEIPT
        )
    }

    operator fun invoke(
        eventIds: Collection<String>,
        limit: Int,
        since: Long? = null
    ): List<EventFilter> {
        if (eventIds.isEmpty()) return emptyList()
        return eventIds.toList().chunked(ENGAGEMENT_KINDS_CHUNK_SIZE).map { idsChunk ->
            EventFilter(
                kinds = ENGAGEMENT_KINDS,
                tagFilters = mapOf("e" to idsChunk.toSet()),
                since = since,
                limit = limit
            )
        }
    }
}
