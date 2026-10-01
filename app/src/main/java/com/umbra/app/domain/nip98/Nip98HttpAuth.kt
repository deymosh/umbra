package com.umbra.app.domain.nip98

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.util.toHex
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * NIP-98 HTTP auth (kind 27235): a single-use signed event proving to an HTTP service that a
 * Nostr identity requested this call. Nothing here signs or performs I/O — [buildUnsignedEvent]
 * emits exactly the JSON an external NIP-55 signer (Amber) must fill in with pubkey/sig, and
 * [authorizationHeader] encodes a *signed* result into the `Authorization: Nostr <base64>` header
 * (same shape as the Blossom BUD-01 `Authorization` header, keepers of that pattern:
 * `UploadBlossomBlobUseCase`).
 */
object Nip98HttpAuth {

    /** NIP-98 http auth event kind. */
    const val KIND_HTTP_AUTH = 27235

    /** Header scheme: `Authorization: Nostr <base64(utf8(signed event json))>`. */
    private const val HEADER_SCHEME = "Nostr"

    /**
     * Builds the unsigned kind-27235 event for one HTTP call:
     * - `u`       — the absolute URL that will be requested (required)
     * - `method`  — the HTTP method (required)
     * - `payload` — sha256 hex of the request body; for GET/HEAD (no body) this is the hash of
     *   empty bytes, kept present because Umbra's relay-management usage (NIP-86) requires it
     *   always. A non-null [body] MUST be the exact bytes the caller then sends.
     * Content is empty and there are no other tags.
     */
    fun buildUnsignedEvent(url: String, method: String, body: ByteArray?, nowEpochSeconds: Long): String {
        val payloadHex = sha256UriHex(body)
        val tags: JsonArray = buildJsonArray {
            addTag("u", url)
            addTag("method", method)
            addTag("payload", payloadHex)
        }
        return buildJsonObject {
            put("id", "")
            put("pubkey", "")
            put("created_at", nowEpochSeconds)
            put("kind", KIND_HTTP_AUTH)
            put("tags", tags)
            put("content", "")
            put("sig", "")
        }.toString()
    }

    /** `Authorization` header value for a [signed] (pubkey+sig filled) event JSON string. */
    fun authorizationHeader(signedEventJson: String): String {
        val encoded = Base64.getEncoder().encodeToString(signedEventJson.toByteArray(Charsets.UTF_8))
        return "$HEADER_SCHEME $encoded"
    }

    private fun JsonArrayBuilder.addTag(vararg values: String) {
        add(buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    private fun sha256UriHex(bytes: ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes ?: ByteArray(0))
        return digest.toHex()
    }
}
