package com.umbra.app.data.nostr

import com.umbra.app.domain.relay.RelayIssueKind

/**
 * Pure classification of a relay's NOTICE/CLOSED reason text into a [RelayIssueKind] — extracted
 * from `UmbraNostrClient` so it's unit-testable without instantiating the WebSocket client.
 */
internal fun classifyRelayNotice(notice: String): RelayIssueKind {
    val n = notice.lowercase()
    return when {
        // Checked before the RATE_LIMIT case below since both can contain "too many" — a
        // subscription-count complaint always also names the concept it's counting
        // (subscription/concurrent/req), which a request-rate complaint doesn't.
        isSubscriptionLimitMessage(n) -> RelayIssueKind.SUBSCRIPTION_LIMIT
        isNegentropyUnsupportedMessage(n) -> RelayIssueKind.NEGENTROPY_UNSUPPORTED
        n.contains("rate") || n.contains("too many") || n.contains("429") || n.contains("throttle") -> RelayIssueKind.RATE_LIMIT
        n.contains("blocked") || n.contains("banned") || n.contains("forbidden") ||
            n.contains("not allowed") || n.contains("not permitted") || n.contains("not welcome") -> RelayIssueKind.BLOCKED
        n.contains("auth") || n.contains("nip-42") || n.contains("restricted") -> RelayIssueKind.AUTH
        else -> RelayIssueKind.NOTICE
    }
}

/**
 * A relay complaining about concurrent-subscription count (e.g. "too many concurrent REQs",
 * "subscription limit reached", "max subscriptions exceeded") rather than request *rate* —
 * distinct from [RelayIssueKind.RATE_LIMIT], which means "same volume, slow down," this means
 * "fewer things open at once." See `ChannelPriority` for how Umbra responds: withhold
 * non-essential channels from a relay that's told us this, instead of repeating the same channel
 * count and getting closed again.
 */
private val REQ_WORD_REGEX = Regex("""\breqs?\b""")

internal fun isSubscriptionLimitMessage(n: String): Boolean {
    // "req"/"reqs" matched as a whole word only — "too many requests" must NOT match via a bare
    // substring check (it contains "req" as part of "requests"), or every plain rate-limit notice
    // mentioning "requests" would be misclassified as a subscription-count complaint instead.
    val mentionsSubscriptionConcept = n.contains("subscription") || n.contains("concurrent") ||
        REQ_WORD_REGEX.containsMatchIn(n)
    val mentionsCountLimit = n.contains("too many") || n.contains("max") ||
        n.contains("limit") || n.contains("exceed")
    return mentionsSubscriptionConcept && mentionsCountLimit
}

/**
 * A relay rejecting NEG-OPEN with a generic NOTICE because NIP-77 is disabled/unimplemented at
 * runtime, even though its NIP-11 document may still list 77 in `supported_nips` (e.g. strfry
 * with negentropy compiled in but turned off in config) — e.g. `["NOTICE", "ERROR: bad msg:
 * negentropy disabled"]`. NIP-77 has no CLOSED-style per-subscription rejection for "I don't
 * recognize/allow this command", so a generic top-level NOTICE is the only signal a client gets.
 */
internal fun isNegentropyUnsupportedMessage(n: String): Boolean {
    return n.contains("negentropy") &&
        (n.contains("disabled") || n.contains("not support") || n.contains("unsupported") || n.contains("not enabled"))
}
