package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/** NIP-51 blocked relays list (kind 10006): relays clients should never connect to. */
data class BlockedRelaysList(
    val ownerPubkey: String,
    val relayUrls: Set<String>,
    val updatedAt: Long
)

/** Returns null if [event] is not kind 10006. */
fun extractBlockedRelaysList(event: Event): BlockedRelaysList? {
    if (event.kind != Event.KIND_BLOCKED_RELAYS) return null
    return BlockedRelaysList(
        ownerPubkey = event.pubkey.lowercase(),
        relayUrls = parseRelayTagUrls(event),
        updatedAt = event.createdAt
    )
}
