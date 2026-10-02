package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.AddressCoordinate
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.addressCoordinate

/**
 * One NIP-51 set (kind 30000-30030): a named, addressable list such as a group of bookmarks or
 * people. Its items are tags like the matching standard list's, plus optional encrypted private
 * items in `content`.
 */
data class ListSet(
    val coordinate: AddressCoordinate,
    val title: String,
    val description: String?,
    val event: Event
) {
    val identifier: String get() = coordinate.identifier
}

/** Null when [event] isn't addressable. The title falls back to the older `name` tag, then the `d`. */
fun parseListSet(event: Event): ListSet? {
    val coordinate = event.addressCoordinate() ?: return null
    val title = event.getTagValue("title")?.takeIf { it.isNotBlank() }
        ?: event.getTagValue("name")?.takeIf { it.isNotBlank() }
        ?: coordinate.identifier
    return ListSet(coordinate, title.trim(), event.getTagValue("description")?.takeIf { it.isNotBlank() }, event)
}

/**
 * The newest revision of each set in [events], sorted by title. A set emptied of everything,
 * title included — the shape some clients publish instead of a deletion — is left out.
 */
fun latestListSets(events: List<Event>): List<ListSet> =
    events.groupBy { it.addressCoordinate() }
        .mapNotNull { (coordinate, revisions) -> coordinate?.let { revisions.maxByOrNull(Event::createdAt) } }
        .filterNot { event -> event.tags.all { it.firstOrNull() == "d" } && event.content.isBlank() }
        .mapNotNull(::parseListSet)
        .sortedBy { it.title.lowercase() }

/** Tags for a brand-new set: its address and title. */
fun newListSetTags(identifier: String, title: String): List<List<String>> =
    listOf(listOf("d", identifier), listOf("title", title.trim()))

/** [tags] with the set's title replaced (and the legacy `name` tag dropped). */
fun retitledListSetTags(tags: List<List<String>>, title: String): List<List<String>> {
    val kept = tags.filterNot { it.firstOrNull() == "title" || it.firstOrNull() == "name" }
    val dIndex = kept.indexOfFirst { it.firstOrNull() == "d" }
    return kept.toMutableList().apply { add(dIndex + 1, listOf("title", title.trim())) }
}
