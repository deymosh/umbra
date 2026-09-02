package com.umbra.app.domain.nip01

/**
 * Identity of a replaceable/parameterized-replaceable event's logical slot (NIP-01/NIP-33):
 * relays and clients keep only the newest event per [pubkey]+[kind] (regular-replaceable,
 * kind 0/3/10000-19999), or per [pubkey]+[kind]+[dTag] (parameterized-replaceable, kind
 * 30000-39999). Two events sharing the same key are different revisions of the same logical
 * content — only the newest should ever be treated as current.
 */
data class ReplaceableEventKey(val pubkey: String, val kind: Int, val dTag: String = "")

/** This event's [ReplaceableEventKey], or `null` if [Event.kind] isn't a replaceable kind. */
fun Event.replaceableKey(): ReplaceableEventKey? = when (kind) {
    Event.KIND_METADATA, Event.KIND_CONTACT_LIST -> ReplaceableEventKey(pubkey, kind)
    in 10_000..19_999 -> ReplaceableEventKey(pubkey, kind)
    in 30_000..39_999 -> ReplaceableEventKey(pubkey, kind, getTagValue("d").orEmpty())
    else -> null
}

/**
 * NIP-01's replaceable-event race rule: highest `created_at` wins; on an exact tie, the
 * lexicographically lowest `id` wins (matches relay-side replaceable-event resolution).
 */
fun Event.winsReplaceableRace(other: Event): Boolean {
    if (createdAt != other.createdAt) return createdAt > other.createdAt
    return id < other.id
}
