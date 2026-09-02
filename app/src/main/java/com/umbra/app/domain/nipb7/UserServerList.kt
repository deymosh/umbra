package com.umbra.app.domain.nipb7

import com.umbra.app.domain.nip01.Event
import kotlinx.serialization.Serializable

/**
 * Blossom User Server List (BUD-03 kind 10063).
 * Advertises the Blossom servers a user uploads their blobs to, in priority order — the first
 * `server` tag is their most "reliable"/"trusted" one (BUD-03 explicitly orders this list by
 * priority, unlike [com.umbra.app.domain.nip65.RelayListMetadata]'s read/write-marked tags).
 */
@Serializable
data class UserServerList(
    val pubkey: String,
    val servers: List<String> = emptyList(),
    val lastUpdated: Long = 0
) {
    companion object {
        /** Parse from a kind 10063 event. */
        fun fromEvent(event: Event): UserServerList {
            val servers = event.tags
                .filter { it.size >= 2 && it[0] == "server" }
                .mapNotNull { it[1].takeIf(String::isNotBlank) }
                .distinct()
            return UserServerList(
                pubkey = event.pubkey,
                servers = servers,
                lastUpdated = event.createdAt
            )
        }
    }
}

/**
 * BUD-03 "Client Upload Implementation": clients MUST attempt upload to at least the first
 * `server` in the user's own list. Falls back to [DefaultBlossomServer.URL] when the user has
 * no server list yet (new account, or one that never published kind:10063) so upload still works
 * out of the box — same bootstrap rationale as [DefaultBlossomServer] itself.
 */
fun UserServerList?.preferredUploadServer(): String =
    this?.servers?.firstOrNull() ?: DefaultBlossomServer.URL
