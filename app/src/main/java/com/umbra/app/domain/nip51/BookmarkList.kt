package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/** NIP-51 bookmarks list (kind 10003): an uncategorized, "global" save list. */
data class BookmarkList(
    val ownerPubkey: String,
    val noteIds: Set<String>,
    val articleAddresses: Set<String>,
    val updatedAt: Long
)

/** Returns null if [event] is not kind 10003. */
fun extractBookmarkList(event: Event): BookmarkList? {
    if (event.kind != Event.KIND_BOOKMARK_LIST) return null
    return BookmarkList(
        ownerPubkey = event.pubkey.lowercase(),
        noteIds = parseTagValues(event, "e", lowercase = true),
        articleAddresses = parseTagValues(event, "a"),
        updatedAt = event.createdAt
    )
}
