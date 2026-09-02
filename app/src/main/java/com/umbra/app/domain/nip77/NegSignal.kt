package com.umbra.app.domain.nip77

/**
 * A NIP-77 message observed on the wire for an open NEG-* subscription — see NostrClient.negMessageFlow.
 */
sealed interface NegSignal {
    val relayUrl: String
    val subscriptionId: String

    /** `["NEG-MSG", subId, message (hex)]` — carries the next round of the reconciliation exchange. */
    data class Msg(
        override val relayUrl: String,
        override val subscriptionId: String,
        val messageHex: String
    ) : NegSignal

    /** `["NEG-ERR", subId, reason]` — the relay refused or aborted the sync (e.g. `blocked: ...`, `closed: ...`). */
    data class Err(
        override val relayUrl: String,
        override val subscriptionId: String,
        val reason: String
    ) : NegSignal
}
