package com.umbra.app.domain.nip05

/**
 * Parsed and normalized NIP-05 identifier.
 */
data class Nip05Identifier(
    val name: String,
    val domain: String
) {
    val normalized: String = "$name@$domain"
}

/**
 * Parses a NIP-05 identifier into canonical parts.
 *
 * Rules:
 * - trim and lowercase
 * - must contain exactly one '@'
 * - blank name is normalized to '_'
 * - domain must be non-blank
 */
fun parseNip05Identifier(raw: String): Nip05Identifier? {
    val normalized = raw.trim().lowercase()
    val parts = normalized.split("@")
    if (parts.size != 2) return null

    val name = parts[0].trim().ifBlank { "_" }
    val domain = parts[1].trim()
    if (domain.isBlank()) return null

    return Nip05Identifier(name = name, domain = domain)
}
