package com.umbra.app.domain.relay

/**
 * Upserts [request] into [current] (replacing any existing entry for the same relayUrl +
 * subscriptionId), capping how many distinct subscriptions any single relay may hold at
 * [maxPerRelay] — evicting that SAME relay's own least-recently-updated entry when already at the
 * cap, never another relay's.
 *
 * Each entry represents one currently-active subscription, not a growing history log, so this
 * cap exists to protect against one relay accumulating an unbounded number of dynamic per-pubkey
 * channels (profile backfill, event lookups) — not to trim "old" data, since every entry here is
 * live. A single global cap on the whole ledger (the previous design) let relay-pool churn evict
 * a *different*, still-active relay's subscriptions once total pressure got high — including the
 * user's own outbox/inbox ones, even though they were never actually closed on the wire.
 */
internal fun upsertBoundedRelayRequest(
    current: List<RelayRequestInfo>,
    request: RelayRequestInfo,
    maxPerRelay: Int
): List<RelayRequestInfo> {
    val withoutExisting = current.filterNot {
        it.relayUrl == request.relayUrl && it.subscriptionId == request.subscriptionId
    }
    val forThisRelay = withoutExisting.filter { it.relayUrl == request.relayUrl }
    val trimmed = if (forThisRelay.size >= maxPerRelay) {
        val oldestForThisRelay = forThisRelay.minByOrNull { it.updatedAtMillis }
        if (oldestForThisRelay != null) withoutExisting - oldestForThisRelay else withoutExisting
    } else {
        withoutExisting
    }
    return trimmed + request
}
