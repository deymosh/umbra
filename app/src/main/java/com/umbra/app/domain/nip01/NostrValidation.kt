package com.umbra.app.domain.nip01

/**
 * Nostr data validation utilities.
 * Per NIP-01: ids, authors, and tag values must be 64-character lowercase hex.
 */
object NostrValidation {
    private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")

    /**
     * Validate that a value is a valid 64-char lowercase hex string.
     * Used for: event IDs, public keys, tag values (#e, #p, etc.)
     * @return The value if valid, null if invalid
     */
    fun validate64HexOrNull(value: String?): String? {
        if (value == null) return null
        val normalized = value.lowercase()
        return if (HEX_64_REGEX.matches(normalized)) normalized else null
    }

    /**
     * Batch validate a collection of 64-hex values.
     * Filters: only returns valid entries (normalized to lowercase).
     */
    fun validate64HexSet(values: Collection<String>): Set<String> {
        return values.mapNotNull { validate64HexOrNull(it) }.toSet()
    }

    /**
     * Check if a value is valid 64-char lowercase hex.
     */
    fun is64HexValid(value: String?): Boolean {
        return validate64HexOrNull(value) != null
    }
}


