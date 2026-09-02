package com.umbra.app.data.repository

import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.data.repository.policy.DiscoveredRelayIdlePolicy
import com.umbra.app.data.repository.policy.FeedRelaySincePolicy
import com.umbra.app.data.repository.policy.OutboxInboxRelaySincePolicy
import com.umbra.app.domain.model.ChannelPriority
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.SubscriptionType
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.relay.upsertBoundedRelayRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// Promoted out of EventRepositoryImpl's companion object — a `private val`/`private
// const val` in a Kotlin companion object is class-private, so EventChannelRouting (a sibling
// file/class) couldn't otherwise see these. Doc comments preserved verbatim from their prior
// location.
internal val OUTBOX_CHANNEL_FAMILY = NostrChannels.OUTBOX_NOTES.substringBefore('-')
internal val INBOX_CHANNEL_FAMILY = NostrChannels.INBOX_NOTES.substringBefore('-')
internal val FEED_CHANNEL_FAMILY = NostrChannels.FEED_NOTES.substringBefore('-')

// Session-persistent channels whose backfill folds into the live channel's own subscription as an
// extra filter (see EventRepositoryImpl.applyBackfillOverlay) instead of a separate derived
// "-page"/"-resync" channel — one fewer subscription slot spent during backfill. Not used for
// screen-scoped channels (profile backfill etc.), whose own channel already has a bounded lifetime
// rather than being a session-wide standing subscription.
internal val MERGEABLE_BACKFILL_CHANNEL_IDS = setOf(NostrChannels.OUTBOX_NOTES, NostrChannels.INBOX_NOTES)

// Relays commonly reject REQs whose total filter items exceed a per-relay cap
// ("too many authors" / "total filter items too large"). Chunking a large follow
// list into multiple same-REQ filters avoids that silent rejection.
internal const val MAX_AUTHORS_PER_FEED_FILTER = 200

// Per relay, not global — see upsertBoundedRelayRequest. relayRequests holds at most
// one entry per (relayUrl, subscriptionId): a ledger of currently-active subscriptions,
// not a growing history log, so every entry is "live" and a global cap risked evicting a
// different, still-active relay's subscriptions (including the user's own outbox/inbox
// ones) purely because relay-pool churn elsewhere touched enough other entries to sort
// them out of a shared top-N window. Bounds how many distinct channels a single relay can
// rack up (mainly a guard against dynamic per-pubkey channels — profile backfill, event
// lookups — piling up on one very active relay) without that relay's churn ever affecting
// any other relay's entries.
internal const val MAX_REQUESTS_PER_RELAY = 30

// Channels whose authors are routed per-relay via feedAuthorsPerRelay (gossip/outbox
// model: ask each relay only about the followed authors it actually covers) instead of
// broadcasting the full author list to every eligible relay. reapplyPreciseRoutedChannels
// (EventRepositoryImpl, facade-owned) also reads this.
internal val PRECISE_ROUTED_CHANNEL_IDS = setOf(NostrChannels.FEED_NOTES, NostrChannels.FEED_PROFILES_ONDEMAND)

/**
 * Channel/subscription-routing collaborator extracted from [EventRepositoryImpl].
 * Constructor shape and manual-instantiation style follow [NegentropySyncOrchestrator]'s
 * precedent: a package-`internal class`, manually constructed by the facade (not Hilt-injected),
 * given the same shared-mutable-state instances the facade itself retains — so writes from either
 * side stay mutually visible with no new synchronization.
 */
internal class EventChannelRouting(
    private val nostrClient: NostrClient,
    private val connectedRelays: ConcurrentHashMap<String, Relay>,
    private val feedSinceByRelay: ConcurrentHashMap<String, Long>,
    private val outboxInboxSinceByRelay: ConcurrentHashMap<Pair<String, String>, Long>,
    private val eventLookupTriedByRelay: ConcurrentHashMap<String, MutableSet<String>>,
    private val authorHydrationTriedByRelay: ConcurrentHashMap<String, MutableSet<String>>,
    private val discoveredRelayLastNeededAtMillis: ConcurrentHashMap<String, Long>,
    private val relayRequests: MutableStateFlow<List<RelayRequestInfo>>,
    private val activeSessionAuthors: () -> Set<String>,
    private val feedAuthorsPerRelay: () -> Map<String, Set<String>>,
    private val authorsWithKnownOutbox: () -> Set<String>
) {

    /**
     * True if applying [channelId] to [relayUrl] would push it past the relay's own advertised
     * NIP-11 `limitation.max_subscriptions`, budgeting proactively instead of waiting for the
     * relay to actually reject something first (see [nostrClient]'s reactive
     * `isSubscriptionLimited`, checked just above this). A relay with no advertised limit, or an
     * implausible non-positive one, is never gated here — absence of a stated cap isn't evidence
     * of a real one. [channelId] already having a subscription on this relay doesn't count against
     * the budget: this is only about *adding a new* subscription, not keeping an existing one.
     */
    private fun wouldExceedAdvertisedSubscriptionLimit(relayUrl: String, channelId: String): Boolean {
        val maxSubscriptions = connectedRelays[relayUrl]?.relayInfo?.maxSubscriptions
        if (maxSubscriptions == null || maxSubscriptions <= 0) return false
        if (nostrClient.currentSubscriptionId(relayUrl, channelId) != null) return false
        return nostrClient.subscribedChannelCount(relayUrl) >= maxSubscriptions
    }

    private fun canApplyChannel(relayUrl: String, channelId: String): Boolean {
        val relay = connectedRelays[relayUrl] ?: return false
        return canApplyChannelToRelay(
            relay = relay,
            isInboxChannel = isInboxChannel(channelId),
            isOutboxChannel = isOutboxChannel(channelId),
            isFeedChannel = isFeedChannel(channelId),
            isOutboxSweepChannel = channelId == NostrChannels.FEED_OUTBOX_SWEEP
        )
    }

    private fun isInboxChannel(channelId: String): Boolean =
        channelId.startsWith("$INBOX_CHANNEL_FAMILY-")

    private fun isOutboxChannel(channelId: String): Boolean =
        channelId.startsWith("$OUTBOX_CHANNEL_FAMILY-")

    private fun isFeedChannel(channelId: String): Boolean =
        channelId.startsWith("$FEED_CHANNEL_FAMILY-")

    // Channels eligible for authorHydrationTriedByRelay's per-relay exclusion — see that
    // property's doc comment. Deliberately excludes referenced-author-hydration-* batches
    // (TrackReferencedAuthorUseCase): each batch is a fresh one-time channel whose author set
    // never grows after creation, so there's no "same relay re-asked as the pool grows" scenario
    // for the exclusion to help with, and BackfillProfileUseCase/BootstrapOwnProfileUseCase's
    // single-author metadata channels for the same reason.
    internal fun isAuthorHydrationChannel(channelId: String): Boolean =
        channelId == NostrChannels.FEED_PROFILES_ONDEMAND ||
            channelId.startsWith(NostrChannels.PROFILE_FOLLOWS_META_PREFIX)

    /**
     * @return true if a REQ was actually sent to the relay, false if withheld for any reason (not
     * connected, throttled, unsupported, precise routing excluded it, an unchanged no-op, etc.) —
     * see [NostrClient.applyChannel] for the transport-level portion of that decision.
     */
    internal fun applyChannelToRelay(relayUrl: String, channelId: String, filters: List<EventFilter>): Boolean {
        if (!nostrClient.isConnected(relayUrl)) {
            return false
        }
        if (nostrClient.isThrottled(relayUrl)) {
            // This relay recently told us to slow down or that we're not welcome — withhold new
            // REQs until the throttle window passes instead of repeating the volume that
            // triggered it (see UmbraNostrClient.applyThrottleIfNeeded).
            return false
        }
        if (nostrClient.isReqUnsupported(relayUrl)) {
            // This relay already told us it closes every REQ (broadcast-only aggregator, e.g.
            // nosflare's "sendit" variant) — stop asking, it will never work.
            return false
        }
        if (nostrClient.requiresSearchFilter(relayUrl) && filters.none { !it.search.isNullOrBlank() }) {
            // This relay already told us it only accepts REQs carrying a NIP-50 search term
            // (e.g. searchnos) — withhold non-search REQs instead of getting closed every time.
            return false
        }
        if (nostrClient.isSubscriptionLimited(relayUrl) && !ChannelPriority.isEssential(channelId)) {
            // This relay already told us it's over its concurrent-subscription count — keep
            // sending the essential channels (own notes/DMs/interactions) and withhold background
            // ones (on-demand hydration, the outbox sweep, search, per-pubkey backfill/lookup)
            // instead of fighting for the same limited slots and getting closed repeatedly.
            return false
        }
        if (!ChannelPriority.isEssential(channelId) && wouldExceedAdvertisedSubscriptionLimit(relayUrl, channelId)) {
            // Proactive counterpart to the reactive isSubscriptionLimited check above: a relay
            // that advertises NIP-11 limitation.max_subscriptions doesn't need to actually reject
            // a REQ first before Umbra starts respecting it — budget against the advertised number
            // from the start instead of always finding out the hard way.
            return false
        }
        if (!canApplyChannel(relayUrl, channelId)) {
            return false
        }
        val preciselyRoutedFilters = applyPerRelayOutboxInboxSince(
            relayUrl, channelId,
            applyPerRelayFeedSince(relayUrl, channelId, routeFiltersPrecisely(relayUrl, channelId, filters))
        )
        if (preciselyRoutedFilters.isEmpty()) {
            // Precise routing determined this relay covers none of the requested authors —
            // skip it entirely rather than sending an empty/unscoped REQ.
            return false
        }
        val lookupFilteredFilters = excludeAlreadyTriedEventLookupIds(relayUrl, channelId, preciselyRoutedFilters)
        if (lookupFilteredFilters.isEmpty()) {
            // Every id in this REQ is one this relay already told us (via EOSE) it doesn't have —
            // skip it rather than re-asking about a known-exhausted id set.
            return false
        }
        val effectiveFilters = excludeAlreadyTriedAuthorHydration(relayUrl, channelId, lookupFilteredFilters)
        if (effectiveFilters.isEmpty()) {
            // Every author in this REQ is one this relay already told us (via EOSE) it has no
            // profile for — skip it rather than re-asking about a known-exhausted author set.
            return false
        }
        // If any filter uses the NIP-50 search field, only send to relays that advertise NIP-50 support.
        // Relays without NIP-50 ignore the search field and return unfiltered results (wasted traffic).
        val hasSearchFilter = effectiveFilters.any { !it.search.isNullOrBlank() }
        if (hasSearchFilter && !relaySupportsNip(connectedRelays[relayUrl], nip = 50)) {
            return false
        }
        // A relay that advertises a lower NIP-11 `limitation.max_limit` than what we're about to
        // ask for will otherwise silently truncate or reject the REQ — clamp down to what it told
        // us it actually supports instead of always sending our own default.
        val clampedFilters = clampFiltersToRelayLimit(
            filters = effectiveFilters,
            relayMaxLimit = connectedRelays[relayUrl]?.relayInfo?.maxLimitEventCount
        )
        val applied = nostrClient.applyChannel(channelId, relayUrl, clampedFilters)
        // Unconditional on `applied`, not just a successful send: this relay having reached every
        // gate above (connected, not throttled/limited, routing-eligible, still covering this
        // channel's authors after precise routing) is itself evidence the relay is still needed,
        // regardless of whether applyChannel's own no-op dedup happened to skip the actual REQ.
        if (connectedRelays[relayUrl]?.isDiscovered == true &&
            DiscoveredRelayIdlePolicy.reflectsSpecificNeed(channelId, PRECISE_ROUTED_CHANNEL_IDS)
        ) {
            // Reset the idle clock only for a REQ that reflects specific need for this relay —
            // see DiscoveredRelayIdlePolicy.reflectsSpecificNeed's doc comment.
            discoveredRelayLastNeededAtMillis[relayUrl] = System.currentTimeMillis()
        }
        return applied
    }

    /**
     * Gossip/outbox-model routing for [PRECISE_ROUTED_CHANNEL_IDS]: instead of asking every
     * eligible relay about the full followed-author list, ask each relay only about the authors
     * it actually covers (per feedAuthorsPerRelay). Authors outside authorsWithKnownOutbox —
     * either a followed author whose own outbox isn't cached yet, or a reply/mention author who
     * isn't followed at all — have no reliable routing data, so they're kept unscoped on general
     * relays rather than silently dropped. Discovered relays are excluded from that unscoped
     * fallback (see scopeAuthorsForRelay): they were added specifically because they cover a
     * known handful of authors, not to be asked about everyone else too. No-op for other
     * channels.
     */
    private fun routeFiltersPrecisely(relayUrl: String, channelId: String, filters: List<EventFilter>): List<EventFilter> {
        if (channelId !in PRECISE_ROUTED_CHANNEL_IDS || activeSessionAuthors().isEmpty()) return filters
        val (authorScoped, unscoped) = filters.partition { it.authors.isNotEmpty() }
        if (authorScoped.isEmpty()) return filters

        val isDiscoveredRelay = connectedRelays[relayUrl]?.isDiscovered == true
        val requestedAuthors = authorScoped.flatMap { it.authors }.toSet()
        val scopedAuthors = scopeAuthorsForRelay(
            relayUrl = normalizeRelayUrl(relayUrl),
            requestedAuthors = requestedAuthors,
            authorsWithKnownOutbox = authorsWithKnownOutbox(),
            authorsPerRelay = feedAuthorsPerRelay(),
            includeUnknownAuthors = !isDiscoveredRelay
        )
        if (scopedAuthors == requestedAuthors) return filters

        val template = authorScoped.first()
        val reScopedAuthorFilters = when {
            scopedAuthors.isEmpty() -> emptyList()
            scopedAuthors.size <= MAX_AUTHORS_PER_FEED_FILTER -> listOf(template.copy(authors = scopedAuthors))
            else -> scopedAuthors.chunked(MAX_AUTHORS_PER_FEED_FILTER).map { chunk -> template.copy(authors = chunk.toSet()) }
        }
        return unscoped + reScopedAuthorFilters
    }

    /**
     * For [NostrChannels.FEED_NOTES] only: overrides `since` with [relayUrl]'s own EOSE-confirmed
     * watermark when known (see [feedSinceByRelay]/[FeedRelaySincePolicy]), instead of the global
     * cursor every relay's filters start with. No-op for every other channel, and a no-op until
     * this relay has reported at least one EOSE for FEED_NOTES.
     */
    private fun applyPerRelayFeedSince(relayUrl: String, channelId: String, filters: List<EventFilter>): List<EventFilter> {
        if (channelId != NostrChannels.FEED_NOTES) return filters
        return FeedRelaySincePolicy.overrideSince(filters, feedSinceByRelay[relayUrl])
    }

    /**
     * For [MERGEABLE_BACKFILL_CHANNEL_IDS] (OUTBOX_NOTES/INBOX_NOTES) only: overrides each *live*
     * filter's `since` with [relayUrl]'s own EOSE-confirmed watermark (outboxInboxSinceByRelay)
     * once known — see [OutboxInboxRelaySincePolicy]'s doc comment for why. A combined REQ built
     * by applyBackfillOverlay carries both the channel's live filters (never set `until`) and the
     * backfill overlay's own backward-window filter (always sets `until`, paired with its own
     * correct `since`); only the `until == null` ones are eligible for this override; the backfill
     * window filter is passed through untouched, or backward pagination would silently break. No-op
     * for every other channel, and a no-op until this relay has reported at least one EOSE for
     * [channelId].
     */
    private fun applyPerRelayOutboxInboxSince(relayUrl: String, channelId: String, filters: List<EventFilter>): List<EventFilter> {
        if (channelId !in MERGEABLE_BACKFILL_CHANNEL_IDS) return filters
        val perRelaySince = outboxInboxSinceByRelay[relayUrl to channelId] ?: return filters
        val (liveFilters, boundedFilters) = filters.partition { it.until == null }
        return OutboxInboxRelaySincePolicy.overrideSince(liveFilters, perRelaySince) + boundedFilters
    }

    /**
     * For [NostrChannels.EVENT_LOOKUP] only: drops ids from the filter that [relayUrl] already
     * told us (via an earlier EOSE) it doesn't have — see [eventLookupTriedByRelay]'s doc comment.
     * A filter left with no ids after this is dropped entirely (callers treat an empty result as
     * "skip this relay", same as [routeFiltersPrecisely]). No-op for every other channel.
     */
    private fun excludeAlreadyTriedEventLookupIds(relayUrl: String, channelId: String, filters: List<EventFilter>): List<EventFilter> {
        if (channelId != NostrChannels.EVENT_LOOKUP) return filters
        val tried = eventLookupTriedByRelay[relayUrl]
        if (tried.isNullOrEmpty()) return filters
        return filters.mapNotNull { filter ->
            if (filter.ids.isEmpty()) return@mapNotNull filter
            val remaining = filter.ids - tried
            if (remaining.isEmpty()) null else filter.copy(ids = remaining, limit = remaining.size)
        }
    }

    /**
     * For [isAuthorHydrationChannel] channels only: drops authors from the filter that [relayUrl]
     * already told us (via an earlier EOSE) it has no profile for — see
     * [authorHydrationTriedByRelay]'s doc comment. `limit` is left as originally computed (sized
     * for the full author set × hydration kind count) rather than recomputed for the shrunk
     * author set — a limit that's too generous is harmless, unlike one that's too tight. A filter
     * left with no authors after this is dropped entirely (same "skip this relay" convention as
     * [excludeAlreadyTriedEventLookupIds]/[routeFiltersPrecisely]). No-op for every other channel.
     */
    private fun excludeAlreadyTriedAuthorHydration(relayUrl: String, channelId: String, filters: List<EventFilter>): List<EventFilter> {
        if (!isAuthorHydrationChannel(channelId)) return filters
        val tried = authorHydrationTriedByRelay[relayUrl]
        if (tried.isNullOrEmpty()) return filters
        return filters.mapNotNull { filter ->
            if (filter.authors.isEmpty()) return@mapNotNull filter
            val remaining = filter.authors - tried
            if (remaining.isEmpty()) null else filter.copy(authors = remaining)
        }
    }

    internal fun fingerprint(filters: List<EventFilter>): String {
        return filters
            .joinToString(separator = "||") { filter ->
                listOf(
                    "ids=${filter.ids.sorted().joinToString(",")}",
                    "authors=${filter.authors.sorted().joinToString(",")}",
                    "kinds=${filter.kinds.sorted().joinToString(",")}",
                    "since=${filter.since ?: ""}",
                    "until=${filter.until ?: ""}",
                    "limit=${filter.limit}",
                    "tags=${filter.tagFilters.toSortedMap().entries.joinToString(";") { (k, v) -> "$k=${v.sorted().joinToString(",")}" }}",
                    "search=${filter.search ?: ""}"
                ).joinToString("|")
            }
    }

    /**
     * Collapses filters that differ only in `kinds` (same authors/tags/ids/time window/limit/
     * everything else) into one filter with the union of their kinds. A windowed backfill page
     * for OUTBOX_NOTES/INBOX_NOTES starts as two filters — notes/deletions and reactions/reposts —
     * that, once both get the same `since`/`until`/`limit` window stamped on them, are otherwise
     * identical; sending them as two separate filters in the same REQ cost an extra filter slot
     * for no behavioral difference (NIP-01 `kinds` already accepts a set). No-op (returns [filters]
     * unchanged) for anything that isn't actually mergeable this way, e.g. FEED_NOTES' per-author
     * chunks, which differ in `authors` too.
     */
    internal fun mergeSameScopeFilters(filters: List<EventFilter>): List<EventFilter> {
        if (filters.size <= 1) return filters
        val template = filters.first()
        val sameScope = filters.all { it.copy(kinds = template.kinds) == template }
        if (!sameScope) return filters
        return listOf(template.copy(kinds = filters.flatMapTo(mutableSetOf()) { it.kinds }))
    }

    internal fun upsertSubscriptionInfo(request: RelayRequestInfo) {
        val now = System.currentTimeMillis()
        // request arrives from nostrClient.reqFlow knowing only relayUrl/subscriptionId/
        // filters — recover the internal channelId that produced this subId via NostrClient's
        // stable stamp, so the UI can show *why* this subscription exists even though the
        // wire-level subscriptionId itself is pure random and carries no purpose info.
        val channelId = nostrClient.resolveChannelId(request.relayUrl, request.subscriptionId)
        val type = SubscriptionType.fromChannelId(channelId)
        relayRequests.update { existing ->
            val previous = existing.firstOrNull {
                it.relayUrl == request.relayUrl && it.subscriptionId == request.subscriptionId
            }
            val merged = request.copy(
                receivedEventCount = previous?.receivedEventCount ?: 0,
                lastEventAtMillis = previous?.lastEventAtMillis,
                sentAtMillis = previous?.sentAtMillis ?: request.sentAtMillis,
                updatedAtMillis = now,
                type = type
            )
            upsertBoundedRelayRequest(existing, merged, MAX_REQUESTS_PER_RELAY)
        }
    }

    internal fun incrementSubscriptionEventCount(relayUrl: String, subscriptionId: String) {
        val now = System.currentTimeMillis()
        relayRequests.update { existing ->
            val index = existing.indexOfFirst {
                it.relayUrl == relayUrl && it.subscriptionId == subscriptionId
            }
            if (index < 0) return@update existing
            val current = existing[index]
            val updatedEntry = current.copy(
                receivedEventCount = current.receivedEventCount + 1,
                lastEventAtMillis = now,
                updatedAtMillis = now
            )
            // No re-bounding needed here — replacing an existing entry in place can't grow the
            // list past what upsertSubscriptionInfo already bounded it to.
            existing.toMutableList().also { it[index] = updatedEntry }
        }
    }

    internal fun removeSubscriptionInfo(relayUrl: String, subscriptionId: String) {
        relayRequests.update { list ->
            list.filterNot { it.relayUrl == relayUrl && it.subscriptionId == subscriptionId }
        }
    }
}
