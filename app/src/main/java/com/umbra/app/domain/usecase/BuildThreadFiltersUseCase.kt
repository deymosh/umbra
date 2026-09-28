package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter

/**
 * Filters for an open thread: everything that points at the focal note or its root.
 *
 * - NIP-10 replies plus reposts, reactions and zap receipts reference their target with a
 *   lowercase `e` tag.
 * - NIP-22 comments (kind 1111) carry the thread root in an uppercase `E` tag and their direct
 *   parent in a lowercase `e` tag, so both tag names are requested for comments.
 */
class BuildThreadFiltersUseCase {
    companion object {
        const val THREAD_LIMIT = 500
        private val REFERENCE_KINDS = setOf(
            Event.KIND_TEXT_NOTE,
            Event.KIND_REPOST,
            Event.KIND_GENERIC_REPOST,
            Event.KIND_REACTION,
            Event.KIND_ZAP_RECEIPT,
            Event.KIND_COMMENT
        )
    }

    operator fun invoke(anchorId: String, rootId: String?): List<EventFilter> {
        val ids = setOfNotNull(anchorId, rootId?.takeIf { it.isNotBlank() })
        return listOf(
            EventFilter(kinds = REFERENCE_KINDS, tagFilters = mapOf("e" to ids), limit = THREAD_LIMIT),
            EventFilter(kinds = setOf(Event.KIND_COMMENT), tagFilters = mapOf("E" to ids), limit = THREAD_LIMIT)
        )
    }
}
