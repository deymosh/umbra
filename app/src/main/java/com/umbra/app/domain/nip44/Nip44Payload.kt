package com.umbra.app.domain.nip44

/**
 * Minimal versioned payload envelope for NIP-44 content transport.
 *
 * This does not implement cryptography; it only models and validates
 * version-prefix formatting to keep domain boundaries explicit.
 */
data class Nip44Payload(
    val version: Int,
    val payload: String
)

object Nip44PayloadEnvelope {
    fun decode(content: String): Nip44Payload? {
        val trimmed = content.trim()
        val delimiter = trimmed.indexOf(':')
        if (delimiter <= 0 || delimiter >= trimmed.lastIndex) return null

        val version = trimmed.substring(0, delimiter).toIntOrNull() ?: return null
        val payload = trimmed.substring(delimiter + 1).trim()
        if (version <= 0 || payload.isBlank()) return null

        return Nip44Payload(version = version, payload = payload)
    }

    fun encode(value: Nip44Payload): String {
        require(value.version > 0) { "NIP-44 version must be positive" }
        require(value.payload.isNotBlank()) { "NIP-44 payload cannot be blank" }
        return "${value.version}:${value.payload}"
    }
}
