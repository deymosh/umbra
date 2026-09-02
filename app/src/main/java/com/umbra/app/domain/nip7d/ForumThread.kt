package com.umbra.app.domain.nip7d

import com.umbra.app.domain.nip01.Event

/**
 * NIP-7D forum thread (kind 11) metadata. Replies to a thread are plain NIP-22 kind-1111
 * comments (see `domain/nip22`) — always scoped to the thread itself as root (spec: "Replies
 * should always be to the root kind 11 to avoid arbitrarily nested reply hierarchies"), so
 * there's no separate NIP-7D reply builder; use `NostrEventBuilder.comment(root = thread, ...)`.
 */
data class ForumThread(val title: String?)

/** Returns null for any non-kind-11 event. */
fun extractForumThread(event: Event): ForumThread? {
    if (event.kind != Event.KIND_THREAD) return null
    val title = event.getTagValue("title")?.takeIf { it.isNotBlank() }
    return ForumThread(title = title)
}
