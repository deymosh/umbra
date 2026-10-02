package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip51.ListSet
import com.umbra.app.domain.nip51.latestListSets
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The signed-in user's NIP-51 sets of [kind] (newest revision each, by title), from their own archive. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveOwnListSetsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val userPreferences: UserPreferences
) {
    operator fun invoke(kind: Int): Flow<List<ListSet>> = userPreferences.getPublicKeyFlow()
        .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
        .distinctUntilChanged()
        .flatMapLatest { owner ->
            if (owner == null) flowOf(emptyList()) else eventRepository.observeEventsByPubkeyAndKind(owner, kind, MAX_SETS).map(::latestListSets)
        }

    private companion object {
        const val MAX_SETS = 500
    }
}
