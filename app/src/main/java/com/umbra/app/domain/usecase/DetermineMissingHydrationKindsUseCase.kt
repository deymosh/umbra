package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.firstOrNull

/**
 * Given a pubkey and a candidate set of profile-hydration kinds, returns only the ones not
 * already cached locally.
 *
 * Every hydration call (bootstrap, backfill, referenced-author batch) used to always request the
 * full applicable kind set regardless of what was already in Room — so a relay that had already
 * told us everything about a pubkey except its kind:10002 relay list kept being asked about
 * kind:0/3/10000/10050 again on every later hydration pass for that same pubkey. This use case
 * lets callers restrict the outgoing filter (via [BuildProfileHydrationFiltersUseCase]'s
 * `restrictToKinds`) to just what's still missing.
 *
 * Owner-only kinds (10007/10086, see [BuildProfileHydrationFiltersUseCase]) have no local
 * presence check here and are always reported missing if asked about — callers only pass them in
 * [candidateKinds] for the signed-in user's own pubkey in the first place.
 */
class DetermineMissingHydrationKindsUseCase(
    private val userRepository: UserRepository,
    private val contactListRepository: ContactListRepository,
    private val muteListRepository: MuteListRepository
) {
    suspend operator fun invoke(pubkey: String, candidateKinds: Set<Int>): Set<Int> {
        val missing = mutableSetOf<Int>()
        for (kind in candidateKinds) {
            val alreadyKnown = when (kind) {
                Event.KIND_METADATA -> userRepository.getProfile(pubkey) != null
                Event.KIND_RELAY_LIST_METADATA -> userRepository.getRelayList(pubkey) != null
                Event.KIND_DM_RELAY_LIST -> userRepository.getDmRelayList(pubkey) != null
                Event.KIND_BLOSSOM_SERVER_LIST -> userRepository.getServerList(pubkey) != null
                Event.KIND_CONTACT_LIST -> contactListRepository.getContactList(pubkey).firstOrNull() != null
                Event.KIND_MUTED_USERS -> muteListRepository.getMuteList(pubkey).firstOrNull() != null
                else -> false
            }
            if (!alreadyKnown) missing += kind
        }
        return missing
    }
}
