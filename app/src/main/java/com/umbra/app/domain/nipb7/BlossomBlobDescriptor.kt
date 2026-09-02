package com.umbra.app.domain.nipb7

import kotlinx.serialization.Serializable

/**
 * Blossom (BUD-02) blob descriptor returned by a server after a successful upload.
 */
@Serializable
data class BlossomBlobDescriptor(
    val url: String,
    val sha256: String,
    val size: Long,
    val mimeType: String? = null
)

/**
 * Single hardcoded bootstrap server, same rationale as `DefaultRelays` — something reachable
 * out of the box without asking the user to configure a media server first.
 */
object DefaultBlossomServer {
    const val URL = "https://nostr.download"
}
