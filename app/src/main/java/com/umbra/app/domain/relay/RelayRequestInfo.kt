package com.umbra.app.domain.relay

import com.umbra.app.domain.nip01.EventFilter

/**
 * Debug info for active Nostr subscriptions per relay+subId.
 *
 * Carries the actual structured [EventFilter] list, not a pre-flattened debug string — the UI
 * (RelayConfigScreen's SubscriptionCard/FilterCard) renders kind names, author counts, etc.
 * directly off typed fields instead of regex-parsing a string that already discarded the
 * structure once.
 */
data class RelayRequestInfo(
    val relayUrl: String,
    val subscriptionId: String,
    val filters: List<EventFilter> = emptyList(),
    val receivedEventCount: Int = 0,
    val sentAtMillis: Long = System.currentTimeMillis(),
    val lastEventAtMillis: Long? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    // Resolved once (see EventRepositoryImpl.upsertSubscriptionInfo) from the internal channel id
    // that produced this subscription — the wire-level subscriptionId itself is pure random
    // (see randomSubscriptionId()) and carries no purpose info to derive this from anymore.
    val type: SubscriptionType = SubscriptionType.OTHER
)

/**
 * [RelayRequestInfo] grouped by [SubscriptionType.family] — the same purpose taxonomy the
 * per-relay Relay Details screen and the cross-relay Active Subscriptions screen both render
 * sections from. A single shared function so both screens agree on what "outbox"/"inbox"/"feed"
 * mean, and so neither of them silently drops a subscription whose type doesn't belong to one of
 * the three known families (search/lookup/backfill channels, etc.) into nowhere — those land in
 * [other] instead of vanishing.
 */
data class SubscriptionsByPurpose(
    val outbox: List<RelayRequestInfo>,
    val inbox: List<RelayRequestInfo>,
    val feed: List<RelayRequestInfo>,
    val other: List<RelayRequestInfo>
)

fun List<RelayRequestInfo>.groupByPurpose(): SubscriptionsByPurpose {
    val outbox = mutableListOf<RelayRequestInfo>()
    val inbox = mutableListOf<RelayRequestInfo>()
    val feed = mutableListOf<RelayRequestInfo>()
    val other = mutableListOf<RelayRequestInfo>()
    forEach { req ->
        when (req.type.family) {
            SubscriptionFamily.OUTBOX -> outbox += req
            SubscriptionFamily.INBOX -> inbox += req
            SubscriptionFamily.FEED -> feed += req
            SubscriptionFamily.OTHER -> other += req
        }
    }
    return SubscriptionsByPurpose(outbox, inbox, feed, other)
}


