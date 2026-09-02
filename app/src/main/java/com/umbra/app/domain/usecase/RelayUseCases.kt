package com.umbra.app.domain.usecase

import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.repository.RelayRepository
import kotlinx.coroutines.flow.Flow

/**
 * Get all relays as a reactive flow
 */
class GetAllRelaysUseCase(
    private val relayRepository: RelayRepository
) {
    operator fun invoke(): Flow<List<Relay>> = relayRepository.getAllRelays()
}

/**
 * Add a new relay configuration
 */
class AddRelayUseCase(
    private val relayRepository: RelayRepository
) {
    suspend operator fun invoke(relay: Relay) {
        relayRepository.addRelay(relay)
    }
}

/**
 * Update an existing relay configuration
 */
class UpdateRelayUseCase(
    private val relayRepository: RelayRepository
) {
    suspend operator fun invoke(relay: Relay) {
        relayRepository.updateRelay(relay)
    }
}

/**
 * Remove a relay by ID
 */
class RemoveRelayUseCase(
    private val relayRepository: RelayRepository
) {
    suspend operator fun invoke(relayId: String) {
        relayRepository.removeRelay(relayId)
    }
}
