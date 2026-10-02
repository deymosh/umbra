package com.umbra.app.domain.usecase

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.addressCoordinate
import com.umbra.app.domain.nip51.followSetMembersOf
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.ContactListRepository
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
 * The authors a feed filter limits the feed to: the user's follow list when it scopes to follows,
 * plus the members of the user's own follow sets (NIP-51, kind 30000) it names. Empty for an
 * unscoped filter, meaning every relay's notes.
 *
 * A scoped filter that resolves to nobody yet (a follow list or set not loaded, or empty) yields
 * just the user, so the feed stays limited instead of falling open to everyone. Private set members
 * count when the signer already lets Umbra read them; this never makes it ask.
 *
 * The one place the feed's authors are worked out: the feed itself, its relay subscription and the
 * app-level session all read it, so they can't disagree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveFeedAuthorsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val contactListRepository: ContactListRepository,
    private val userPreferences: UserPreferences,
    private val decryptOwnListItems: DecryptOwnListItemsUseCase
) {
    operator fun invoke(filter: FeedFilter): Flow<Set<String>> {
        if (!filter.isScoped) return flowOf(emptySet())
        return userPreferences.getPublicKeyFlow()
            .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
            .distinctUntilChanged()
            .flatMapLatest { owner -> if (owner == null) flowOf(emptySet()) else authorsFor(owner, filter) }
            .distinctUntilChanged()
    }

    private fun authorsFor(owner: String, filter: FeedFilter): Flow<Set<String>> {
        val follows = if (filter.scopeToFollows) {
            contactListRepository.getContactList(owner).map { list -> list?.followedPubkeys.orEmpty().mapTo(HashSet()) { it.lowercase() } }
        } else {
            flowOf(emptySet<String>())
        }
        val sets = if (filter.followSets.isEmpty()) {
            flowOf(emptyList<Event>())
        } else {
            eventRepository.observeEventsByPubkeyAndKind(owner, Event.KIND_FOLLOW_SET, MAX_SETS)
        }
        return combine(follows, sets) { followed, setEvents -> followed to setEvents }
            .mapLatest { (followed, setEvents) ->
                val members = chosenSets(setEvents, filter.followSets).flatMapTo(HashSet()) { set ->
                    followSetMembersOf(set, decryptOwnListItems(set.content, interactive = false)).map { it.pubkey }
                }
                (followed + members).ifEmpty { setOf(owner) }
            }
    }

    /** The newest revision of each set named in [addresses]. */
    private fun chosenSets(events: List<Event>, addresses: Set<String>): List<Event> =
        events.groupBy { it.addressCoordinate()?.toString() }
            .filterKeys { it != null && it in addresses }
            .values
            .map { revisions -> revisions.maxBy { it.createdAt } }

    private companion object {
        const val MAX_SETS = 500
    }
}
