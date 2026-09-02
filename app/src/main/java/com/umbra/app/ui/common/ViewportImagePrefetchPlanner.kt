package com.umbra.app.ui.common

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.ui.components.MentionedProfileRef
import com.umbra.app.ui.components.QuotedEventRef
import com.umbra.app.ui.components.URL_REGEX
import com.umbra.app.ui.components.extractMentionedPubkeys
import com.umbra.app.ui.components.extractMentionedProfileRefs
import com.umbra.app.ui.components.extractQuotedEventReferences
import com.umbra.app.ui.components.extractQuotedEventRefs
import com.umbra.app.ui.components.parseExternalUrlCandidate
import java.net.URI

private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp")

private fun viewportWindow(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int
): List<Event> {
    if (events.isEmpty() || visibleCount <= 0 || firstVisibleIndex < 0) return emptyList()

    val lastVisibleIndex = (firstVisibleIndex + visibleCount - 1).coerceAtMost(events.lastIndex)
    val lastLookAheadIndex = (lastVisibleIndex + lookAheadItems).coerceAtMost(events.lastIndex)
    if (firstVisibleIndex > lastLookAheadIndex) return emptyList()

    return events.subList(firstVisibleIndex, lastLookAheadIndex + 1)
}

internal fun extractPrefetchableHttpUrlsFromText(text: String): List<String> {
    return URL_REGEX.findAll(text)
        .mapNotNull { parseExternalUrlCandidate(it.value)?.normalizedUrl }
        .distinct()
        .toList()
}

internal fun extractPrefetchableImageUrlsFromText(text: String): List<String> {
    return extractPrefetchableHttpUrlsFromText(text)
        .filter { isLikelyImageUrl(it) }
        .toList()
}

internal fun collectViewportHttpPrefetchUrls(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 8,
    maxUrls: Int = 24,
    includeImages: Boolean = false
): List<String> {
    return viewportWindow(events, firstVisibleIndex, visibleCount, lookAheadItems)
        .asSequence()
        .flatMap { extractPrefetchableHttpUrlsFromText(it.content).asSequence() }
        .filter { includeImages || !isLikelyImageUrl(it) }
        .distinct()
        .take(maxUrls)
        .toList()
}

internal fun collectViewportImagePrefetchUrls(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 8,
    maxUrls: Int = 24
): List<String> {
    return viewportWindow(events, firstVisibleIndex, visibleCount, lookAheadItems)
        .asSequence()
        .flatMap { extractPrefetchableImageUrlsFromText(it.content).asSequence() }
        .distinct()
        .take(maxUrls)
        .toList()
}

/**
 * Ids of the notes currently visible (or just about to scroll into view) themselves — as opposed
 * to [collectViewportQuotedEventIds]'s ids of *other* events they quote. Used to scope a live
 * engagement (reactions/reposts/zaps) overlay to what's actually on screen instead of every note
 * ever loaded into a long-lived list (see ProfileViewModel.scheduleProfileEngagementSubscription):
 * a profile's loaded note count only grows as the user scrolls, so windowing by loaded-count alone
 * (e.g. "the newest 80 loaded") would stop covering older notes the moment more than that many
 * are loaded, even while the user is actively looking at them.
 */
internal fun collectViewportEventIds(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 8,
    maxIds: Int = 80
): List<String> {
    return viewportWindow(events, firstVisibleIndex, visibleCount, lookAheadItems)
        .asSequence()
        .map { it.id }
        .distinct()
        .take(maxIds)
        .toList()
}

/**
 * Oldest `createdAt` among the same viewport window [collectViewportEventIds] targets — lets a
 * caller building an engagement/interactions lookback window (see
 * FeedViewModel.scheduleEngagementSubscription) extend back far enough to cover whatever note is
 * oldest on screen, instead of assuming everything visible is recent.
 */
internal fun collectViewportOldestCreatedAt(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 8
): Long? {
    return viewportWindow(events, firstVisibleIndex, visibleCount, lookAheadItems).minOfOrNull { it.createdAt }
}

/**
 * Quoted-event ids referenced by notes currently visible (or just about to scroll into view),
 * so the caller can proactively fetch them from relays — matches UX-1: a quoted note shouldn't
 * sit as an unresolved "Quote: <id>" chip just because the user hasn't tapped it yet when the
 * quoting note is right there on screen.
 */
internal fun collectViewportQuotedEventIds(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 4,
    maxIds: Int = 8
): List<String> {
    return viewportWindow(events, firstVisibleIndex, visibleCount, lookAheadItems)
        .asSequence()
        .filter { it.kind == Event.KIND_TEXT_NOTE }
        .flatMap { extractQuotedEventReferences(it).asSequence() }
        .distinct()
        .take(maxIds)
        .toList()
}

/** Like [collectViewportQuotedEventIds] but keeps each reference's NIP-19 relay hints. */
internal fun collectViewportQuotedEventRefs(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 4,
    maxIds: Int = 8
): List<QuotedEventRef> {
    return viewportWindow(events, firstVisibleIndex, visibleCount, lookAheadItems)
        .asSequence()
        .filter { it.kind == Event.KIND_TEXT_NOTE }
        .flatMap { extractQuotedEventRefs(it).asSequence() }
        .distinctBy { it.id }
        .take(maxIds)
        .toList()
}

/**
 * Mentioned pubkeys (nostr:npub1/nprofile1) referenced by notes currently visible (or just about
 * to scroll into view) — same rationale as [collectViewportQuotedEventIds] but for NIP-19 profile
 * mentions (UX-2): a mention shouldn't stay a raw npub just because nothing else already asked
 * for that profile.
 */
internal fun collectViewportMentionedPubkeys(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 4,
    maxPubkeys: Int = 8
): List<String> {
    return viewportWindow(events, firstVisibleIndex, visibleCount, lookAheadItems)
        .asSequence()
        .filter { it.kind == Event.KIND_TEXT_NOTE }
        .flatMap { extractMentionedPubkeys(it).asSequence() }
        .distinct()
        .take(maxPubkeys)
        .toList()
}

/** Like [collectViewportMentionedPubkeys] but keeps each mention's NIP-19 relay hints. */
internal fun collectViewportMentionedProfileRefs(
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 4,
    maxPubkeys: Int = 8
): List<MentionedProfileRef> {
    return viewportWindow(events, firstVisibleIndex, visibleCount, lookAheadItems)
        .asSequence()
        .filter { it.kind == Event.KIND_TEXT_NOTE }
        .flatMap { extractMentionedProfileRefs(it).asSequence() }
        .distinctBy { it.pubkey }
        .take(maxPubkeys)
        .toList()
}

/**
 * Resolves quoted-event ids from the current viewport that aren't already known (i.e. not in the
 * screen's own local event list, and not already resolved by a previous prefetch tick) — tries
 * the in-memory/Room cache first, then falls back to a bounded one-shot relay lookup per id via
 * [EventRepository.fetchEventById]. Bounded by [collectViewportQuotedEventIds]'s own `maxIds` cap
 * and by [fetchTimeoutMs] per id, so a burst of quotes scrolling into view can't stall the caller.
 */
internal suspend fun resolveViewportQuotedEvents(
    eventRepository: EventRepository,
    trackReferencedAuthorUseCase: TrackReferencedAuthorUseCase,
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    alreadyKnownIds: Set<String>,
    lookAheadItems: Int = 4,
    maxIds: Int = 6,
    fetchTimeoutMs: Long = 4000L
): Map<String, Event> {
    val refsToResolve = collectViewportQuotedEventRefs(
        events = events,
        firstVisibleIndex = firstVisibleIndex,
        visibleCount = visibleCount,
        lookAheadItems = lookAheadItems,
        maxIds = maxIds
    ).filterNot { it.id in alreadyKnownIds }
    if (refsToResolve.isEmpty()) return emptyMap()

    val resolved = mutableMapOf<String, Event>()
    refsToResolve.forEach { ref ->
        // relayHints (a nevent1's NIP-19 TLV hints) let fetchEventById dial the relay(s) the
        // quoting author actually pointed at directly, instead of only asking whichever relays
        // we already happen to be connected to — see EventRepository.fetchEventById's doc
        // comment for why a bare id-only lookup regularly never finds a quote from outside our
        // own relay pool.
        eventRepository.fetchEventById(ref.id, timeoutMs = fetchTimeoutMs, relayHints = ref.relays)?.let { event ->
            resolved[ref.id] = event
            trackReferencedAuthorUseCase(event.pubkey)
        }
    }
    return resolved
}

/**
 * Requests a metadata fetch (via [TrackReferencedAuthorUseCase]) for any not-already-fresh pubkey
 * mentioned by notes in the current viewport. Resolution itself isn't tracked here: it lands in
 * Room via the normal event-ingestion pipeline, and NostrTextRenderer's mention rendering observes
 * it reactively (see `observeProfile` usage there) — this function's only job is making sure the
 * fetch gets requested at all.
 */
internal suspend fun requestViewportMentionedProfiles(
    userRepository: UserRepository,
    trackReferencedAuthorUseCase: TrackReferencedAuthorUseCase,
    events: List<Event>,
    firstVisibleIndex: Int,
    visibleCount: Int,
    lookAheadItems: Int = 4,
    maxPubkeys: Int = 8
) {
    val refs = collectViewportMentionedProfileRefs(
        events = events,
        firstVisibleIndex = firstVisibleIndex,
        visibleCount = visibleCount,
        lookAheadItems = lookAheadItems,
        maxPubkeys = maxPubkeys
    )
    refs.forEach { ref ->
        if (!userRepository.isProfileFresh(ref.pubkey)) {
            trackReferencedAuthorUseCase(ref.pubkey, ref.relays)
        }
    }
}

private fun isLikelyImageUrl(url: String): Boolean {
    val lowercaseUrl = url.lowercase()
    val path = runCatching { URI(lowercaseUrl).path.orEmpty() }
        .getOrElse {
            lowercaseUrl.substringBefore('?').substringBefore('#')
        }
    return IMAGE_EXTENSIONS.any { extension -> path.endsWith(extension) }
}

