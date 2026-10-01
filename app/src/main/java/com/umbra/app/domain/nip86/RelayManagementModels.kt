package com.umbra.app.domain.nip86

import com.umbra.app.domain.nip11.RelayInfo

/** One entry of a NIP-86 people/events/ip list result — the identifier plus optional reason. */
data class RelayManagementEntry(
    val identifier: String,
    val reason: String? = null
)

/**
 * Result of a NIP-86 call. [Ok.data] semantics vary by method: list methods return entries,
 * ban/allow/banip/unban return the affected identifier, kind methods return the kind number,
 * and everything else returns arbitrary/null payloads the relays happen to send (usually the
 * string lists the method description names, e.g. the newly-set name for changerelayname).
 *
 * [Ok.data] is parsed leniently: entries pulled out when the relay sends shaped objects
 * ({"pubkey"/"id"/"ip", "reason?"}), the raw primitive when it sends one, whole-object raw JSON
 * when it sends an unshaped object.
 */
sealed interface RelayManagementResult {
    /** The relay answered OK. */
    data class Ok(val data: RelayManagementData) : RelayManagementResult

    /** The relay refused NIP-86 access on HTTP grounds (401/403) — Umbra is not an admin here. */
    data object NotAuthorized : RelayManagementResult

    /** The relay answered with a NIP-86 `error` string (unrecognized method, bad params, ...). */
    data class RelayError(val error: String) : RelayManagementResult

    /** Anything that prevented a well-formed response: connection failure, bad JSON, 5xx, ... */
    data class Transport(val message: String) : RelayManagementResult
}

/** Payload of a successful NIP-86 answer, parsed by the shape the relay actually sent. */
data class RelayManagementData(
    /** Parsed when the answer is an array of {"pubkey"|"id"|"ip", "reason?"} objects. */
    val entries: List<RelayManagementEntry> = emptyList(),

    /** Parsed when the answer is an array of integers (kinds). */
    val kindNumbers: List<Int> = emptyList(),

    /**
     * The answer's own JSON as flat text, for everything else — a relay echoing the name it just
     * set, an action's ack string, a bare `true`. Never throw on unexpected shapes.
     */
    val raw: String = "",

    /** True when the answer is an empty array or nothing meaningful at all. */
    val isEmpty: Boolean = entries.isEmpty() && kindNumbers.isEmpty() && raw.isEmpty()
)

/** True when the relay advertises the NIP-86 relay management API via NIP-11. */
fun supportsNip86(relayInfo: RelayInfo?): Boolean =
    relayInfo?.supportedNips?.contains(86) == true
