package com.umbra.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.DataSource
import com.umbra.app.R
import com.umbra.app.domain.model.PendingRepost
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.common.ImmutableListSnapshot
import com.umbra.app.ui.common.ImmutableMapSnapshot
import com.umbra.app.ui.feed.EventCard
import com.umbra.app.ui.common.UrlMetadata

/**
 * A row in the merged feed sequence — either a resolved note or a repost still waiting on its
 * target (see [mergeFeedRows]'s doc comment for the ordering trade-off this makes).
 */
internal sealed interface FeedRow {
    data class NoteRow(val event: Event) : FeedRow
    data class PendingRow(val pending: PendingRepost) : FeedRow
}

/**
 * Interleaves [pendingReposts] into [notes] for display, without disturbing [notes]' own
 * (already-correct — see NoteView/ResolvedFeedEvent's repost-bump-to-top ordering) relative order:
 * walks [notes] once, inserting each pending item (sorted newest-repost-first) right before the
 * first note it should sort ahead of. Deliberately NOT pixel-perfect against an *already-resolved*
 * repost's own bump-to-top position — that note's `createdAt` is its ORIGINAL target timestamp,
 * not its repost time (only [PendingRepost.repostedAt] is directly available here), so a pending
 * item can occasionally land one slot off relative to a neighboring resolved repost. Pending items
 * are typically few and short-lived (they resolve within seconds, or drop off after one failed
 * fetch attempt), so this is an accepted, narrowly-scoped trade-off rather than threading a
 * parallel repostedAt-per-event map through to re-derive [notes]' order from scratch here.
 */
internal fun mergeFeedRows(notes: List<Event>, pendingReposts: List<PendingRepost>): List<FeedRow> {
    if (pendingReposts.isEmpty()) return notes.map { FeedRow.NoteRow(it) }
    val pendingSorted = pendingReposts.sortedByDescending { it.repostedAt }
    val rows = ArrayList<FeedRow>(notes.size + pendingReposts.size)
    var pendingIndex = 0
    notes.forEach { event ->
        while (pendingIndex < pendingSorted.size && pendingSorted[pendingIndex].repostedAt >= event.createdAt) {
            rows += FeedRow.PendingRow(pendingSorted[pendingIndex])
            pendingIndex++
        }
        rows += FeedRow.NoteRow(event)
    }
    while (pendingIndex < pendingSorted.size) {
        rows += FeedRow.PendingRow(pendingSorted[pendingIndex])
        pendingIndex++
    }
    return rows
}

fun LazyListScope.notesFeedSection(
    notes: List<Event>,
    eventsById: Map<String, Event>,
    threadDepthByEventId: Map<String, Int> = buildThreadDepthByEventId(notes, eventsById),
    profileForPubkey: (String) -> UserProfile?,
    userRepository: UserRepository,
    replyCounts: ImmutableMapSnapshot<String, Int>,
    reactionCounts: ImmutableMapSnapshot<String, Int>,
    repostCounts: ImmutableMapSnapshot<String, Int>,
    // Event id -> reposter pubkey, for a note that arrived via a NIP-18 repost — drives EventCard's
    // "reposted by" banner. Reposter's own profile is resolved the same way authorProfile already
    // is, via profileForPubkey below.
    repostedByPubkeyForEvent: ImmutableMapSnapshot<String, String> = ImmutableMapSnapshot(),
    // Event id -> the repost event's own created_at (not the original note's), for the "reposted by"
    // banner's relative-time label.
    repostedAtForEvent: ImmutableMapSnapshot<String, Long> = ImmutableMapSnapshot(),
    // Event id -> the repost event itself, for the "reposted by" banner's overflow menu — offers
    // the exact same actions a normal note's menu does, applied to the repost event.
    repostEventForEvent: ImmutableMapSnapshot<String, Event> = ImmutableMapSnapshot(),
    // Reposts known but whose target hasn't resolved yet — rendered as a PendingRepostCard,
    // interleaved with `notes` via mergeFeedRows.
    pendingReposts: ImmutableListSnapshot<PendingRepost> = ImmutableListSnapshot(),
    isLoading: Boolean,
    isLoadingMore: Boolean,
    // True once pagination has paged back as far as it's willing to search and found nothing
    // further — mutually exclusive with isLoadingMore in practice (see loadOlderFeed()/
    // loadMoreNotes()'s decision functions), so both being true is treated as still-loading.
    noOlderNotesFound: Boolean = false,
    notesHeaderText: String? = null,
    emptyTitle: String? = null,
    showBottomSpacer: Boolean = true,
    torDataSourceFactory: DataSource.Factory,
    enableEventClick: Boolean = true,
    isLikedForEvent: (String) -> Boolean = { false },
    isRepostedForEvent: (String) -> Boolean = { false },
    onEventClick: (Event) -> Unit,
    onLike: (Event, String, CustomEmoji?) -> Boolean,
    reactionEmojis: List<ReactionEmoji> = emptyList(),
    onAddReactionEmoji: (ReactionEmoji) -> Unit = {},
    onRemoveReactionEmoji: (String) -> Unit = {},
    onRepost: (Event) -> Unit,
    onQuote: (Event) -> Unit = {},
    onShare: (Event) -> Unit,
    onReply: (Event) -> Unit,
    onProfileClick: (String) -> Unit,
    onEventReferenceClick: (String) -> Unit,
    currentUserPubkey: String? = null,
    onDelete: (Event) -> Unit = {},
    onMute: (String) -> Unit = {},
    isPinnedForEvent: (String) -> Boolean = { false },
    onPin: (Event) -> Unit = {},
    animateAvatars: Boolean = true,
    getUrlMetadata: (String) -> UrlMetadata? = { null },
    getEventJson: (Event) -> String = { "" }
) {
    if (isLoading) {
        item {
            LoadingSpinner(
                size = 32.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            )
        }
    }

    if (!notesHeaderText.isNullOrBlank() && notes.isNotEmpty()) {
        item {
            Text(
                text = notesHeaderText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }

    // Not remember()'d: notesFeedSection is a plain LazyListScope builder function, not itself
    // @Composable — same non-remembered treatment threadDepthByEventId's default param already
    // gets above, computed fresh whenever the caller (which IS @Composable) recomposes this block.
    val feedRows = mergeFeedRows(notes, pendingReposts.toList())
    items(
        items = feedRows,
        key = { row ->
            when (row) {
                is FeedRow.NoteRow -> row.event.id
                is FeedRow.PendingRow -> "pending:${row.pending.repostId}"
            }
        },
        contentType = { row ->
            when (row) {
                is FeedRow.NoteRow -> row.event.kind
                is FeedRow.PendingRow -> "pending_repost"
            }
        }
    ) { row ->
        when (row) {
            is FeedRow.PendingRow -> {
                val pending = row.pending
                PendingRepostCard(
                    pubkey = pending.repostedByPubkey,
                    userProfile = profileForPubkey(pending.repostedByPubkey),
                    onClick = { onProfileClick(pending.repostedByPubkey) },
                    repostedAt = pending.repostedAt
                )
            }
            is FeedRow.NoteRow -> {
                val event = row.event
                val parentId = event.getParentEventId()
                val parentEvent = parentId?.let { eventsById[it] }
                val replyToProfile = parentEvent?.let { profileForPubkey(it.pubkey) }
                val replyToLabel = replyToProfile?.getUserDisplayName() ?: parentEvent?.pubkey?.take(8)
                // Permanently stable closure identity (remember with no keys), reading eventsById/
                // profileForPubkey through rememberUpdatedState instead of capturing them directly —
                // keying remember() on eventsById/profileForPubkey (as before) produced a *new* lambda on
                // every note arrival or single profile update, which defeats EventCard's recomposition
                // skip for every visible row's getQuotedEvent/getQuotedEventAuthorProfile parameter, not
                // just the row that actually changed. Same reasoning as onLike/onRepost/etc. in
                // FeedScreen, taken one step further since those are already stable at the source.
                val eventsByIdState = rememberUpdatedState(eventsById)
                val getQuotedEvent = remember { { id: String -> eventsByIdState.value[id] } }
                val profileForPubkeyState = rememberUpdatedState(profileForPubkey)
                val getQuotedEventAuthorProfile = remember { { pubkey: String -> profileForPubkeyState.value(pubkey) } }

                val repostedByPubkey = repostedByPubkeyForEvent[event.id]
                val repostEvent = repostEventForEvent[event.id]

                EventCard(
                    event = event,
                    enableEventClick = enableEventClick,
                    userProfile = profileForPubkey(event.pubkey),
                    replyToProfile = replyToProfile,
                    userRepository = userRepository,
                    threadDepth = threadDepthByEventId[event.id] ?: 0,
                    replyToLabel = replyToLabel,
                    replyCount = replyCounts[event.id] ?: 0,
                    reactionCount = reactionCounts[event.id] ?: 0,
                    repostCount = repostCounts[event.id] ?: 0,
                    repostedByPubkey = repostedByPubkey,
                    repostedByProfile = repostedByPubkey?.let { profileForPubkey(it) },
                    repostedAt = repostedAtForEvent[event.id],
                    repostEvent = repostEvent,
                    isRepostPinned = repostEvent?.let { isPinnedForEvent(it.id) } ?: false,
                    isLiked = isLikedForEvent(event.id),
                    isReposted = isRepostedForEvent(event.id),
                    torDataSourceFactory = torDataSourceFactory,
                    onEventClick = onEventClick,
                    onLike = onLike,
                    reactionEmojis = reactionEmojis,
                    onAddReactionEmoji = onAddReactionEmoji,
                    onRemoveReactionEmoji = onRemoveReactionEmoji,
                    onRepost = onRepost,
                    onQuote = onQuote,
                    onShare = onShare,
                    onReply = onReply,
                    onProfileClick = onProfileClick,
                    onEventReferenceClick = onEventReferenceClick,
                    currentUserPubkey = currentUserPubkey,
                    onDelete = onDelete,
                    onMute = onMute,
                    isPinned = isPinnedForEvent(event.id),
                    onPin = onPin,
                    getQuotedEvent = getQuotedEvent,
                    getQuotedEventAuthorProfile = getQuotedEventAuthorProfile,
                    animateAvatars = animateAvatars,
                    getUrlMetadata = getUrlMetadata,
                    getEventJson = getEventJson
                )
            }
        }
    }

    if (isLoadingMore) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                LoadingSpinner(size = 32.dp)
            }
        }
    } else if (noOlderNotesFound && notes.isNotEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_more_notes_found),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (!isLoading && notes.isEmpty() && !emptyTitle.isNullOrBlank()) {
        item {
            EmptyState(
                title = emptyTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        }
    }

    if (showBottomSpacer) {
        item {
            Spacer(modifier = Modifier.height(92.dp))
        }
    }
}

