package com.umbra.app.domain.nipb7

import com.umbra.app.domain.util.toHex
import java.security.MessageDigest

/** SHA-256 of [bytes] as lowercase hex — the `x` tag value Blossom auth events require. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.toHex()
}
