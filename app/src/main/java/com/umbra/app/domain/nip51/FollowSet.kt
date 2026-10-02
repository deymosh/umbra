package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event

/**
 * People in a NIP-51 follow set (kind 30000): its public `p` tags and those among its decrypted
 * [privateTags] (null when they couldn't be read), lowercase, private ones first.
 */
fun followSetMembersOf(set: Event?, privateTags: List<List<String>>?): List<FollowSetMember> {
    val private = pubkeysIn(privateTags.orEmpty())
    val public = pubkeysIn(set?.tags.orEmpty()).filterNot { it in private }
    return private.map { FollowSetMember(it, isPrivate = true) } + public.map { FollowSetMember(it, isPrivate = false) }
}

data class FollowSetMember(val pubkey: String, val isPrivate: Boolean)

private fun pubkeysIn(tags: List<List<String>>): List<String> =
    tags.mapNotNull { tag ->
        if (tag.size < 2 || tag[0] != "p") return@mapNotNull null
        tag[1].lowercase().takeIf { key -> key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' } }
    }.distinct()
