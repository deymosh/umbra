package com.umbra.app.domain.nip77

/**
 * One record in a Negentropy storage vector: a Nostr event's (id, timestamp). [id] is the
 * lowercase 64-hex event id (32 bytes) — same representation used everywhere else in this
 * codebase (see [com.umbra.app.domain.nip01.Event.id]), converted to raw bytes only at the wire
 * boundary. [timestamp] is Unix seconds, matching [com.umbra.app.domain.nip01.Event.createdAt].
 */
internal data class NegentropyItem(val id: String, val timestamp: Long)

/** Sort order the protocol requires: ascending timestamp, then lexically ascending id. */
internal val NEGENTROPY_ITEM_COMPARATOR: Comparator<NegentropyItem> =
    compareBy<NegentropyItem> { it.timestamp }.thenBy { it.id }

/** Reserved "infinity" timestamp — the upper bound of the full timestamp/ID universe. Real Unix
 * timestamps never approach [Long.MAX_VALUE], so it's safe to use directly as the sentinel
 * instead of modeling the spec's literal max-uint64 value. */
internal const val NEGENTROPY_INFINITY_TIMESTAMP = Long.MAX_VALUE

/**
 * An *exclusive* upper bound for a Range: a timestamp plus a variable-length disambiguating
 * prefix of an item id (empty when the timestamp alone is enough to separate two adjacent items).
 */
internal data class NegentropyBound(
    val timestamp: Long,
    val idPrefixHex: String = ""
)
