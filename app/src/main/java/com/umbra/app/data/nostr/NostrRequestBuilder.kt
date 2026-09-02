package com.umbra.app.data.nostr

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds outgoing Nostr relay protocol message JSON strings (NIP-01 REQ/CLOSE/EVENT, NIP-42 AUTH,
 * NIP-45 COUNT, NIP-77 NEG-OPEN/NEG-MSG/NEG-CLOSE).
 *
 * Responsibilities:
 * - Knows relay wire-protocol framing (message arrays, filter/event object shapes).
 * - Does NOT open sockets, send frames, or otherwise perform I/O — that stays [UmbraNostrClient]'s
 *   job, same division of labor [com.umbra.app.domain.nip01.NostrEventBuilder] uses for event
 *   content shaping vs. signing/publishing.
 *
 * Usage pattern:
 *   val payload = NostrRequestBuilder.req(subscriptionId, filters)
 *   webSocket.send(payload)
 */
object NostrRequestBuilder {

    fun req(subscriptionId: String, filters: List<EventFilter>): String = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subscriptionId))
        filters.forEach { add(filterToJson(it)) }
    }.toString()

    fun count(subscriptionId: String, filters: List<EventFilter>): String = buildJsonArray {
        add(JsonPrimitive("COUNT"))
        add(JsonPrimitive(subscriptionId))
        filters.forEach { add(filterToJson(it)) }
    }.toString()

    fun event(event: Event): String = buildJsonArray {
        add(JsonPrimitive("EVENT"))
        add(eventToJson(event))
    }.toString()

    fun auth(event: Event): String = buildJsonArray {
        add(JsonPrimitive("AUTH"))
        add(eventToJson(event))
    }.toString()

    fun close(subscriptionId: String): String = buildJsonArray {
        add(JsonPrimitive("CLOSE"))
        add(JsonPrimitive(subscriptionId))
    }.toString()

    /** NIP-77: `["NEG-OPEN", subId, filter, initialMessage (hex)]`. */
    fun negOpen(subscriptionId: String, filter: EventFilter, initialMessageHex: String): String = buildJsonArray {
        add(JsonPrimitive("NEG-OPEN"))
        add(JsonPrimitive(subscriptionId))
        add(filterToJson(filter))
        add(JsonPrimitive(initialMessageHex))
    }.toString()

    /** NIP-77: `["NEG-MSG", subId, message (hex)]` — same shape in both directions. */
    fun negMsg(subscriptionId: String, messageHex: String): String = buildJsonArray {
        add(JsonPrimitive("NEG-MSG"))
        add(JsonPrimitive(subscriptionId))
        add(JsonPrimitive(messageHex))
    }.toString()

    /** NIP-77: `["NEG-CLOSE", subId]`. */
    fun negClose(subscriptionId: String): String = buildJsonArray {
        add(JsonPrimitive("NEG-CLOSE"))
        add(JsonPrimitive(subscriptionId))
    }.toString()

    private fun filterToJson(filter: EventFilter): JsonObject = buildJsonObject {
        if (filter.ids.isNotEmpty()) {
            put("ids", JsonArray(filter.ids.map { JsonPrimitive(it) }))
        }
        if (filter.authors.isNotEmpty()) {
            put("authors", JsonArray(filter.authors.map { JsonPrimitive(it) }))
        }
        if (filter.kinds.isNotEmpty()) {
            put("kinds", JsonArray(filter.kinds.map { JsonPrimitive(it) }))
        }
        if (filter.since != null) {
            put("since", JsonPrimitive(filter.since))
        }
        if (filter.until != null) {
            put("until", JsonPrimitive(filter.until))
        }
        if (filter.limit > 0) {
            put("limit", JsonPrimitive(filter.limit))
        }
        if (!filter.search.isNullOrBlank()) {
            put("search", JsonPrimitive(filter.search))
        }
        filter.tagFilters.forEach { (tag, values) ->
            put("#${tag}", JsonArray(values.map { JsonPrimitive(it) }))
        }
    }

    private fun eventToJson(event: Event): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(event.id))
        put("pubkey", JsonPrimitive(event.pubkey))
        put("created_at", JsonPrimitive(event.createdAt))
        put("kind", JsonPrimitive(event.kind))
        put("tags", JsonArray(event.tags.map { tagList ->
            JsonArray(tagList.map { JsonPrimitive(it) })
        }))
        put("content", JsonPrimitive(event.content))
        put("sig", JsonPrimitive(event.sig))
    }
}
