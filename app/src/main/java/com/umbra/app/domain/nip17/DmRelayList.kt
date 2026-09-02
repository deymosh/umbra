package com.umbra.app.domain.nip17

import com.umbra.app.domain.nip01.Event
import kotlinx.serialization.Serializable

/**
 * DM Relay List (NIP-17 / NIP-51 kind 10050).
 * User's preferred relays for receiving NIP-17 direct messages.
 */
@Serializable
data class DmRelayList(
    val pubkey: String,
    val relays: List<String> = emptyList(),
    val lastUpdated: Long = 0
) {
    companion object {
        /** Parse from a kind 10050 event. */
        fun fromEvent(event: Event): DmRelayList {
            val relays = event.tags
                .filter { it.size >= 2 && it[0] == "relay" }
                .mapNotNull { it[1].takeIf(String::isNotBlank) }
                .distinct()
            return DmRelayList(
                pubkey = event.pubkey,
                relays = relays,
                lastUpdated = event.createdAt
            )
        }
    }
}
