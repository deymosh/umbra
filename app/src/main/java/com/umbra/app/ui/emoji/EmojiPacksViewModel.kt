package com.umbra.app.ui.emoji

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip30.EmojiPack
import com.umbra.app.domain.nip30.EmojiSetAddress
import com.umbra.app.domain.nip30.coordinate
import com.umbra.app.domain.nip30.latestEmojiPacks
import com.umbra.app.domain.nip30.parseUserEmojiList
import com.umbra.app.domain.nip51.ListEdit
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.LocalEmojiPackRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.common.InteractionActionsCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a pack the user picked lives: in their published kind-10030 list, or only on this device. */
enum class PackStorage { PUBLIC_LIST, THIS_DEVICE }

/** A pack the user picked; [pack] is null until its kind-30030 event arrives. */
@Immutable
data class OwnedEmojiPack(val address: EmojiSetAddress, val storage: PackStorage, val pack: EmojiPack?)

@Immutable
data class EmojiPacksState(
    val yourPacks: List<OwnedEmojiPack> = emptyList(),
    val discover: List<EmojiPack> = emptyList(),
    val authors: Map<String, UserProfile> = emptyMap(),
    val query: String = "",
    /** False for a read-only session: packs can still be kept on this device. */
    val canPublish: Boolean = false
) {
    val pickedAddresses: Set<EmojiSetAddress> get() = yourPacks.mapTo(HashSet()) { it.address }

    /** Discover results matching [query] by title, description or shortcode. */
    val visibleDiscover: List<EmojiPack>
        get() {
            val q = query.trim().trim(':')
            if (q.isEmpty()) return discover
            return discover.filter { pack ->
                pack.title.contains(q, ignoreCase = true) ||
                    pack.description?.contains(q, ignoreCase = true) == true ||
                    pack.emojis.any { it.shortcode.contains(q, ignoreCase = true) }
            }
        }
}

/**
 * Browse recent NIP-51 emoji packs (kind 30030) and pick the ones to use. A picked pack goes
 * either into the user's public kind-10030 list (signed and published, so their other clients
 * see it) or only into this device's encrypted storage, never published. The composer's catalog
 * reads both.
 */
@HiltViewModel
class EmojiPacksViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val localEmojiPacks: LocalEmojiPackRepository,
    coordinatorFactory: InteractionActionsCoordinator.Factory
) : ViewModel() {

    private val actions = coordinatorFactory.create(viewModelScope)
    private val owner: String? = userPreferences.getPublicKey()?.lowercase()

    private val _state = MutableStateFlow(EmojiPacksState(canPublish = actions.canSignEvents()))
    val state: StateFlow<EmojiPacksState> = _state.asStateFlow()

    // Coordinates in the published list, for the list edit's fallback when no list is cached yet.
    private var publishedCoordinates: Set<String> = emptySet()

    init {
        eventRepository.subscribeChannel(
            NostrChannels.EMOJI_PACK_BROWSE,
            listOf(
                EventFilter(
                    kinds = setOf(Event.KIND_EMOJI_SET),
                    since = System.currentTimeMillis() / 1000 - BROWSE_WINDOW_SECS,
                    limit = BROWSE_LIMIT
                )
            )
        )
        observeDiscover()
        observeYourPacks()
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    /** Picks [pack]: [storage] decides whether it is published or kept on this device. */
    fun addPack(pack: EmojiPack, storage: PackStorage) {
        val me = owner ?: return
        when (storage) {
            PackStorage.THIS_DEVICE -> viewModelScope.launch { localEmojiPacks.addLocalPack(me, pack.address) }
            PackStorage.PUBLIC_LIST -> publishListEdit(ListEdit("a", add = setOf(pack.address.coordinate())))
        }
    }

    fun removePack(owned: OwnedEmojiPack) {
        val me = owner ?: return
        when (owned.storage) {
            PackStorage.THIS_DEVICE -> viewModelScope.launch { localEmojiPacks.removeLocalPack(me, owned.address) }
            PackStorage.PUBLIC_LIST -> publishListEdit(ListEdit("a", remove = setOf(owned.address.coordinate())))
        }
    }

    /**
     * Moves a picked pack between the public list and this device. The local side only changes
     * once the list edit is signed, so a cancelled signature leaves the pack where it was.
     */
    fun movePack(owned: OwnedEmojiPack) {
        val me = owner ?: return
        when (owned.storage) {
            PackStorage.THIS_DEVICE -> publishListEdit(
                ListEdit("a", add = setOf(owned.address.coordinate())),
                onSigned = { localEmojiPacks.removeLocalPack(me, owned.address) }
            )
            PackStorage.PUBLIC_LIST -> publishListEdit(
                ListEdit("a", remove = setOf(owned.address.coordinate())),
                onSigned = { localEmojiPacks.addLocalPack(me, owned.address) }
            )
        }
    }

    private fun publishListEdit(edit: ListEdit, onSigned: suspend () -> Unit = {}) {
        if (!actions.canSignEvents()) return
        actions.requestSignAndPublish(
            buildEventJson = { actions.buildListEdit(Event.KIND_USER_EMOJI_LIST, edit, fallbackValues = publishedCoordinates) },
            currentUserHex = owner,
            onSigned = onSigned
        )
    }

    private fun observeDiscover() {
        viewModelScope.launch {
            eventRepository.getCachedEvents()
                .map { events -> latestEmojiPacks(events.filter { it.kind == Event.KIND_EMOJI_SET }) }
                .distinctUntilChanged()
                .collect { packs ->
                    val authors = userRepository.getProfilesByPubkey(packs.map { it.address.pubkey }.toSet())
                    _state.update { it.copy(discover = packs, authors = it.authors + authors) }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeYourPacks() {
        val me = owner ?: return
        viewModelScope.launch {
            val published: Flow<List<EmojiSetAddress>> =
                eventRepository.observeEventsByPubkeyAndKind(me, Event.KIND_USER_EMOJI_LIST, 1)
                    .map { events -> parseUserEmojiList(events.maxByOrNull { it.createdAt }?.tags.orEmpty()).second }
            combine(published, localEmojiPacks.observeLocalPacks(me)) { public, local ->
                publishedCoordinates = public.mapTo(HashSet()) { it.coordinate() }
                public.map { it to PackStorage.PUBLIC_LIST } +
                    local.filterNot { it in public }.map { it to PackStorage.THIS_DEVICE }
            }
                .distinctUntilChanged()
                .flatMapLatest { picked ->
                    if (picked.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(picked.map { (address, storage) -> observePack(address).map { OwnedEmojiPack(address, storage, it) } }) {
                            it.toList()
                        }
                    }
                }
                .collect { packs -> _state.update { it.copy(yourPacks = packs) } }
        }
    }

    // Own packs come from the archive, everyone else's from the cache; the app-wide catalog keeps
    // every picked set requested from relays.
    private fun observePack(address: EmojiSetAddress): Flow<EmojiPack?> =
        eventRepository.observeEventsByPubkeyAndKind(address.pubkey, Event.KIND_EMOJI_SET, PACK_SCAN_LIMIT)
            .map { events -> latestEmojiPacks(events).firstOrNull { it.address == address } }
            .distinctUntilChanged()

    override fun onCleared() {
        eventRepository.clearChannel(NostrChannels.EMOJI_PACK_BROWSE)
    }

    private companion object {
        const val BROWSE_WINDOW_SECS = 90L * 24 * 60 * 60
        const val BROWSE_LIMIT = 200
        const val PACK_SCAN_LIMIT = 100
    }
}
