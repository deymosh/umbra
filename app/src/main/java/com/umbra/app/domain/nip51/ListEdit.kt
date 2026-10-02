package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/**
 * A change to one tracked tag of a replaceable list (contacts, mutes, pins, bookmarks): values to
 * add and values to remove, compared case-insensitively.
 */
data class ListEdit(
    val tagName: String,
    val add: Set<String> = emptySet(),
    val remove: Set<String> = emptySet()
)

/**
 * Applies [edit] to the tags of [base] — the user's latest published version of this list —
 * and returns the full new tag list. Everything the edit doesn't touch is kept verbatim: other
 * tag names (muted hashtags/words/threads, bookmarked articles and hashtags), extra fields on
 * kept tags (relay hints, petnames), and their order. Newly added values are appended.
 *
 * Replaceable lists are shared with every other Nostr client the user runs; rebuilding one from
 * only the values Umbra tracks would silently delete everything else on it.
 */
fun applyListEdit(base: Event?, edit: ListEdit, fallbackValues: Set<String> = emptySet()): List<List<String>> =
    applyListEdit(base?.tags ?: fallbackValues.map { listOf(edit.tagName, it) }, edit)

/** [applyListEdit] over a plain tag list, e.g. a list's decrypted private items. */
fun applyListEdit(baseTags: List<List<String>>, edit: ListEdit): List<List<String>> {
    val removeKeys = edit.remove.mapTo(HashSet()) { it.lowercase() }
    val kept = baseTags.filterNot { tag ->
        tag.size >= 2 && tag[0] == edit.tagName && tag[1].lowercase() in removeKeys
    }
    val present = kept.asSequence()
        .filter { it.size >= 2 && it[0] == edit.tagName }
        .mapTo(HashSet()) { it[1].lowercase() }
    val added = edit.add
        .filter { it.lowercase() !in present && it.lowercase() !in removeKeys }
        .distinctBy { it.lowercase() }
        .map { listOf(edit.tagName, it) }
    return kept + added
}
