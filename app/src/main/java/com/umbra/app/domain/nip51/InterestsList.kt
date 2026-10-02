package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.AddressCoordinate
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.addressCoordinate

/** NIP-51 interests list (kind 10015): hashtags and interest-set (kind 30015) pointers. */
data class InterestsList(
    val ownerPubkey: String,
    val hashtags: Set<String>,
    val interestSetAddresses: Set<String>,
    val updatedAt: Long
)

/** Returns null if [event] is not kind 10015. */
fun extractInterestsList(event: Event): InterestsList? {
    if (event.kind != Event.KIND_INTERESTS_LIST) return null
    return InterestsList(
        ownerPubkey = event.pubkey.lowercase(),
        hashtags = parseTagValues(event, "t", lowercase = true),
        interestSetAddresses = parseTagValues(event, "a"),
        updatedAt = event.createdAt
    )
}

/** [raw] as a `t` value: no leading `#`, lowercase, or null when nothing is left. */
fun normalizeHashtag(raw: String): String? =
    raw.trim().removePrefix("#").trim().lowercase().takeIf { it.isNotEmpty() }

/**
 * Hashtags named directly on the interests list [list], public and among its decrypted
 * [privateTags] (null when they couldn't be read).
 */
fun listedHashtagsOf(list: Event?, privateTags: List<List<String>>?): Set<String> =
    hashtagsIn(list?.tags.orEmpty()) + hashtagsIn(privateTags.orEmpty())

/**
 * Every hashtag the user follows: those on [list] plus those in the interest sets it points to.
 * Only the list owner's own sets among [ownInterestSets] are followed through, since their
 * archive already holds them; a pointer to someone else's set is left alone.
 */
fun followedHashtagsOf(list: Event?, privateTags: List<List<String>>?, ownInterestSets: List<Event>): Set<String> {
    val owner = list?.pubkey?.lowercase() ?: return emptySet()
    val pointed = (list.tags + privateTags.orEmpty())
        .filter { it.size >= 2 && it[0] == "a" }
        .mapNotNullTo(HashSet()) { tag ->
            AddressCoordinate.parse(tag[1])?.takeIf { it.kind == Event.KIND_INTEREST_SET && it.pubkey == owner }
        }
    val fromSets = ownInterestSets
        .groupBy { it.addressCoordinate() }
        .filterKeys { it in pointed }
        .values
        .flatMapTo(HashSet()) { revisions -> hashtagsIn(revisions.maxBy { it.createdAt }.tags) }
    return listedHashtagsOf(list, privateTags) + fromSets
}

private fun hashtagsIn(tags: List<List<String>>): Set<String> =
    tags.mapNotNullTo(LinkedHashSet()) { tag -> if (tag.size >= 2 && tag[0] == "t") normalizeHashtag(tag[1]) else null }
