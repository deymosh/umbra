package com.umbra.app.domain.nip25

import com.umbra.app.domain.nip01.Event

/**
 * Minimal NIP-25 reaction helpers shared by feed/thread logic.
 */
data class ReactionTarget(
    val eventId: String?,
    val authorPubkey: String?
)

fun extractReactionTarget(event: Event): ReactionTarget {
    if (event.kind != Event.KIND_REACTION && event.kind != Event.KIND_WEBSITE_REACTION) {
        return ReactionTarget(eventId = null, authorPubkey = null)
    }

    val targetEventId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
    val targetAuthorPubkey = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
    return ReactionTarget(eventId = targetEventId, authorPubkey = targetAuthorPubkey)
}

fun isPositiveReactionContent(content: String): Boolean {
    val normalized = content.trim()
    return normalized.isEmpty() || normalized == "+" || normalized == "❤️" || normalized == "👍"
}
