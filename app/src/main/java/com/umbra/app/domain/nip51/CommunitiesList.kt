package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/** NIP-51 communities list (kind 10004): NIP-72 community definitions (kind 34550) the user belongs to. */
data class CommunitiesList(
    val ownerPubkey: String,
    val communityAddresses: Set<String>,
    val updatedAt: Long
)

/** Returns null if [event] is not kind 10004. */
fun extractCommunitiesList(event: Event): CommunitiesList? {
    if (event.kind != Event.KIND_COMMUNITIES_LIST) return null
    return CommunitiesList(
        ownerPubkey = event.pubkey.lowercase(),
        communityAddresses = parseTagValues(event, "a"),
        updatedAt = event.createdAt
    )
}
