package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.AddressCoordinate
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.addressCoordinate

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

/**
 * One saved item: a note by id (`e`) or an addressable event such as an article by its address
 * (`a`), either public or one of the list's encrypted private items.
 */
data class Bookmark(val target: BookmarkTarget, val isPrivate: Boolean)

sealed interface BookmarkTarget {
    /** The `e`/`a` value this target is saved under. */
    val value: String
    val tagName: String

    data class Note(val id: String) : BookmarkTarget {
        override val value get() = id
        override val tagName get() = "e"
    }

    data class Address(val coordinate: AddressCoordinate) : BookmarkTarget {
        override val value get() = coordinate.toString()
        override val tagName get() = "a"
    }

    companion object {
        /** How [event] is bookmarked: addressable events by address, so a new revision stays saved. */
        fun of(event: Event): BookmarkTarget =
            event.addressCoordinate()?.let(::Address) ?: Note(event.id.lowercase())
    }
}

/**
 * Every bookmark on the list [event] with its decrypted [privateTags] (null when they couldn't
 * be read). An item saved both ways counts once, as private. Hashtags and URLs some clients also
 * bookmark are left on the list but not returned.
 */
fun bookmarksOf(event: Event?, privateTags: List<List<String>>?): List<Bookmark> {
    val private = targetsOf(privateTags.orEmpty())
    val public = targetsOf(event?.tags.orEmpty()).filterNot { it in private }
    return private.map { Bookmark(it, isPrivate = true) } + public.map { Bookmark(it, isPrivate = false) }
}

private fun targetsOf(tags: List<List<String>>): List<BookmarkTarget> =
    tags.mapNotNull { tag ->
        if (tag.size < 2) return@mapNotNull null
        when (tag[0]) {
            "e" -> tag[1].lowercase().takeIf { it.length == 64 }?.let(BookmarkTarget::Note)
            "a" -> AddressCoordinate.parse(tag[1])?.let(BookmarkTarget::Address)
            else -> null
        }
    }.distinct()
