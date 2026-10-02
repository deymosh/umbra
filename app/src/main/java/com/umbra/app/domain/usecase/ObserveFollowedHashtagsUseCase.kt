package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip51.followedHashtagsOf
import com.umbra.app.domain.nip51.listedHashtagsOf
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * The hashtags the signed-in user follows (NIP-51 interests, kind 10015), read from their own
 * archive, which the login subscription keeps current. Private items count when the signer
 * already lets Umbra read them; this never makes it ask.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveFollowedHashtagsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val userPreferences: UserPreferences,
    private val decryptOwnListItems: DecryptOwnListItemsUseCase
) {
    /** Hashtags on the list itself and in the user's own interest sets it points to. */
    operator fun invoke(): Flow<Set<String>> = ownerFlow().flatMapLatest { owner ->
        if (owner == null) {
            flowOf(emptySet())
        } else {
            combine(
                eventRepository.observeEventsByPubkeyAndKind(owner, Event.KIND_INTERESTS_LIST, 1),
                eventRepository.observeEventsByPubkeyAndKind(owner, Event.KIND_INTEREST_SET, MAX_SETS)
            ) { lists, sets -> lists.firstOrNull() to sets }
                .mapLatest { (list, sets) -> followedHashtagsOf(list, privateItemsOf(list), sets) }
        }
    }.distinctUntilChanged()

    /** Hashtags named on the list itself: what following or unfollowing one edits. */
    fun listed(): Flow<Set<String>> = ownerFlow().flatMapLatest { owner ->
        if (owner == null) {
            flowOf(emptySet())
        } else {
            eventRepository.observeEventsByPubkeyAndKind(owner, Event.KIND_INTERESTS_LIST, 1)
                .mapLatest { lists -> lists.firstOrNull().let { listedHashtagsOf(it, privateItemsOf(it)) } }
        }
    }.distinctUntilChanged()

    private fun ownerFlow(): Flow<String?> = userPreferences.getPublicKeyFlow()
        .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
        .distinctUntilChanged()

    private suspend fun privateItemsOf(list: Event?): List<List<String>>? =
        decryptOwnListItems(list?.content.orEmpty(), interactive = false)

    private companion object {
        const val MAX_SETS = 200
    }
}
