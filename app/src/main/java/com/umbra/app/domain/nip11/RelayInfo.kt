package com.umbra.app.domain.nip11

import kotlinx.serialization.Serializable

/**
 * Relay information document model (NIP-11).
 */
@Serializable
data class RelayInfo(
    // Relay banner URL (NIP-11)
    val banner: String? = null,

    // Relay icon URL (NIP-11)
    val icon: String? = null,

    // Human-readable name
    val name: String? = null,

    // Relay description
    val description: String? = null,

    // Contact information
    val contact: String? = null,

    // Pubkey of relay operator
    val pubkey: String? = null,

    // Relay's own pubkey (NIP-11 "self")
    val self: String? = null,

    // Software version
    val software: String? = null,

    // Relay software version from NIP-11
    val version: String? = null,

    // Supported NIP numbers exposed by relay
    val supportedNips: List<Int> = emptyList(),

    // Terms of service URL (NIP-11)
    val termsOfService: String? = null,

    // Maximum subscriptions per client
    val maxSubscriptions: Int? = null,

    // Maximum events per request
    val maxLimitEventCount: Int? = null,

    // Days of historical events available
    val maxEventComplexity: Int? = null,

    // Minimum Pow difficulty for events
    val minPoW: Int? = null,

    // Whether the relay requires payment
    val requiresPayment: Boolean = false,

    // Whether auth is required (NIP-42)
    val requiresAuth: Boolean = false
)
