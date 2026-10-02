package com.umbra.app.data.repository

import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.model.EngagementCounts
import com.umbra.app.domain.model.EngagementLink
import com.umbra.app.domain.model.EngagementType
import com.umbra.app.domain.model.NOTE_VIEW_FEED_ORDER
import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.model.PendingRepost
import com.umbra.app.domain.model.engagementLinksOf
import com.umbra.app.domain.model.toCounts
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
    return byId.values
        .sortedWith(NOTE_VIEW_FEED_ORDER)
        .take(limit)
}


/**
 * Live per-target engagement over a changing set of events, using the one shared rule
 * ([engagementLinksOf]). Links are computed by the caller (zap validation verifies a signature,
 * so ingestion does it outside any lock) and remembered per event, so [remove] undoes exactly
 * what [add] did.
 *
 * Zaps are kept as links rather than folded into a total: whether a zap counts depends on its
 * recipient's LNURL key, which is learned later (see [zapCountsToward]), so each read sums only
 * the zaps the given [ZapTrust] accepts, and none without one.
 */
internal class EventEngagementIndex {
    private val countsByTarget = HashMap<String, EngagementCounts>()
    private val zapsByTarget = HashMap<String, HashMap<String, EngagementLink>>()
    private val linksByEvent = HashMap<String, List<EngagementLink>>()

    fun add(eventId: String, links: List<EngagementLink>) {
        remove(eventId)
        if (links.isEmpty()) return
        linksByEvent[eventId] = links
        links.forEach { link ->
            if (link.type == EngagementType.ZAP) {
                zapsByTarget.getOrPut(link.targetId) { HashMap() }[eventId] = link
            } else {
                countsByTarget[link.targetId] = (countsByTarget[link.targetId] ?: EngagementCounts()) + link.toCounts()
            }
        }
    }

    fun remove(eventId: String) {
        linksByEvent.remove(eventId)?.forEach { link ->
            if (link.type == EngagementType.ZAP) {
                val zaps = zapsByTarget[link.targetId] ?: return@forEach
                zaps.remove(eventId)
                if (zaps.isEmpty()) zapsByTarget.remove(link.targetId)
                return@forEach
            }
            val counts = countsByTarget[link.targetId] ?: return@forEach
            val reduced = counts - link.toCounts()
            if (reduced.isEmpty) countsByTarget.remove(link.targetId) else countsByTarget[link.targetId] = reduced
        }
    }

    fun clear() {
        countsByTarget.clear()
        zapsByTarget.clear()
        linksByEvent.clear()
    }

    fun snapshot(zapTrust: ZapTrust? = null): Map<String, EngagementCounts> {
        val out = HashMap(countsByTarget)
        if (zapTrust != null) {
            zapsByTarget.keys.forEach { target ->
                val sats = trustedZapSats(target, zapTrust)
                if (sats > 0) out[target] = (out[target] ?: EngagementCounts()) + EngagementCounts(zapSats = sats)
            }
        }
        return out
    }

    fun countsFor(ids: Collection<String>, zapTrust: ZapTrust? = null): Map<String, EngagementCounts> =
        ids.mapNotNull { id ->
            val key = id.lowercase()
            val sats = if (zapTrust == null) 0 else trustedZapSats(key, zapTrust)
            val counts = countsByTarget[key]
            when {
                sats > 0 -> id to (counts ?: EngagementCounts()) + EngagementCounts(zapSats = sats)
                counts != null -> id to counts
                else -> null
            }
        }.toMap()

    private fun trustedZapSats(target: String, zapTrust: ZapTrust): Long =
        zapsByTarget[target]?.values?.sumOf { link -> if (zapTrust.accepts(target, link)) link.sats else 0L } ?: 0L
}

/** Decides, per read, whether one zap link's sats count toward its target. */
internal fun interface ZapTrust {
    fun accepts(targetId: String, link: EngagementLink): Boolean
}

/** The engagement links of [event] under the shared rule, with Umbra's own signature verifier. */
internal fun engagementLinksOf(event: Event): List<EngagementLink> =
    engagementLinksOf(event, EventCrypto::verifySignature)

internal fun buildIndexedNoteViews(
    resolved: List<ResolvedFeedEvent>,
    profilesByPubkey: Map<String, com.umbra.app.domain.profile.UserProfile>,
    engagement: Map<String, EngagementCounts>
): List<NoteView> = resolved.map { r ->
    val counts = engagement[r.targetEvent.id.lowercase()]
    NoteView(
        event = r.targetEvent,
        authorProfile = profilesByPubkey[r.targetEvent.pubkey.lowercase()],
        reactionCount = counts?.reactions ?: 0,
        replyCount = counts?.replies ?: 0,
        repostCount = counts?.reposts ?: 0,
        zapSats = counts?.zapSats ?: 0,
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
    events.forEach { index.add(it.id, engagementLinksOf(it)) }
    return index.snapshot()
}

internal fun mergeEngagementCounts(
    cachedCounts: Map<String, EngagementCounts>,
    additionalSnapshot: Map<String, EngagementCounts>
): Map<String, EngagementCounts> {
    if (additionalSnapshot.isEmpty()) return cachedCounts
    val merged = cachedCounts.toMutableMap()
    additionalSnapshot.forEach { (targetId, additional) ->
        merged[targetId] = (merged[targetId] ?: EngagementCounts()) + additional
    }
    return merged
}