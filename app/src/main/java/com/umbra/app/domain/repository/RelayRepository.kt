package com.umbra.app.domain.repository

import com.umbra.app.domain.relay.Relay
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for relay management.
 * Abstracts data sources (database, preferences, etc.)
 */
interface RelayRepository {

    /**
     * Get all configured relays as a stream of updates
     */
    fun getAllRelays(): Flow<List<Relay>>

    /**
     * Get a single relay by ID
     */
    suspend fun getRelayById(id: String): Relay?

    /**
     * Add a new relay configuration
     */
    suspend fun addRelay(relay: Relay)

    /**
     * Update an existing relay
     */
    suspend fun updateRelay(relay: Relay)

    /**
     * Remove a relay by ID
     */
    suspend fun removeRelay(id: String)

    /**
     * Seed default relays only for a true first-login scenario.
     * Must not override an existing empty list chosen by the user.
     */
    suspend fun bootstrapDefaultsOnFirstLogin()

    /**
     * Remove all persisted relay configuration for the current user/session.
     */
    suspend fun clearUserRelayConfig()
}

