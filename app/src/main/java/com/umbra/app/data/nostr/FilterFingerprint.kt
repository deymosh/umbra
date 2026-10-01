package com.umbra.app.data.nostr

import com.umbra.app.domain.nip01.EventFilter

/**
 * Stable, order-independent string fingerprint of [filters], used to detect whether a REQ would be
 * a no-op reapply. Shared by [RelaySubscriptionRegistry] (per-(relay, channel) no-op dedup) and the
 * channel-routing layer, which previously carried two identical private copies of this logic — one
 * definition keeps the two dedup paths agreeing on what "unchanged" means. Elements inside each
 * set/map are sorted so structurally-equal filters always fingerprint the same regardless of
 * iteration order.
 */
internal fun filterFingerprint(filters: List<EventFilter>): String {
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
