package com.umbra.app.domain.nip45

/**
 * Parsed NIP-45 COUNT response for a relay/subscription pair.
 */
data class RelayCountResult(
    val relayUrl: String,
    val subscriptionId: String,
    val count: Long,
    val approximate: Boolean = false
)
