package com.umbra.app.domain.usecase

import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.EmojiSetAddress
import com.umbra.app.domain.nip30.KIND_EMOJI_SET
import com.umbra.app.domain.nip30.KIND_USER_EMOJI_LIST
import com.umbra.app.domain.nip30.extractCustomEmojis
import com.umbra.app.domain.nip30.parseUserEmojiList
import com.umbra.app.domain.repository.EventRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The signed-in user's NIP-30 custom emoji: the inline emoji of their kind-10030 list plus every
 * emoji of the kind-30030 sets it references, deduplicated by shortcode (inline entries win).
 * Both the list and the referenced sets are requested from relays while collected.
 */
class ObserveOwnCustomEmojisUseCase @Inject constructor(private val eventRepository: EventRepository) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(pubkey: String): Flow<List<CustomEmoji>> = channelFlow {
        val listChannel = NostrChannels.emojiList(pubkey)
        val setsChannel = NostrChannels.emojiSets(pubkey)
        eventRepository.subscribeChannel(
            listChannel,
            listOf(EventFilter(authors = setOf(pubkey), kinds = setOf(KIND_USER_EMOJI_LIST), limit = 1))
        )

        launch {
            eventRepository.observeEventsByPubkeyAndKind(pubkey, KIND_USER_EMOJI_LIST, 1)
                .map { events -> parseUserEmojiList(events.maxByOrNull { it.createdAt }?.tags.orEmpty()) }
                .distinctUntilChanged()
                .flatMapLatest { (inline, sets) ->
                    if (sets.isEmpty()) {
                        eventRepository.clearChannel(setsChannel)
                        flowOf(inline)
                    } else {
                        eventRepository.subscribeChannel(
                            setsChannel,
                            listOf(
                                EventFilter(
                                    authors = sets.mapTo(HashSet()) { it.pubkey },
                                    kinds = setOf(KIND_EMOJI_SET),
                                    tagFilters = mapOf("d" to sets.mapTo(HashSet()) { it.identifier })
                                )
                            )
                        )
                        combine(sets.map(::observeSet)) { setEmojis -> (inline + setEmojis.flatMap { it }).distinctBy { it.shortcode } }
                    }
                }
                .distinctUntilChanged()
                .collect { send(it) }
        }

        awaitClose {
            eventRepository.clearChannel(listChannel)
            eventRepository.clearChannel(setsChannel)
        }
    }

    private fun observeSet(address: EmojiSetAddress): Flow<List<CustomEmoji>> =
        eventRepository.observeEventsByPubkeyAndKind(address.pubkey, KIND_EMOJI_SET, SET_SCAN_LIMIT)
            .map { events ->
                events.asSequence()
                    .filter { it.getTagValue("d") == address.identifier }
                    .maxByOrNull { it.createdAt }
                    ?.let { extractCustomEmojis(it.tags).values.toList() }
                    .orEmpty()
            }

    private companion object {
        const val SET_SCAN_LIMIT = 100
    }
}
