package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/**
 * Index relay list (kind 10086): relays clients should query when browsing/indexing content —
 * see [Event.KIND_INDEX_RELAYS] for provenance (not yet in the ratified NIP-51 list). Same
 * "relay" tag shape, and the same public/private split, as
 * [SearchRelaysList] — see that type's doc comment for [encryptedContent].
 */
data class IndexRelaysList(
    val ownerPubkey: String,
    val relayUrls: Set<String>,
    val updatedAt: Long,
    val encryptedContent: String? = null
)

/** Returns null if [event] is not kind 10086. */
fun extractIndexRelaysList(event: Event): IndexRelaysList? {
    if (event.kind != Event.KIND_INDEX_RELAYS) return null
    return IndexRelaysList(
        ownerPubkey = event.pubkey.lowercase(),
        relayUrls = parseRelayTagUrls(event),
        updatedAt = event.createdAt,
        encryptedContent = event.content.takeIf { it.isNotBlank() }
    )
}
