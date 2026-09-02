package com.umbra.app.domain.usecase

import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository

/**
 * Resolves which relays are worth dialing right now for [pubkey]'s content — the declared NIP-65
 * outbox relays if already cached, unioned with whatever [EventRepository.getRelayHints] has
 * accumulated for this pubkey (relays it's been *seen* hinted at, e.g. via a NIP-19 nprofile/
 * nevent TLV elsewhere). The union matters for a profile viewed for the first time: its kind:10002
 * may not be cached yet, so the declared-relays side alone would resolve to nothing to dial even
 * though a relay hint for this exact pubkey might already be known.
 */
class ResolveProfileRelayHintsUseCase(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository
) {
    operator fun invoke(pubkey: String): List<String> {
        val declared = userRepository.getRelayList(pubkey)?.getAllDeclaredRelays().orEmpty()
        val hinted = eventRepository.getRelayHints(pubkey)
        return (declared + hinted).distinct()
    }
}
