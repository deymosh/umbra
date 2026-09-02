package com.umbra.app.domain.relay

import com.umbra.app.domain.nip11.RelayInfo
import kotlinx.serialization.Serializable

/**
 * Data model representing a Nostr relay configuration.
 * Relays are servers that store and distribute Nostr events.
 * Can be .onion (Tor-only) or clearnet (accessible via TOR proxy).
 */
@Serializable
data class Relay(
    // Unique identifier for the relay
    val id: String,

    // Relay WebSocket URL (ws:// or wss://)
    val url: String,

    // Whether this relay is enabled for connections
    val isEnabled: Boolean = true,

    // Whether to read events from this relay
    val isReadEnabled: Boolean = true,

    // Whether inbox traffic is currently active for this relay
    val isReadActive: Boolean = isEnabled && isReadEnabled,

    // Whether to publish events to this relay
    val isWriteEnabled: Boolean = true,

    // Whether outbox traffic is currently active for this relay
    val isWriteActive: Boolean = isEnabled && isWriteEnabled,

    // Whether this relay is enabled for private messaging transport (NIP-17)
    val isDmEnabled: Boolean = false,

    // Whether DM traffic is currently active for this relay
    val isDmActive: Boolean = isEnabled && isDmEnabled,

    // DM transport should use authenticated relays (NIP-42)
    val dmRequiresAuth: Boolean = false,

    // Whether this relay is part of the user's declared NIP-51 search relay list (kind 10007)
    val isSearchEnabled: Boolean = false,

    // Whether search traffic is currently active for this relay
    val isSearchActive: Boolean = isEnabled && isSearchEnabled,

    // Whether this relay is part of the user's declared index relay list (kind 10086)
    val isIndexEnabled: Boolean = false,

    // Whether index traffic is currently active for this relay
    val isIndexActive: Boolean = isEnabled && isIndexEnabled,

    // Whether this is a .onion relay (Tor-only)
    val isOnion: Boolean = false,

    // True for relays auto-added by UserRepositoryImpl.saveRelayList() to cover a tracked
    // author's outbox — never the user's own logged-in/manually-configured relays. Kept out of
    // the Outbox/Inbox/DM sections in RelayConfigScreen; shown in a separate "Discovered" section
    // instead so the two are never visually conflated.
    val isDiscovered: Boolean = false,

    // Connection timeout in milliseconds
    val connectionTimeoutMs: Long = 5000,

    // Timestamp of when this relay was added
    val addedAtMillis: Long = System.currentTimeMillis(),

    // Optional relay information (NIP-11)
    val relayInfo: RelayInfo? = null,

    // Epoch millis when NIP-11 was last successfully fetched from network (null = never)
    val nip11FetchedAtMillis: Long? = null
) {
    fun hasAnyAssignedRole(): Boolean = isReadEnabled || isWriteEnabled || isDmEnabled || isSearchEnabled || isIndexEnabled

    fun hasAnyActiveRole(): Boolean = isReadActive || isWriteActive || isDmActive || isSearchActive || isIndexActive
}

/**
 * Shared relay ID generator used across manual and automatic relay creation flows.
 */
object RelayIdGenerator {
    fun create(seed: Long = System.currentTimeMillis()): String = "relay_$seed"
}

/**
 * Bootstrap relays used when the user has no saved relay configuration yet.
 */



