package com.umbra.app.domain.nip65

import com.umbra.app.domain.nip01.Event
import kotlinx.serialization.Serializable

/**
 * Relay List Metadata (NIP-65 kind 10002).
 * User's preferred inbox/outbox relay configuration.
 */
@Serializable
data class RelayListMetadata(
    val pubkey: String,
    val writeRelays: List<String> = emptyList(),    // Outbox relays (published events)
    val readRelays: List<String> = emptyList(),     // Inbox relays (mentions/replies)
    val allRelays: List<String> = emptyList(),      // Relays without r tag or general use
    val lastUpdated: Long = 0
) {
    /**
     * Get all relays for publishing (write relays)
     */
    fun getOutboxRelays(): List<String> {
        return (writeRelays + allRelays).distinct()
    }

    /**
     * Get all relays for receiving (read relays)
     */
    fun getInboxRelays(): List<String> {
        return (readRelays + allRelays).distinct()
    }

    /**
     * Every relay this list declares, regardless of role (write/read/unmarked) — for relay
     * *discovery* purposes, where a read-only relay is just as real/reachable as a write one.
     * [getOutboxRelays]/[getInboxRelays] stay role-scoped since they feed local role application
     * (see UserRepositoryImpl.applyRelayListToLocalConfig), which does need the distinction.
     */
    fun getAllDeclaredRelays(): List<String> {
        return (writeRelays + readRelays + allRelays).distinct()
    }

    companion object {
        /**
         * Create from kind 10002 event
         */
        fun fromEvent(event: Event): RelayListMetadata {
            val writeRelays = mutableListOf<String>()
            val readRelays = mutableListOf<String>()
            val allRelays = mutableListOf<String>()

            event.tags.forEach { tag ->
                if (tag.isNotEmpty() && tag[0] == "r") {
                    val relayUrl = tag.getOrNull(1) ?: return@forEach
                    val marker = tag.getOrNull(2)

                    when (marker) {
                        "write" -> writeRelays.add(relayUrl)
                        "read" -> readRelays.add(relayUrl)
                        else -> allRelays.add(relayUrl)
                    }
                }
            }

            return RelayListMetadata(
                pubkey = event.pubkey,
                writeRelays = writeRelays,
                readRelays = readRelays,
                allRelays = allRelays,
                lastUpdated = event.createdAt
            )
        }
    }
}
