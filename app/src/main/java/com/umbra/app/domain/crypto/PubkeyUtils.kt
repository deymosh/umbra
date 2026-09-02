package com.umbra.app.domain.crypto

import com.umbra.app.domain.nip19.Bech32Encoder

fun normalizePubkey(value: String): String {
    val candidate = value.trim().lowercase()
    if (candidate.startsWith("npub1")) {
        val decoded = Bech32Encoder.decodeNpub(candidate)
        if (!decoded.isNullOrBlank()) {
            return decoded.lowercase()
        }
    }
    return candidate
}
