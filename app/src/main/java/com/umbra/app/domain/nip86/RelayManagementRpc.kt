package com.umbra.app.domain.nip86

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * NIP-86 wire encoding/decoding, pure Kotlin so unit tests need no device.
 *
 * Request:  {"method": <name>, "params": [...]}   (Content-Type: application/nostr+json+rpc)
 * Response: {"result": <any>, "error": "<optional>"}
 */
object RelayManagementRpc {

    /** Lenient, unknown-keys-tolerant parser — relays pad responses freely. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Encodes a call's body once, exactly what gets POSTed. */
    fun encodeRequest(method: RelayManagementMethod, params: List<String>): String =
        buildJsonRpcBody(method.wireName, params).toString()

    private fun buildJsonRpcBody(methodName: String, params: List<String>): JsonObject =
        buildJsonObject {
            put("method", methodName)
            put("params", buildJsonArray { params.forEach { add(JsonPrimitive(it)) } })
        }

    /**
     * Decodes a relay's HTTP body into a typed result. Errors are never exceptions unless the
     * body isn't JSON at all (or the HTTP layer already failed — callers handle that).
     */
    fun decodeResponse(rawBody: String): RelayManagementResult {
        val obj = runCatching { json.parseToJsonElement(rawBody).jsonObject }
            .getOrElse { return RelayManagementResult.Transport(RelayManagementCause.UnparseableResponse) }

        return when {
            (obj["error"] as? JsonPrimitive)?.content?.isNotBlank() == true ->
                RelayManagementResult.RelayError((obj["error"] as JsonPrimitive).content)

            else -> RelayManagementResult.Ok(parseData(obj["result"] ?: JsonNull))
        }
    }

    private fun parseData(element: JsonElement): RelayManagementData = when {
        element is JsonArray -> parseListData(element)
        element is JsonNull -> RelayManagementData(isEmpty = true)
        element is JsonObject -> RelayManagementData(raw = element.toString())
        element is JsonPrimitive -> RelayManagementData(raw = element.content)
        else -> RelayManagementData(raw = element.toString())
    }

    private fun parseListData(array: JsonArray): RelayManagementData {
        // One array might be people-ish (objects with pubkey/id/ip) or kind-ish (plain ints) —
        // pick by reflecting what every element actually is, not by which method the caller used.
        if (array.isEmpty()) return RelayManagementData(isEmpty = true)
        val allPrimitives = array.all { it is JsonPrimitive }
        if (allPrimitives) {
            val ints = array.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            if (ints.size == array.size && array.all { (it as? JsonPrimitive)?.isString != true }) {
                return RelayManagementData(kindNumbers = ints)
            }
            return RelayManagementData(raw = array.toString())
        }
        val entries = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val identifier = (obj["pubkey"] ?: obj["id"] ?: obj["ip"])
                .takeIf { it is JsonPrimitive && it !== JsonNull }?.let { (it as JsonPrimitive).content }
                ?: return@mapNotNull null
            RelayManagementEntry(
                identifier = identifier,
                reason = (obj["reason"] as? JsonPrimitive)?.takeIf { it !== JsonNull }?.content
            )
        }
        if (entries.size == array.size) return RelayManagementData(entries = entries)
        // Shaped-but-unrecognized array (e.g. strings mixed with objects): keep the raw form.
        return RelayManagementData(raw = array.toString())
    }
}
