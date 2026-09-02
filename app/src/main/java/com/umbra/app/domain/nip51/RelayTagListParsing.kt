package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared single-tag-name scan for NIP-51 list kinds that store a flat `Set<String>` of values
 * under one tag name (bookmarks' "e"/"a" tags, communities'/interests' "a"/"t" tags, the
 * relay-list kinds' "relay" tag, etc.) — delegates to [Event.getTagValues] rather than each
 * caller re-walking `event.tags` itself.
 */
internal fun parseTagValues(event: Event, tagName: String, lowercase: Boolean = false): Set<String> {
    val values = event.getTagValues(tagName)
    return if (lowercase) values.mapTo(mutableSetOf()) { it.lowercase() } else values.toSet()
}

/**
 * Shared "relay" tag scan for the NIP-51 relay-list kinds that all share the exact same tag
 * shape (kind:10006 blocked, kind:10007 search, kind:10086 index) — `["relay", <url>]`. Each
 * kind keeps its own type/doc comment/extractor for protocol clarity; this only removes the
 * copy-pasted tag scan those extractors would otherwise each repeat verbatim.
 */
internal fun parseRelayTagUrls(event: Event): Set<String> = parseTagValues(event, "relay")

/**
 * Encodes [urls] as the plain-JSON tag-array shape NIP-51's "private" content mechanism expects
 * — `[["relay","wss://..."],...]`, the same structure as the event's own public `tags` array,
 * just JSON-stringified instead of living in `tags`. This is the **plaintext** handed to
 * nip44_encrypt before publish (see RelayConfigViewModel); the encryption itself always goes
 * through Amber, never locally — see Nip44Gateway.
 */
fun encodeRelayTagUrls(urls: Set<String>): String {
    val array = buildJsonArray {
        urls.forEach { url ->
            add(buildJsonArray { add(JsonPrimitive("relay")); add(JsonPrimitive(url)) })
        }
    }
    return JsonUtils.CompactJson.encodeToString(JsonArray.serializer(), array)
}

/**
 * Inverse of [encodeRelayTagUrls] — parses the plaintext returned by nip44_decrypt back into a
 * relay URL set. Returns an empty set (not null/throw) for blank or malformed input, matching
 * [parseRelayTagUrls]'s own permissive style — a decrypt result that doesn't parse cleanly
 * shouldn't crash the caller, just contribute nothing.
 */
fun decodeRelayTagUrls(json: String): Set<String> {
    if (json.isBlank()) return emptySet()
    return runCatching {
        JsonUtils.NostrJson.parseToJsonElement(json).jsonArray
            .mapNotNull { tag ->
                val parts = tag.jsonArray
                val name = parts.getOrNull(0)?.jsonPrimitive?.content
                val value = parts.getOrNull(1)?.jsonPrimitive?.content
                value.takeIf { name == "relay" && !it.isNullOrBlank() }
            }
            .toSet()
    }.getOrDefault(emptySet())
}
