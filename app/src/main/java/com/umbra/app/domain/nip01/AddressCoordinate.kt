package com.umbra.app.domain.nip01

/**
 * The `<kind>:<pubkey>:<d>` address of an addressable event (kind 30000-39999), as NIP-01 `a`
 * tags carry it. Its newest revision is what the address points to.
 */
data class AddressCoordinate(val kind: Int, val pubkey: String, val identifier: String) {
    override fun toString(): String = "$kind:$pubkey:$identifier"

    companion object {
        /** Null unless [value] names an addressable kind and a 64-hex pubkey. */
        fun parse(value: String): AddressCoordinate? {
            val parts = value.split(":", limit = 3)
            if (parts.size != 3) return null
            val kind = parts[0].toIntOrNull()?.takeIf { it in ADDRESSABLE_KINDS } ?: return null
            val pubkey = parts[1].lowercase()
            if (pubkey.length != 64 || pubkey.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return AddressCoordinate(kind, pubkey, parts[2])
        }

        private val ADDRESSABLE_KINDS = 30_000..39_999
    }
}

/** This event's address, or null when it isn't addressable. */
fun Event.addressCoordinate(): AddressCoordinate? =
    if (kind in 30_000..39_999) AddressCoordinate(kind, pubkey.lowercase(), getTagValue("d").orEmpty()) else null
