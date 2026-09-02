package com.umbra.app.domain.nip67

/**
 * NIP-67 (EOSE Completeness Hint, draft/optional): the optional third element of an
 * `["EOSE", subId, ...]` message. Most relays don't implement this NIP at all, so the common case
 * is [UNSPECIFIED] — callers must treat that the same as [FINISH] (today's pre-NIP-67 behavior),
 * never assume truncation just because the hint is absent.
 */
enum class EoseCompleteness {
    /** Relay sent every stored event matching the filter. */
    FINISH,
    /** Relay truncated/capped the results — more matching stored events exist than were sent. */
    MORE,
    /** No third element, or a value this client doesn't recognize — the relay doesn't support
     * NIP-67, or sent something unexpected. Treated identically to [FINISH] by every consumer. */
    UNSPECIFIED
}

/** Parses EOSE's optional third array element into an [EoseCompleteness]. Never throws — an
 * absent or unrecognized value degrades to [EoseCompleteness.UNSPECIFIED] rather than failing the
 * whole EOSE handling for a relay sending something unexpected. */
fun parseEoseCompleteness(rawThirdElement: String?): EoseCompleteness = when (rawThirdElement) {
    "finish" -> EoseCompleteness.FINISH
    "more" -> EoseCompleteness.MORE
    else -> EoseCompleteness.UNSPECIFIED
}

/**
 * (relayUrl, subscriptionId, completeness) observed on an EOSE frame — see NostrClient.eoseFlow.
 */
data class EoseSignal(
    val relayUrl: String,
    val subscriptionId: String,
    val completeness: EoseCompleteness = EoseCompleteness.UNSPECIFIED
)
