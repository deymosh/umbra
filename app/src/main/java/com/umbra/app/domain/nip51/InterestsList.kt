package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/** NIP-51 interests list (kind 10015): hashtags and interest-set (kind 30015) pointers. */
data class InterestsList(
    val ownerPubkey: String,
    val hashtags: Set<String>,
    val interestSetAddresses: Set<String>,
    val updatedAt: Long
)

/** Returns null if [event] is not kind 10015. */
fun extractInterestsList(event: Event): InterestsList? {
    if (event.kind != Event.KIND_INTERESTS_LIST) return null
    return InterestsList(
        ownerPubkey = event.pubkey.lowercase(),
        hashtags = parseTagValues(event, "t", lowercase = true),
        interestSetAddresses = parseTagValues(event, "a"),
        updatedAt = event.createdAt
    )
}
