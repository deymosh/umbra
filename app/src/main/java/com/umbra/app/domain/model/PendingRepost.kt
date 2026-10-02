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
) {
    /** See [NoteView.feedSortAt]: a repost row sits at the repost event's own time. */
    val feedSortAt: Long get() = repostedAt
}

/**
 * What EventRepository.observeFeedNotes/observeProfileNotes emit: resolved notes plus any reposts
 * still waiting on their target to resolve. Bundled together (not two separate flows) since both
 * come from the same resolution pass and must stay in sync. Both lists are ordered newest first by
 * `feedSortAt`, the one ordering rule every feed row follows, so they merge into a single timeline
 * without any per-kind special casing.
 */
@Immutable
data class FeedNotesResult(
    val notes: List<NoteView> = emptyList(),
    val pendingReposts: List<PendingRepost> = emptyList()
)

/** Newest-first by `feedSortAt`, ties broken by id so the order is total and stable. */
val NOTE_VIEW_FEED_ORDER: Comparator<NoteView> =
    compareByDescending<NoteView> { it.feedSortAt }.thenBy { it.event.id }

private val PENDING_REPOST_FEED_ORDER: Comparator<PendingRepost> =
    compareByDescending<PendingRepost> { it.feedSortAt }.thenBy { it.repostId }

/** Builds a [FeedNotesResult] with both lists in feed order, whatever order they arrived in. */
fun orderedFeedNotesResult(notes: List<NoteView>, pendingReposts: List<PendingRepost> = emptyList()): FeedNotesResult =
    FeedNotesResult(
        notes = notes.sortedWith(NOTE_VIEW_FEED_ORDER),
        pendingReposts = pendingReposts.sortedWith(PENDING_REPOST_FEED_ORDER)
    )
