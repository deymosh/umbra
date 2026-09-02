package com.umbra.app.domain.nip21

import com.umbra.app.domain.nip19.Bech32Encoder

/**
 * A `nostr:` URI (NIP-21) resolved to a typed NIP-19 entity. [Profile]/[Note]'s `relays` is
 * always empty for the bare npub1/note1 forms (they carry no TLV data) and populated from the
 * nprofile1/nevent1 forms' relay hints (TLV type 1) when present — the whole reason those richer
 * forms exist over the bare ones is to tell a client where to actually find the entity.
 */
sealed class NostrUriEntity {
    data class Profile(val pubkey: String, val relays: List<String> = emptyList()) : NostrUriEntity()
    data class Note(
        val eventId: String,
        val relays: List<String> = emptyList(),
        val authorPubkey: String? = null
    ) : NostrUriEntity()
    data class Address(
        val kind: Int,
        val authorPubkey: String,
        val identifier: String,
        val relays: List<String>
    ) : NostrUriEntity()
}

/**
 * Strips a `nostr:` or `nostr://` prefix (NIP-21) and common trailing punctuation from [raw],
 * leaving the bare bech32 entity — or [raw] (trimmed) unchanged if it had no such prefix, so bare
 * entities (already just "npub1...") work too. Also strips a leading `@` — not part of any NIP,
 * but some clients write mentions as `@npub1...`/`@nprofile1...` instead of `nostr:npub1...`;
 * treating it as an equivalent prefix lets every caller of this function (rendering, mention
 * prefetch, event-reference resolution) handle that form for free.
 */
fun stripNostrUriPrefix(raw: String): String {
    var value = raw.trim()
    value = value.removePrefix("nostr://")
    value = value.removePrefix("nostr:")
    value = value.removePrefix("@")
    return value.trimEnd('.', ',', ';', ':', ')', ']', '}')
}

/**
 * Resolves a bech32-encoded NIP-19 entity (optionally `nostr:`/`nostr://`-prefixed per NIP-21)
 * to a typed [NostrUriEntity]. Single source of truth shared by content rendering
 * (`NostrTextRenderer`/`TextParsingUtils`) and any future `nostr:` deep-link intent handler —
 * previously this resolution logic was only reachable from inside `ui/components`.
 *
 * Does not handle bare 64-hex strings — those are ambiguous between a pubkey and an event id
 * without caller context, so that fallback stays local to callers that know which one they mean.
 */
fun resolveNostrUri(raw: String): NostrUriEntity? {
    val normalized = stripNostrUriPrefix(raw)
    return when {
        normalized.startsWith("npub1", ignoreCase = true) ->
            Bech32Encoder.decodeNpub(normalized)?.let { NostrUriEntity.Profile(it) }
        normalized.startsWith("nprofile1", ignoreCase = true) ->
            Bech32Encoder.decodeNprofile(normalized)?.let { NostrUriEntity.Profile(it.pubkey, it.relays) }
        normalized.startsWith("note1", ignoreCase = true) ->
            Bech32Encoder.decodeNote(normalized)?.let { NostrUriEntity.Note(it) }
        normalized.startsWith("nevent1", ignoreCase = true) ->
            Bech32Encoder.decodeNevent(normalized)?.let { NostrUriEntity.Note(it.eventId, it.relays, it.authorPubkey) }
        normalized.startsWith("naddr1", ignoreCase = true) ->
            Bech32Encoder.decodeNaddr(normalized)?.let {
                NostrUriEntity.Address(
                    kind = it.kind,
                    authorPubkey = it.authorPubkey,
                    identifier = it.identifier,
                    relays = it.relays
                )
            }
        else -> null
    }
}
