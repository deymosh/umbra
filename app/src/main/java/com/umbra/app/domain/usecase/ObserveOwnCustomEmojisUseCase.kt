package com.umbra.app.domain.usecase

import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.EmojiGroup
import com.umbra.app.domain.nip30.EmojiSetAddress
import com.umbra.app.domain.nip30.KIND_EMOJI_SET
import com.umbra.app.domain.nip30.KIND_USER_EMOJI_LIST
import com.umbra.app.domain.nip30.coordinate
import com.umbra.app.domain.nip30.extractCustomEmojis
import com.umbra.app.domain.nip30.parseUserEmojiList
import com.umbra.app.domain.repository.LocalEmojiPackRepository
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

/** A kind-30030 set's resolved content: its address, emoji list and display title. */
data class EmojiSetContent(
    val address: EmojiSetAddress,
    val emojis: List<CustomEmoji>,
    val title: String
)

/**
 * The signed-in user's NIP-30 emoji catalog grouped by source: the inline emoji of their
 * kind-10030 list first (untitled group), then one titled group per kind-30030 set — the sets
 * their published list references, then the ones they keep only on this device
 * ([LocalEmojiPackRepository]). Shortcodes are deduplicated across groups, first occurrence wins.
 * The list and every set are requested from relays while collected.
 */
class ObserveOwnCustomEmojisUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val localEmojiPacks: LocalEmojiPackRepository
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(pubkey: String): Flow<List<EmojiGroup>> = channelFlow {
        val listChannel = NostrChannels.emojiList(pubkey)
        val setsChannel = NostrChannels.emojiSets(pubkey)
        eventRepository.subscribeChannel(
            listChannel,
            listOf(EventFilter(authors = setOf(pubkey), kinds = setOf(KIND_USER_EMOJI_LIST), limit = 1))
        )

        launch {
            combine(
                eventRepository.observeEventsByPubkeyAndKind(pubkey, KIND_USER_EMOJI_LIST, 1)
                    .map { events -> parseUserEmojiList(events.maxByOrNull { it.createdAt }?.tags.orEmpty()) },
                localEmojiPacks.observeLocalPacks(pubkey)
            ) { (inline, published), local -> inline to (published + local).distinct() }
                .distinctUntilChanged()
                .flatMapLatest { (inline, sets) ->
                    if (sets.isEmpty()) {
                        eventRepository.clearChannel(setsChannel)
                        flowOf(if (inline.isEmpty()) emptyList() else listOf(EmojiGroup(title = null, emojis = inline)))
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
                        combine(sets.map(::observeSet)) { setContents ->
                            toGroups(inline, setContents.toList())
                        }
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

    private fun observeSet(address: EmojiSetAddress): Flow<EmojiSetContent> =
        eventRepository.observeEventsByPubkeyAndKind(address.pubkey, KIND_EMOJI_SET, SET_SCAN_LIMIT)
            .map { events ->
                val event = events.asSequence()
                    .filter { it.getTagValue("d") == address.identifier }
                    .maxByOrNull { it.createdAt }
                EmojiSetContent(
                    address = address,
                    emojis = event?.let { extractCustomEmojis(it.tags).values.toList() }.orEmpty()
                        .map { it.copy(setCoordinate = address.coordinate()) },
                    title = event?.getTagValue("title") ?: address.identifier
                )
            }

    private companion object {
        const val SET_SCAN_LIMIT = 100
    }
}

/**
 * Builds the grouped catalog: the inline group leads when non-empty, then each referenced
 * set keeps its list position and empty groups are dropped (an unresolved set has none).
 * A shortcode already seen in an earlier group is removed from later groups.
 */
internal fun toGroups(
    inline: List<CustomEmoji>,
    setContents: List<EmojiSetContent>
): List<EmojiGroup> {
    val groups = buildList {
        if (inline.isNotEmpty()) add(EmojiGroup(title = null, emojis = inline))
        for (content in setContents) {
            if (content.emojis.isNotEmpty()) add(EmojiGroup(title = content.title, emojis = content.emojis))
        }
    }
    val seen = mutableSetOf<String>()
    return groups.map { group ->
        val kept = group.emojis.filter { seen.add(it.shortcode) }
        if (kept.size == group.emojis.size) group else group.copy(emojis = kept)
    }.filter { it.emojis.isNotEmpty() }
}
