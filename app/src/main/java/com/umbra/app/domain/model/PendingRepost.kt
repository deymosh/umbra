package com.umbra.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * A NIP-18 repost that's real (we have the repost event, know who reposted and when) but whose
 * target couldn't be resolved to a [NoteView] yet — see EventRepositoryImpl.resolveFeedEvents'
 * `unresolvedReposts` and resolveFeedEventsAndScheduleFetches, which schedules a fallback relay
 * fetch for it. Rendered as a placeholder (ui/components/PendingRepostCard.kt) — the quote-
 * resolution equivalent of this is UnresolvedQuoteReferenceChip.
 */
@Immutable
data class PendingRepost(
    val repostId: String,
    val repostedByPubkey: String,
    val repostedAt: Long,
    val targetId: String
)

/**
 * What EventRepository.observeFeedNotes/observeProfileNotes emit: resolved notes plus any reposts
 * still waiting on their target to resolve. Bundled together (not two separate flows) since both
 * come from the same resolution pass and must stay in sync.
 */
@Immutable
data class FeedNotesResult(
    val notes: List<NoteView> = emptyList(),
    val pendingReposts: List<PendingRepost> = emptyList()
)
