package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.repository.EventRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One NIP-45 COUNT against one relay: sends the request and waits for that relay's single answer.
 * Returns null when the relay doesn't answer within [timeoutMs] — including a relay that doesn't
 * advertise NIP-45, which the repository never sends the request to at all.
 */
class CountOnRelayUseCase @Inject constructor(private val eventRepository: EventRepository) {
    suspend operator fun invoke(
        relayUrl: String,
        filter: EventFilter,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): RelayCountResult? = coroutineScope {
        val subscriptionId = "count-" + UUID.randomUUID().toString().take(12)
        // Subscribed before the request goes out, so an answer that comes back quickly isn't missed.
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            eventRepository.observeRelayCounts().first { it.subscriptionId == subscriptionId }
        }
        eventRepository.requestCount(relayUrl = relayUrl, subscriptionId = subscriptionId, filters = listOf(filter))
        withTimeoutOrNull(timeoutMs) { answer.await() }.also { answer.cancel() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
