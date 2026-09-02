package com.umbra.app.data.repository

import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.model.PendingRepost
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip18.extractRepostTarget

internal fun mergeHybridEvents(
    cachedEvents: List<Event>,
    encryptedEvents: List<Event>,
    currentUserPubkey: String?,
    limit: Int
): List<Event> {
    val ownEvents = if (currentUserPubkey == null) {
        emptyList()
    } else {
        encryptedEvents.filter { it.pubkey.equals(currentUserPubkey, ignoreCase = true) }
    }
    return (cachedEvents + ownEvents)
        .distinctBy { it.id }
        .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
        .take(limit)
}

/**
 * A selected feed/profile [Event] resolved to what should actually render as a [NoteView]: a
 * plain note as itself ([repostedByPubkey] null), or a NIP-18 kind-6/16 repost resolved to its
 * target event via [resolveFeedEvents]'s [eventsById] lookup — the target is expected to already
 * be ingestion-time-verified-and-cached (see EventRepositoryImpl.cacheVerifiedRepostTarget), so
 * this never re-parses or re-verifies embedded repost content itself.
 */
internal data class ResolvedFeedEvent(
    val targetEvent: Event,
    val repostedByPubkey: String? = null,
    val repostedAt: Long? = null,
    // The full NIP-18 kind-6/16 repost event itself (not just its id) — the repost banner's
    // overflow menu treats it as a first-class event with the exact same actions a normal note's
    // menu offers (pin, mute, copy id/content/nevent/json, delete), which needs its content/tags,
    // not just its id.
    val repostEvent: Event? = null
)

/**
 * Result of [resolveFeedEvents]: what's actually renderable ([resolved]), plus reposts whose
 * target didn't resolve via the given lookup ([unresolvedReposts]) — the repost itself is real
 * (we have the event, know who reposted and when), only its target isn't available yet. Callers
 * render a placeholder for these and trigger a fallback fetch (see
 * EventRepositoryImpl.resolveFeedEventsAndScheduleFetches) instead of silently dropping them.
 */
internal data class FeedEventResolution(
    val resolved: List<ResolvedFeedEvent>,
    val unresolvedReposts: List<Event>
)

/**
 * Converts [FeedEventResolution.unresolvedReposts] into the placeholders EventCard-adjacent UI
 * renders (see domain.model.PendingRepost / ui/components/PendingRepostCard.kt). Every entry here
 * is guaranteed a non-null target id by construction (resolveFeedEvents only adds a repost to
 * unresolvedReposts once it already extracted one) — the null-filter is defensive, not expected
 * to ever actually drop anything.
 */
internal fun toPendingReposts(unresolvedReposts: List<Event>): List<PendingRepost> =
    unresolvedReposts.mapNotNull { repost ->
        val targetId = extractRepostTarget(repost).eventId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        PendingRepost(
            repostId = repost.id,
            repostedByPubkey = repost.pubkey,
            repostedAt = repost.createdAt,
            targetId = targetId
        )
    }

/**
 * Resolves each of [selected] to a [ResolvedFeedEvent] (see [FeedEventResolution.resolved]) or,
 * for a repost whose target isn't resolvable via [eventsById], to [FeedEventResolution.unresolvedReposts]
 * — deduplicating [resolved] by resolved target id: collapseRepostsToLatestPerTarget only dedupes
 * *among reposts* upstream of this, so a repost and an independent plain occurrence of the same
 * note can still both reach here; when that happens, the repost-carrying entry wins (more
 * informative) and the plain duplicate is dropped, which also keeps NoteView.displayKey (and
 * Compose's LazyColumn key) unique per target id. A repost with no identifiable target at all (no
 * "e" tag) is dropped from both — nothing to show even as a placeholder.
 */
internal fun resolveFeedEvents(selected: List<Event>, eventsById: (String) -> Event?): FeedEventResolution {
    val byTargetId = LinkedHashMap<String, ResolvedFeedEvent>()
    val unresolvedReposts = mutableListOf<Event>()
    selected.forEach { event ->
        if (event.kind == Event.KIND_REPOST || event.kind == Event.KIND_GENERIC_REPOST) {
            val targetId = extractRepostTarget(event).eventId?.takeIf { it.isNotBlank() } ?: return@forEach
            val target = eventsById(targetId)
            if (target == null) {
                unresolvedReposts += event
                return@forEach
            }
            val resolved = ResolvedFeedEvent(target, event.pubkey, event.createdAt, event)
            val existing = byTargetId[resolved.targetEvent.id]
            if (existing == null || (existing.repostedByPubkey == null && resolved.repostedByPubkey != null)) {
                byTargetId[resolved.targetEvent.id] = resolved
            }
        } else {
            val resolved = ResolvedFeedEvent(event)
            val existing = byTargetId[resolved.targetEvent.id]
            if (existing == null || (existing.repostedByPubkey == null && resolved.repostedByPubkey != null)) {
                byTargetId[resolved.targetEvent.id] = resolved
            }
        }
    }
    return FeedEventResolution(byTargetId.values.toList(), unresolvedReposts)
}

internal fun buildCachedNoteViews(
    allEvents: List<Event>,
    profilesByPubkey: Map<String, com.umbra.app.domain.profile.UserProfile>,
    selectedNotes: List<Event>
): List<NoteView> {
    val eventsById = allEvents.associateBy { it.id }
    val resolved = resolveFeedEvents(selectedNotes) { eventsById[it] }.resolved
    val selectedIds = resolved.mapTo(HashSet(resolved.size)) { it.targetEvent.id }
    val engagement = HashMap<String, IntArray>()
    val seenLinks = HashSet<Triple<String, String, Int>>()
    allEvents.forEach { event ->
        if (event.kind != Event.KIND_TEXT_NOTE &&
            event.kind != Event.KIND_REPOST &&
            event.kind != Event.KIND_REACTION
        ) return@forEach
        event.getTagValues("e").forEach { targetId ->
            if (targetId !in selectedIds || !seenLinks.add(Triple(event.id, targetId, event.kind))) return@forEach
            val counts = engagement.getOrPut(targetId) { IntArray(3) }
            when (event.kind) {
                Event.KIND_REACTION -> counts[0] += 1
                Event.KIND_TEXT_NOTE -> counts[1] += 1
                Event.KIND_REPOST -> counts[2] += 1
            }
        }
    }
    return resolved.map { r ->
        val counts = engagement[r.targetEvent.id]
        NoteView(
            event = r.targetEvent,
            authorProfile = profilesByPubkey[r.targetEvent.pubkey.lowercase()],
            reactionCount = counts?.get(0) ?: 0,
            replyCount = counts?.get(1) ?: 0,
            repostCount = counts?.get(2) ?: 0,
            repostedByPubkey = r.repostedByPubkey,
            repostedByProfile = r.repostedByPubkey?.let { profilesByPubkey[it.lowercase()] },
            repostedAt = r.repostedAt,
            repostEvent = r.repostEvent
        )
    }
}

/**
 * Merges the self-profile's SQL-JOIN-computed text notes ([ownNoteViews] — accurate engagement,
 * full Room scope) with its separately-resolved repost [NoteView]s ([repostNoteViews] — engagement
 * scanned from the in-memory cache only, same scope limitation [buildCachedNoteViews] already
 * accepts for the non-self branch). On an id collision (a self-repost of the user's own note),
 * the own-note entry's engagement counts are kept — they're strictly more complete — with only
 * the "reposted by" annotation layered in from the repost-side entry.
 */
internal fun mergeOwnNotesAndReposts(
    ownNoteViews: List<NoteView>,
    repostNoteViews: List<NoteView>,
    limit: Int
): List<NoteView> {
    if (repostNoteViews.isEmpty()) return ownNoteViews.take(limit)
    val byId = LinkedHashMap<String, NoteView>(ownNoteViews.size + repostNoteViews.size)
    ownNoteViews.forEach { byId[it.event.id] = it }
    repostNoteViews.forEach { repostView ->
        val own = byId[repostView.event.id]
        byId[repostView.event.id] = if (own != null) {
            own.copy(
                repostedByPubkey = repostView.repostedByPubkey,
                repostedByProfile = repostView.repostedByProfile,
                repostedAt = repostView.repostedAt,
                repostEvent = repostView.repostEvent
            )
        } else {
            repostView
        }
    }
    // repostedAt (not event.createdAt) is the display/sort position for a repost entry, matching
    // the feed's own bump-to-top behavior — event.createdAt there is the target's original
    // timestamp, which for an old note reposted just now would otherwise sort it as if it were old.
    return byId.values
        .sortedWith(compareByDescending<NoteView> { it.repostedAt ?: it.event.createdAt }.thenBy { it.event.id })
        .take(limit)
}

internal data class EngagementCounts(
    val reactions: Int = 0,
    val replies: Int = 0,
    val reposts: Int = 0
)

internal class EventEngagementIndex {
    private data class Link(val targetId: String, val kind: Int)

    private val countsByTarget = HashMap<String, IntArray>()
    private val linksByEvent = HashMap<String, List<Link>>()

    fun add(event: Event) {
        remove(event.id)
        if (event.kind != Event.KIND_TEXT_NOTE &&
            event.kind != Event.KIND_REPOST &&
            event.kind != Event.KIND_REACTION
        ) return

        val links = event.getTagValues("e")
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .map { Link(it, event.kind) }
            .toList()
        if (links.isEmpty()) return

        linksByEvent[event.id] = links
        links.forEach { link ->
            val counts = countsByTarget.getOrPut(link.targetId) { IntArray(3) }
            counts.increment(link.kind)
        }
    }

    fun remove(eventId: String) {
        linksByEvent.remove(eventId)?.forEach { link ->
            val counts = countsByTarget[link.targetId] ?: return@forEach
            counts.decrement(link.kind)
            if (counts.all { it == 0 }) countsByTarget.remove(link.targetId)
        }
    }

    fun clear() {
        countsByTarget.clear()
        linksByEvent.clear()
    }

    fun snapshot(): Map<String, EngagementCounts> = countsByTarget.mapValues { (_, counts) ->
        EngagementCounts(
            reactions = counts[0],
            replies = counts[1],
            reposts = counts[2]
        )
    }

    private fun IntArray.increment(kind: Int) {
        when (kind) {
            Event.KIND_REACTION -> this[0] += 1
            Event.KIND_TEXT_NOTE -> this[1] += 1
            Event.KIND_REPOST -> this[2] += 1
        }
    }

    private fun IntArray.decrement(kind: Int) {
        when (kind) {
            Event.KIND_REACTION -> this[0] = (this[0] - 1).coerceAtLeast(0)
            Event.KIND_TEXT_NOTE -> this[1] = (this[1] - 1).coerceAtLeast(0)
            Event.KIND_REPOST -> this[2] = (this[2] - 1).coerceAtLeast(0)
        }
    }
}

internal fun buildIndexedNoteViews(
    resolved: List<ResolvedFeedEvent>,
    profilesByPubkey: Map<String, com.umbra.app.domain.profile.UserProfile>,
    engagement: Map<String, EngagementCounts>
): List<NoteView> = resolved.map { r ->
    val counts = engagement[r.targetEvent.id]
    NoteView(
        event = r.targetEvent,
        authorProfile = profilesByPubkey[r.targetEvent.pubkey.lowercase()],
        reactionCount = counts?.reactions ?: 0,
        replyCount = counts?.replies ?: 0,
        repostCount = counts?.reposts ?: 0,
        repostedByPubkey = r.repostedByPubkey,
        repostedByProfile = r.repostedByPubkey?.let { profilesByPubkey[it.lowercase()] },
        repostedAt = r.repostedAt,
        repostEvent = r.repostEvent
    )
}

/**
 * Builds a target-id-keyed engagement snapshot from [events] — extracted out of
 * [mergeEngagementCounts] so a caller whose event set only changes occasionally (see
 * `observeFeedNotes`'s `ownEvents`) can cache this result and skip rebuilding it on every
 * emission, only recomputing when the source events actually change.
 */
internal fun buildAdditionalEngagementSnapshot(events: Collection<Event>): Map<String, EngagementCounts> {
    if (events.isEmpty()) return emptyMap()
    val index = EventEngagementIndex()
    events.forEach(index::add)
    return index.snapshot()
}

internal fun mergeEngagementCounts(
    cachedCounts: Map<String, EngagementCounts>,
    additionalSnapshot: Map<String, EngagementCounts>
): Map<String, EngagementCounts> {
    if (additionalSnapshot.isEmpty()) return cachedCounts
    val merged = cachedCounts.toMutableMap()
    additionalSnapshot.forEach { (targetId, additional) ->
        val cached = merged[targetId] ?: EngagementCounts()
        merged[targetId] = EngagementCounts(
            reactions = cached.reactions + additional.reactions,
            replies = cached.replies + additional.replies,
            reposts = cached.reposts + additional.reposts
        )
    }
    return merged
}
