package com.umbra.app.domain.nipc7

import com.umbra.app.domain.nip01.Event

/**
 * NIP-C7 chat message (kind 9): a flat, ordered stream — a reply quotes its parent via a
 * NIP-18 `q` tag rather than a threading `e` tag.
 */
data class ChatMessage(val quotedEventId: String?, val quotedRelayUrl: String?)

/** Returns null if [event] is not kind 9. */
fun extractChatMessage(event: Event): ChatMessage? {
    if (event.kind != Event.KIND_CHAT_MESSAGE) return null
    val qTag = event.tags.firstOrNull { it.getOrNull(0) == "q" }
    return ChatMessage(
        quotedEventId = qTag?.getOrNull(1),
        quotedRelayUrl = qTag?.getOrNull(2)?.takeIf { it.isNotBlank() }
    )
}
