package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.RelayRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * NIP-45 COUNT for [filters], asked of every connected relay that advertises NIP-45 in its NIP-11
 * document (a relay that doesn't, or whose document isn't known yet, is skipped rather than sent
 * a request it may reject). Emits the best answer so far — the maximum across relays, since each
 * only counts what it stores — and nothing until the first relay replies. Re-asks when the set of
 * connected NIP-45 relays changes.
 */
class ObserveRemoteCountUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val relayRepository: RelayRepository
) {
    operator fun invoke(subscriptionId: String, filters: List<EventFilter>): Flow<Long> = channelFlow {
        val perRelay = MutableStateFlow<Map<String, Long>>(emptyMap())
        launch {
            eventRepository.observeRelayCounts()
                .filter { it.subscriptionId == subscriptionId }
                .collect { result ->
                    perRelay.update { it + (result.relayUrl.lowercase() to result.count.coerceAtLeast(0L)) }
                }
        }
        launch {
            combine(relayRepository.getAllRelays(), eventRepository.observeConnectedRelayUrls()) { relays, connected ->
                val connectedNormalized = connected.mapTo(HashSet()) { normalizeRelayUrl(it) }
                relays.asSequence()
                    .filter { it.relayInfo?.supportedNips?.contains(45) == true }
                    .map { normalizeRelayUrl(it.url) }
                    .filter { it in connectedNormalized }
                    .toSet()
            }
                .distinctUntilChanged()
                .collect { nip45Relays ->
                    nip45Relays.forEach { relayUrl ->
                        eventRepository.requestCount(relayUrl = relayUrl, subscriptionId = subscriptionId, filters = filters)
                    }
                }
        }
        perRelay.collect { counts -> counts.values.maxOrNull()?.let { send(it) } }
    }
}
