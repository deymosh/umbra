package com.umbra.app.data.repository

import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.normalizeRelayUrl

/**
 * Whether a channel's REQ should be sent to [relay]. Discovered relays (auto-added by
 * UserRepositoryImpl.saveRelayList() to reach a tracked author's outbox) are excluded from the
 * outbox-sweep channel: a relay added specifically because it covers a handful of known authors
 * has no business being asked to help discover everyone ELSE's outbox too — that's the general
 * relay pool's job, and duplicating it across every discovered relay is exactly the kind of
 * avoidable request volume that gets a client rate-limited or blocked.
 *
 * Inbox (#p-tag "mentions me") channels DO include discovered relays, unlike outbox-sweep — by
 * strict NIP-65 gossip-model protocol, mentions of you belong on your own configured read/inbox
 * relays, not a followed author's outbox, but in practice plenty of clients aren't outbox-aware
 * and just publish a reply to whatever relays they already have open (often that author's own
 * outbox, which is exactly what a discovered relay is). The inbox filter itself stays narrow
 * (`#p == me`, server-side filtered) regardless of how many relays it's sent to, so this is a
 * coverage win against non-compliant repliers, not a broadcast-volume concern the way an
 * unscoped author-list REQ would be.
 *
 * [Relay.isReadActive]/[Relay.isWriteActive] now exclusively reflect a genuine kind:10002 (own
 * NIP-65) declaration — see UserRepositoryImpl.applyRelayListToLocalConfig's doc comment. A relay
 * added only for a narrower secondary purpose (discovered-for-gossip, or the user's own NIP-51
 * search/index relay list) no longer carries a real isReadActive of its own, but it's still a
 * relay Umbra chose to add and general read traffic should still reach it — plenty of relays and
 * clients don't respect the outbox model strictly, and excluding these would just silently narrow
 * coverage for no protocol-correctness gain. DM relays (kind 10050) are deliberately NOT included
 * here — they're a distinct private-message transport, not meant to be swept for public content.
 *
 * isOutboxChannel (OUTBOX_PROFILE/OUTBOX_NOTES) deliberately does NOT fall
 * back to isDiscovered, unlike inbox/feed above — these are persistent, always-on channels
 * (resubscribed to every connected relay, forever, for as long as the session is active), so
 * broadening them to the whole discovered pool would mean broadcasting the same REQs to
 * potentially hundreds of relays continuously, not once. A brand-new/freshly logged-in account
 * with no write-active relay yet still needs its own kind:0/kind:3/kind:10002 discovered from
 * somewhere — that cold-start bootstrap is handled by BootstrapOwnProfileUseCase instead, a
 * one-shot batched hydration REQ (same mechanism TrackReferencedAuthorUseCase already uses for a
 * mentioned/quoted author's profile, which reaches isDiscovered relays via the unclassified
 * "else" branch below) fired once at session start and closed on EOSE/timeout, not a standing
 * subscription.
 *
 * Feed also falls back to isSearchActive/isIndexActive, same as the unclassified "else" branch —
 * a relay the user declared for NIP-50 search or content indexing is still a real, reachable
 * relay worth pulling the normal timeline from too, not just its narrow declared purpose. Unlike
 * outbox above, this is safe to broaden this way precisely because it already falls back to
 * isDiscovered for the same reason.
 */
internal fun canApplyChannelToRelay(
    relay: Relay,
    isInboxChannel: Boolean,
    isOutboxChannel: Boolean,
    isFeedChannel: Boolean,
    isOutboxSweepChannel: Boolean = false
): Boolean = when {
    // Feed is a read operation, same as inbox — a write-only relay is one the user
    // explicitly opted out of reading from, so it shouldn't get feed REQs either. NIP-65
    // only has two roles (read/write); "feed" isn't a third relay role, just a different
    // query sent to the same read-enabled pool inbox queries go to. Unlike inbox, feed
    // still applies to discovered relays — that's their entire purpose — and to search/index-
    // active relays, for the same "still a real relay" reasoning.
    isInboxChannel -> relay.isReadActive || relay.isDiscovered
    isOutboxChannel -> relay.isWriteActive
    isOutboxSweepChannel -> relay.isReadActive && !relay.isDiscovered
    isFeedChannel -> relay.isReadActive || relay.isDiscovered || relay.isSearchActive || relay.isIndexActive
    // Anything else (e.g. NIP-50 search) — same "still its purpose" reasoning as feed/inbox.
    else -> relay.isReadActive || relay.isDiscovered || relay.isSearchActive || relay.isIndexActive
}

/**
 * Whether [relay] has confirmed support for a given NIP via its own NIP-11 document. Used to
 * gate protocol features relays aren't required to implement (NIP-45 COUNT, NIP-50 search) —
 * a relay we haven't fetched info for yet, or one whose document doesn't list the NIP, is
 * treated as unsupported rather than assumed-supported: sending an unsupported COUNT or search
 * is a wasted REQ at best and, on some relays, an error/NOTICE response.
 */
internal fun relaySupportsNip(relay: Relay?, nip: Int): Boolean =
    relay?.relayInfo?.supportedNips?.contains(nip) == true

/**
 * Reduces each filter's `limit` down to [relayMaxLimit] (a relay's own NIP-11
 * `limitation.max_limit`) whenever our own limit asks for more than the relay says it supports —
 * a relay commonly rejects or silently truncates a REQ whose `limit` exceeds this, so honoring it
 * up front avoids the wasted round trip. A relay with no advertised max (`relayMaxLimit == null`)
 * or an implausible non-positive one is left alone: absence of a stated cap isn't evidence of a
 * lower one.
 */
internal fun clampFiltersToRelayLimit(filters: List<EventFilter>, relayMaxLimit: Int?): List<EventFilter> {
    if (relayMaxLimit == null || relayMaxLimit <= 0) return filters
    return filters.map { filter ->
        if (filter.limit > relayMaxLimit) filter.copy(limit = relayMaxLimit) else filter
    }
}

/**
 * For each followed author whose own NIP-65 write/outbox relays are already known, resolves
 * which relay(s) their content (notes, profile metadata) should actually be requested from.
 * NIP-65 outbox-model routing, with a relay-hint-from-tags fallback tier (`hintRelaysFor`, fed by
 * [EventRepository.recordRelayHint]) for an author
 * whose outbox isn't declared/cached yet but has been *seen hinted* at a relay (a NIP-19
 * nprofile1/nevent1 TLV hint) — weaker signal than a real kind:10002 declaration, but still real
 * routing data, not a guess.
 *
 * Beyond that fallback, there is still NO further tier for an author with neither a known outbox
 * nor any seen hint — that used to fall back to a fixed relay list (first a hardcoded constant,
 * then a snapshot of connectedRelays), but both are wrong: a relay a user isn't actually
 * connected to routes nowhere, and a connected-pool snapshot goes stale the instant a relay
 * connects asynchronously after the snapshot was taken (which, over Tor, is the common case —
 * connections trickle in over seconds). An author simply absent from this map's values is handled
 * by the caller (scopeAuthorsForRelay) as "unknown": always broadcast to every eligible relay,
 * exactly like pre-precise-routing behavior, until their real outbox (or a hint) is learned and
 * this recomputes — self-correcting without needing to track relay-connection timing at all.
 *
 * Returns normalized relay URL -> the set of authors that relay actually covers.
 */
// Known aggregator/paid-filter relays excluded from outbox routing. These don't actually host an
// author's own content the way a real NIP-65 write relay does — routing author-scoped feed REQs
// to them wastes the request without improving coverage.
private val OUTBOX_ROUTING_EXCLUDED_HOSTS = setOf(
    "feeds.nostr.band",
    "filter.nostr.wine",
    "nwc.primal.net",
    "relay.getalby.com"
)

internal fun isOutboxRoutingExcluded(normalizedRelayUrl: String): Boolean =
    OUTBOX_ROUTING_EXCLUDED_HOSTS.any { normalizedRelayUrl.contains(it, ignoreCase = true) }

internal fun computeAuthorsPerRelay(
    followedPubkeys: Set<String>,
    outboxRelaysFor: (String) -> List<String>,
    hintRelaysFor: (String) -> List<String> = { emptyList() }
): Map<String, Set<String>> {
    val result = HashMap<String, MutableSet<String>>()
    followedPubkeys.forEach { pubkey ->
        val declaredOutbox = outboxRelaysFor(pubkey)
        val relays = declaredOutbox.ifEmpty { hintRelaysFor(pubkey) }
        relays
            .map(::normalizeRelayUrl)
            .filter { it.isNotBlank() && !isOutboxRoutingExcluded(it) }
            .forEach { url -> result.getOrPut(url) { mutableSetOf() }.add(pubkey) }
    }
    return result
}

/**
 * Scopes [requestedAuthors] down to the subset [relayUrl] actually covers, per
 * [authorsPerRelay] (see [computeAuthorsPerRelay]). Authors outside [authorsWithKnownOutbox] —
 * either a followed author whose outbox isn't cached yet, or a reply/mention author who isn't
 * followed at all — have no reliable routing data. On a general relay ([includeUnknownAuthors]
 * true) they're kept unscoped: better to over-ask than silently drop their profile/notes. On a
 * narrow-purpose discovered relay ([includeUnknownAuthors] false) they're dropped instead — a
 * relay added specifically because it covers a handful of known authors shouldn't also be
 * blasted with every other still-unresolved author's REQ on every cycle; that's exactly the
 * kind of avoidable request volume that gets a client rate-limited or blocked.
 */
internal fun scopeAuthorsForRelay(
    relayUrl: String,
    requestedAuthors: Set<String>,
    authorsWithKnownOutbox: Set<String>,
    authorsPerRelay: Map<String, Set<String>>,
    includeUnknownAuthors: Boolean = true
): Set<String> {
    if (requestedAuthors.isEmpty()) return requestedAuthors
    val relayAuthors = authorsPerRelay[relayUrl].orEmpty()
    val known = requestedAuthors.filterTo(HashSet()) { it in authorsWithKnownOutbox }
    val unknown = if (includeUnknownAuthors) requestedAuthors - known else emptySet()
    return (known intersect relayAuthors) + unknown
}

/**
 * Extra relay targets for publishing an event, beyond the author's own outbox — the inbox (read)
 * relays of every pubkey the event addresses via a `p` tag (NIP-10 reply/root participants,
 * NIP-27 mentions), per NIP-65's outbox model: a reply/mention SHOULD also reach the addressed
 * user's own inbox relays, so their client finds it even if it never reads the author's outbox.
 *
 * Restricted to relays this client is already connected to — opening a brand-new connection
 * purely to publish is relay-pool lifecycle, a bigger change than relay *selection*, and is left
 * for a follow-up. An inbox relay this client hasn't discovered/connected to yet (e.g. because it
 * has never seen that pubkey's kind:10002) is silently skipped rather than blocking the publish;
 * as soon as that relay list is discovered and connected — the same discovery path the read-side
 * gossip in [computeAuthorsPerRelay] already relies on — later publishes to that participant will
 * include it.
 */
internal fun computeInboxTargetRelays(
    participantPubkeys: Set<String>,
    connectedRelayUrls: Set<String>,
    inboxRelaysFor: (String) -> List<String>
): Set<String> {
    if (participantPubkeys.isEmpty() || connectedRelayUrls.isEmpty()) return emptySet()
    val connectedByNormalized = connectedRelayUrls.associateBy { normalizeRelayUrl(it) }
    return participantPubkeys
        .asSequence()
        .flatMap { inboxRelaysFor(it).asSequence() }
        .mapNotNull { connectedByNormalized[normalizeRelayUrl(it)] }
        .toSet()
}
