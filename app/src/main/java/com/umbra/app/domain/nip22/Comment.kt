package com.umbra.app.domain.nip22

import com.umbra.app.domain.nip01.Event

/**
 * NIP-22 comment (kind 1111) event pointer extraction.
 *
 * A comment has two scopes — root (uppercase tags `E`/`A`/`I`/`K`/`P`) and parent (lowercase
 * `e`/`a`/`i`/`k`/`p`) — each pointing to exactly one of an event id, an addressable-event
 * coordinate, or an external identifier (NIP-73: URLs, hashtags, podcast episodes, etc.).
 */
sealed class CommentPointer {
    data class EventPointer(
        val eventId: String,
        val relayHint: String? = null,
        val authorPubkey: String? = null
    ) : CommentPointer()

    data class AddressPointer(
        val address: String,
        val relayHint: String? = null
    ) : CommentPointer()

    data class ExternalPointer(
        val identifier: String,
        val hint: String? = null
    ) : CommentPointer()
}

/** One comment scope: what it points to, the pointed-at item's kind, and (if known) its author. */
data class CommentScope(
    val pointer: CommentPointer,
    // Numeric event kind as a string for E/A pointers, or a NIP-73 type string (e.g. "web",
    // "podcast:item:guid") for I pointers — the K/k tag value is untyped per spec either way.
    val kind: String,
    val authorPubkey: String? = null,
    val authorRelayHint: String? = null
)

/** [root] is the thread's top-level target; [parent] is what this specific comment replies to
 *  (equal to [root] for a top-level comment, or another comment/reply deeper in the thread). */
data class CommentTarget(
    val root: CommentScope?,
    val parent: CommentScope?
)

/** Returns null for any non-kind-1111 event. */
fun extractCommentTarget(event: Event): CommentTarget? {
    if (event.kind != Event.KIND_COMMENT) return null
    return CommentTarget(
        root = extractScope(event.tags, uppercase = true),
        parent = extractScope(event.tags, uppercase = false)
    )
}

private fun extractScope(tags: List<List<String>>, uppercase: Boolean): CommentScope? {
    val eTag = if (uppercase) "E" else "e"
    val aTag = if (uppercase) "A" else "a"
    val iTag = if (uppercase) "I" else "i"
    val kTag = if (uppercase) "K" else "k"
    val pTag = if (uppercase) "P" else "p"

    val pointer = tags.firstOrNull { it.getOrNull(0) == eTag }?.let { tag ->
        val eventId = tag.getOrNull(1)
        if (eventId.isNullOrBlank()) null else CommentPointer.EventPointer(
            eventId = eventId,
            relayHint = tag.getOrNull(2)?.takeIf { it.isNotBlank() },
            authorPubkey = tag.getOrNull(3)?.takeIf { it.isNotBlank() }
        )
    } ?: tags.firstOrNull { it.getOrNull(0) == aTag }?.let { tag ->
        val address = tag.getOrNull(1)
        if (address.isNullOrBlank()) null else CommentPointer.AddressPointer(
            address = address,
            relayHint = tag.getOrNull(2)?.takeIf { it.isNotBlank() }
        )
    } ?: tags.firstOrNull { it.getOrNull(0) == iTag }?.let { tag ->
        val identifier = tag.getOrNull(1)
        if (identifier.isNullOrBlank()) null else CommentPointer.ExternalPointer(
            identifier = identifier,
            hint = tag.getOrNull(2)?.takeIf { it.isNotBlank() }
        )
    } ?: return null

    val kind = tags.firstOrNull { it.getOrNull(0) == kTag }?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    val authorTag = tags.firstOrNull { it.getOrNull(0) == pTag }

    return CommentScope(
        pointer = pointer,
        kind = kind,
        authorPubkey = authorTag?.getOrNull(1)?.takeIf { it.isNotBlank() },
        authorRelayHint = authorTag?.getOrNull(2)?.takeIf { it.isNotBlank() }
    )
}
