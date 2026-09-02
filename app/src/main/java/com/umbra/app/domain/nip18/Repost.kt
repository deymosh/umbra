package com.umbra.app.domain.nip18

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal NIP-18 repost helpers, mirroring domain/nip25/ReactionSemantics.kt's plain
 * data-class-plus-free-function shape — a repost is also just "points at another event", the
 * same lightweight case reactions are.
 */
data class RepostTarget(
    val eventId: String?,
    val authorPubkey: String?,
    // Optional relay hint from the "e" tag's third element (["e", id, relayUrl], NIP-01/NIP-18
    // convention) — passed through to a fallback fetchEventById lookup the same way a quoted
    // nevent1's relay hints already are (see ViewportImagePrefetchPlanner's
    // resolveViewportQuotedEvents), since a hint-less lookup only reaches relays already connected.
    val relayHint: String? = null
)

fun extractRepostTarget(event: Event): RepostTarget {
    if (event.kind != Event.KIND_REPOST && event.kind != Event.KIND_GENERIC_REPOST) {
        return RepostTarget(eventId = null, authorPubkey = null)
    }
    val eTag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }
    val targetAuthorPubkey = event.tags.lastOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
    return RepostTarget(
        eventId = eTag?.getOrNull(1),
        authorPubkey = targetAuthorPubkey,
        relayHint = eTag?.getOrNull(2)?.takeIf { it.isNotBlank() }
    )
}

/**
 * NIP-18: a repost's `content` SHOULD be the full serialized original event JSON. Parsed here,
 * but deliberately NOT signature-verified — domain/ has no crypto dependency (EventCrypto lives
 * in data/, off-limits per this codebase's layering) — so the caller (EventRepositoryImpl) MUST
 * verify the result before trusting or caching it, exactly like every other incoming event.
 * Returns null on blank/malformed content or missing required fields; the caller then falls back
 * to resolving the target by id instead (cache/relay lookup), same as quote resolution already
 * does for a q-tag-only reference.
 */
fun parseRepostedEvent(event: Event): Event? {
    if (event.kind != Event.KIND_REPOST && event.kind != Event.KIND_GENERIC_REPOST) return null
    if (event.content.isBlank()) return null

    val obj = runCatching { JsonUtils.NostrJson.parseToJsonElement(event.content) }
        .getOrNull() as? JsonObject ?: return null

    val id = (obj["id"] as? JsonPrimitive)?.content
    val pubkey = (obj["pubkey"] as? JsonPrimitive)?.content
    val createdAt = (obj["created_at"] as? JsonPrimitive)?.content?.toLongOrNull()
    val kind = (obj["kind"] as? JsonPrimitive)?.content?.toIntOrNull()
    val sig = (obj["sig"] as? JsonPrimitive)?.content
    if (id.isNullOrBlank() || pubkey.isNullOrBlank() || createdAt == null || kind == null || sig.isNullOrBlank()) {
        return null
    }

    val tags = (obj["tags"] as? JsonArray)?.map { tag ->
        (tag as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    } ?: emptyList()
    val content = (obj["content"] as? JsonPrimitive)?.content ?: ""

    return Event(id = id, pubkey = pubkey, createdAt = createdAt, kind = kind, tags = tags, content = content, sig = sig)
}
