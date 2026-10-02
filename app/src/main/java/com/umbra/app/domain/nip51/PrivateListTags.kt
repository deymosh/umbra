package com.umbra.app.domain.nip51

import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * NIP-51 private items: a list's `content` is the author's NIP-44 encryption, to themselves, of a
 * JSON array of tags shaped exactly like the public ones. These helpers read and write that
 * plaintext; encryption itself goes through the external signer.
 */
fun parsePrivateTags(plaintext: String): List<List<String>>? {
    val array = runCatching { JsonUtils.NostrJson.parseToJsonElement(plaintext) as? JsonArray }.getOrNull()
        ?: return null
    return array.mapNotNull { element ->
        (element as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            ?.takeIf { it.size >= 2 }
    }
}

fun encodePrivateTags(tags: List<List<String>>): String =
    buildJsonArray {
        tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } }) }
    }.toString()

/**
 * Older clients encrypted private items with NIP-04, which the signer bridge here can't decrypt
 * as NIP-44. Such content is kept untouched on edit rather than rewritten.
 */
fun isLegacyNip04Content(content: String): Boolean = content.contains("?iv=")

/**
 * Whether [content] is shaped like a NIP-44 payload (unpadded-or-padded base64, no spaces or
 * JSON), so plain text some clients leave there — a kind-3's old relay map, a note — is never sent
 * to the signer to decrypt.
 */
fun looksLikeNip44Payload(content: String): Boolean =
    content.length >= MIN_NIP44_PAYLOAD_LENGTH && content.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

// Version byte + 32-byte nonce + at least 32 padded bytes + 32-byte MAC, base64-encoded.
private const val MIN_NIP44_PAYLOAD_LENGTH = 132
