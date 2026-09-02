package com.umbra.app.data.repository

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip18.extractRepostTarget

internal fun shouldStoreInMemoryCache(eventPubkey: String, currentUserPubkey: String?): Boolean {
    if (currentUserPubkey == null) return true
    return !eventPubkey.equals(currentUserPubkey, ignoreCase = true)
}

internal fun selectHybridFeedNotes(
    events: List<Event>,
    since: Long,
    limit: Int,
    authors: Set<String>,
    mutedPubkeys: Set<String>,
    excludedHashtagsLower: Set<String>,
    includeMentions: Boolean,
    hideNsfw: Boolean,
    currentNpub: String?,
    currentUserPubkey: String?,
    desiredTagsLower: Set<String>
): List<Event> {
    val normalizedAuthors = authors.mapTo(HashSet(authors.size)) { it.lowercase() }
    val normalizedMuted = mutedPubkeys.mapTo(HashSet(mutedPubkeys.size)) { it.lowercase() }
    val normalizedExcluded = excludedHashtagsLower.mapTo(HashSet(excludedHashtagsLower.size)) { it.lowercase() }
    val normalizedDesired = desiredTagsLower.mapTo(HashSet(desiredTagsLower.size)) { it.lowercase() }
    val normalizedCurrentUser = currentUserPubkey?.lowercase()

    val filtered = events.asSequence()
        .filter { isFeedEligibleKind(it.kind) && it.createdAt > since }
        .filter { normalizedAuthors.isEmpty() || it.pubkey.lowercase() in normalizedAuthors }
        .filter { event ->
            // Hashtag/NSFW filters read the event's own content — a repost's is either empty or
            // NIP-18's embedded original-event JSON, never the actual post text, so these checks
            // don't meaningfully apply to it (and would false-negative-filter almost every
            // repost). Always pass a repost through here; its target's own hashtags aren't
            // inspected in this pass — see collapseRepostsToLatestPerTarget's doc comment.
            if (event.kind != Event.KIND_TEXT_NOTE) return@filter true
            val hashtags = event.getHashtags().toHashSet()
            (!hideNsfw || "nsfw" !in hashtags) &&
                (normalizedExcluded.isEmpty() || hashtags.none { it in normalizedExcluded }) &&
                (normalizedDesired.isEmpty() || hashtags.any { it in normalizedDesired })
        }
        .filter { event ->
            val isOwn = normalizedCurrentUser != null && event.pubkey.equals(normalizedCurrentUser, ignoreCase = true)
            isOwn || event.pubkey.lowercase() !in normalizedMuted
        }
        .filter { event ->
            if (event.kind != Event.KIND_TEXT_NOTE) return@filter true
            val isOwn = normalizedCurrentUser != null && event.pubkey.equals(normalizedCurrentUser, ignoreCase = true)
            isOwn || includeMentions || currentNpub == null || !event.content.contains(currentNpub, ignoreCase = true)
        }
        .toList()

    return collapseRepostsToLatestPerTarget(filtered)
        .distinctBy { it.id }
        .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
        .take(limit)
}

private fun isFeedEligibleKind(kind: Int): Boolean =
    kind == Event.KIND_TEXT_NOTE || kind == Event.KIND_REPOST || kind == Event.KIND_GENERIC_REPOST

/**
 * Collapses multiple reposts (kind 6/16) of the same target event down to the single newest one
 * — per product decision, a note reposted by several followed authors should appear once in the
 * feed, annotated with the latest reposter, not once per reposter. A repost whose target id can't
 * even be determined from its own "e" tag (malformed/missing) is dropped outright — nothing
 * coherent to show. This only dedupes *among reposts*; a repost and an independent plain
 * occurrence of the same note (e.g. its author is also followed) are deliberately left for
 * buildIndexedNoteViews/buildCachedNoteViews to reconcile once both are resolved to NoteViews,
 * since only there is it known whether the plain occurrence's target actually resolves too.
 */
internal fun collapseRepostsToLatestPerTarget(events: List<Event>): List<Event> {
    val plain = events.filterNot { it.kind == Event.KIND_REPOST || it.kind == Event.KIND_GENERIC_REPOST }
    val reposts = events.filter { it.kind == Event.KIND_REPOST || it.kind == Event.KIND_GENERIC_REPOST }
    if (reposts.isEmpty()) return plain

    val latestByTarget = LinkedHashMap<String, Event>()
    reposts.forEach { repost ->
        val targetId = extractRepostTarget(repost).eventId?.takeIf { it.isNotBlank() } ?: return@forEach
        val current = latestByTarget[targetId]
        if (current == null || repost.createdAt > current.createdAt ||
            (repost.createdAt == current.createdAt && repost.id > current.id)
        ) {
            latestByTarget[targetId] = repost
        }
    }
    return plain + latestByTarget.values
}

internal fun updateFeedNotesIncrementally(
    currentNotes: List<Event>,
    incomingEvents: Collection<Event>,
    since: Long,
    limit: Int,
    authors: Set<String>,
    mutedPubkeys: Set<String>,
    excludedHashtagsLower: Set<String>,
    includeMentions: Boolean,
    hideNsfw: Boolean,
    currentNpub: String?,
    currentUserPubkey: String?,
    desiredTagsLower: Set<String>
): List<Event> = selectHybridFeedNotes(
    events = currentNotes + incomingEvents,
    since = since,
    limit = limit,
    authors = authors,
    mutedPubkeys = mutedPubkeys,
    excludedHashtagsLower = excludedHashtagsLower,
    includeMentions = includeMentions,
    hideNsfw = hideNsfw,
    currentNpub = currentNpub,
    currentUserPubkey = currentUserPubkey,
    desiredTagsLower = desiredTagsLower
)

/**
 * True if any id in [previousIds] is missing from [newIds] — i.e. an own event was deleted
 * between two `observeFeedNotes` `OwnSnapshot` updates. See that branch's doc comment for why a
 * removal forces a full [selectHybridFeedNotes] recompute instead of the cheaper incremental
 * merge [addedOwnEvents] enables.
 */
internal fun hasRemovedOwnEvents(previousIds: Set<String>, newIds: Set<String>): Boolean =
    previousIds.any { it !in newIds }

/** [events] not already present in [previousIds] — the delta to fold in incrementally. */
internal fun addedOwnEvents(events: List<Event>, previousIds: Set<String>): List<Event> =
    events.filter { it.id !in previousIds }
