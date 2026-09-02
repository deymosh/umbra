package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/**
 * NIP-51 search relays list (kind 10007): relays clients should use when performing search
 * queries. [relayUrls] is only what the event declared as *public* tags — per NIP-51, a list can
 * also carry NIP-44 self-encrypted "private" items in [encryptedContent] (the private-by-default
 * convention here puts the whole list there — see RelayConfigViewModel's decrypt orchestration).
 * Only the list owner can ever decrypt that content, so [encryptedContent] is opaque here; a
 * consumer that isn't the owner has no use for it beyond knowing it exists.
 */
data class SearchRelaysList(
    val ownerPubkey: String,
    val relayUrls: Set<String>,
    val updatedAt: Long,
    val encryptedContent: String? = null
)

/** Returns null if [event] is not kind 10007. */
fun extractSearchRelaysList(event: Event): SearchRelaysList? {
    if (event.kind != Event.KIND_SEARCH_RELAYS) return null
    return SearchRelaysList(
        ownerPubkey = event.pubkey.lowercase(),
        relayUrls = parseRelayTagUrls(event),
        updatedAt = event.createdAt,
        encryptedContent = event.content.takeIf { it.isNotBlank() }
    )
}
