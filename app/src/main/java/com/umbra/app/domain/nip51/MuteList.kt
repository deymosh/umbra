package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/**
 * NIP-51 mute list (kind 10000): people (`p`), hashtags (`t`), words (`word`) and threads (`e`)
 * the owner doesn't want to see, each public or one of the list's encrypted private items.
 * [mutedPubkeys] is all most callers need; [items] keeps the kind and privacy of each entry.
 */
data class MuteList(
    val ownerPubkey: String,
    val mutedPubkeys: Set<String>,
    val updatedAt: Long,
    val items: List<MuteItem> = mutedPubkeys.map { MuteItem(MuteItem.Kind.PERSON, it, isPrivate = false) },
    /** The list has private items the signer hasn't opened yet, so [items] may be missing some. */
    val privateLocked: Boolean = false
) {
    val mutedHashtags: Set<String> = valuesOf(MuteItem.Kind.HASHTAG)
    val mutedWords: Set<String> = valuesOf(MuteItem.Kind.WORD)
    val mutedThreads: Set<String> = valuesOf(MuteItem.Kind.THREAD)

    private fun valuesOf(kind: MuteItem.Kind): Set<String> = items.filter { it.kind == kind }.mapTo(HashSet()) { it.value }

    /**
     * Whether [event] is muted: by its author, a hashtag on it, a word in its text (whole words or
     * phrases, ignoring case) or the thread it belongs to.
     */
    fun hides(event: Event): Boolean {
        if (event.pubkey.lowercase() in mutedPubkeys) return true
        if (mutedHashtags.isNotEmpty() && event.getHashtags().any { it in mutedHashtags }) return true
        if (mutedThreads.isNotEmpty() && (event.id.lowercase() in mutedThreads || event.threadIds().any { it in mutedThreads })) return true
        return mutedWords.any { word -> containsWord(event.content, word) }
    }
}

/** One mute list entry. [value] is a lowercase pubkey, hashtag, word or event id. */
data class MuteItem(val kind: Kind, val value: String, val isPrivate: Boolean) {
    enum class Kind(val tagName: String) { PERSON("p"), HASHTAG("t"), WORD("word"), THREAD("e") }
}

/**
 * Every entry on the mute list [event] with its decrypted [privateTags] (null when they couldn't
 * be read). An entry muted both ways counts once, as private.
 */
fun muteListOf(event: Event, privateTags: List<List<String>>?): MuteList {
    val private = itemsIn(privateTags.orEmpty(), isPrivate = true)
    val privateKeys = private.mapTo(HashSet()) { it.kind to it.value }
    val public = itemsIn(event.tags, isPrivate = false).filterNot { (it.kind to it.value) in privateKeys }
    val items = private + public
    return MuteList(
        ownerPubkey = event.pubkey.lowercase(),
        mutedPubkeys = items.filter { it.kind == MuteItem.Kind.PERSON }.mapTo(HashSet()) { it.value },
        updatedAt = event.createdAt,
        items = items,
        privateLocked = privateTags == null && event.content.isNotBlank()
    )
}

private fun itemsIn(tags: List<List<String>>, isPrivate: Boolean): List<MuteItem> =
    tags.mapNotNull { tag ->
        if (tag.size < 2) return@mapNotNull null
        val kind = MuteItem.Kind.entries.firstOrNull { it.tagName == tag[0] } ?: return@mapNotNull null
        val value = when (kind) {
            MuteItem.Kind.PERSON, MuteItem.Kind.THREAD -> tag[1].lowercase().takeIf { it.length == 64 && it.isHex() }
            MuteItem.Kind.HASHTAG -> normalizeHashtag(tag[1])
            MuteItem.Kind.WORD -> tag[1].trim().lowercase().takeIf { it.isNotEmpty() }
        } ?: return@mapNotNull null
        MuteItem(kind, value, isPrivate)
    }.distinctBy { it.kind to it.value }

private fun String.isHex(): Boolean = all { it in '0'..'9' || it in 'a'..'f' }

/** Ids of the thread [this] sits in: its `e` tags (root and parent). */
private fun Event.threadIds(): List<String> =
    tags.filter { it.size >= 2 && it[0] == "e" }.map { it[1].lowercase() }

/** [word] in [text] as a whole word or phrase, ignoring case, so "cat" doesn't hide "category". */
private fun containsWord(text: String, word: String): Boolean {
    var from = 0
    while (true) {
        val at = text.indexOf(word, from, ignoreCase = true)
        if (at < 0) return false
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + word.length)
        if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
        from = at + 1
    }
}
