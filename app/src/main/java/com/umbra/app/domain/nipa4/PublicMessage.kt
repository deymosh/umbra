package com.umbra.app.domain.nipa4

import com.umbra.app.domain.nip01.Event

/**
 * NIP-A4 public message (kind 24): a plaintext reply-to-notification message with no thread —
 * `p` tags name the receivers, an optional NIP-18 `q` tag cites a quoted event/address.
 */
data class PublicMessage(val receiverPubkeys: List<String>, val quotedRef: String?)

/** Returns null if [event] is not kind 24. */
fun extractPublicMessage(event: Event): PublicMessage? {
    if (event.kind != Event.KIND_PUBLIC_MESSAGE) return null
    val receivers = event.tags.filter { it.getOrNull(0) == "p" }.mapNotNull { it.getOrNull(1) }
    val quotedRef = event.tags.firstOrNull { it.getOrNull(0) == "q" }?.getOrNull(1)
    return PublicMessage(receiverPubkeys = receivers, quotedRef = quotedRef)
}
