package com.umbra.app.ui.bookmarks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip51.Bookmark
import com.umbra.app.domain.nip51.BookmarkTarget
import com.umbra.app.domain.nip51.ListEdit
import com.umbra.app.domain.nip51.ListSet
import com.umbra.app.domain.nip51.bookmarksOf
import com.umbra.app.domain.nip51.latestListSets
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.DecryptOwnListItemsUseCase
import com.umbra.app.ui.common.InteractionActionsCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A bookmarked event as the Bookmarks screen shows it. */
@Immutable
data class BookmarkedEvent(val event: Event, val isPrivate: Boolean)

/** How much of the shown list's private part Umbra could read. */
enum class PrivateBookmarksState {
    /** Nothing private, or all of it is shown. */
    READ,
    /** The signer hasn't been asked yet; the user can unlock them. */
    LOCKED,
    /** The signer refused, or they were saved in a format it can't read here. */
    UNREADABLE
}

/** A bookmark set as a chip on the Bookmarks screen. */
@Immutable
data class BookmarkSetChip(val identifier: String, val title: String)

/** A set in the "Add to list" sheet, with whether the note is already in it. */
@Immutable
data class BookmarkSetChoice(val identifier: String, val title: String, val contains: Boolean)

@Immutable
data class BookmarksState(
    val items: List<BookmarkedEvent> = emptyList(),
    val profiles: Map<String, UserProfile> = emptyMap(),
    val privateState: PrivateBookmarksState = PrivateBookmarksState.READ,
    val newBookmarksPrivate: Boolean = true,
    val isLoading: Boolean = false,
    val sets: List<BookmarkSetChip> = emptyList(),
    /** The set shown, or null for the main bookmark list. */
    val selectedSet: String? = null
) {
    val selectedSetTitle: String? get() = sets.firstOrNull { it.identifier == selectedSet }?.title
}

/**
 * The user's NIP-51 bookmarks: the main list (kind 10003) and their named bookmark sets (kind
 * 30003), read from their own archive (the login subscription keeps them current) and edited
 * through the shared list-edit path, so bookmarks made in other clients are never dropped.
 *
 * Bookmarks can be private: encrypted to the user by the signer, so relays and other people only
 * see that a list exists. New ones are private unless the user turns that off. Private items are
 * read with a permission the signer already holds; when it would have to ask, the screen offers
 * to unlock them instead of prompting on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val videoCacheDataSourceProvider: VideoCacheDataSourceProvider,
    private val decryptOwnListItems: DecryptOwnListItemsUseCase,
    coordinatorFactory: InteractionActionsCoordinator.Factory
) : ViewModel() {

    private val actions = coordinatorFactory.create(viewModelScope)
    private val me = userPreferences.getPublicKey()?.takeIf { userPreferences.canSignWithAmber() }

    private val mainList: StateFlow<Event?> =
        (if (me == null) emptyFlow() else eventRepository.observeEventsByPubkeyAndKind(me, Event.KIND_BOOKMARK_LIST, 1))
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val sets: StateFlow<List<ListSet>> =
        (if (me == null) emptyFlow() else eventRepository.observeEventsByPubkeyAndKind(me, Event.KIND_BOOKMARK_SET, MAX_SETS))
            .map(::latestListSets)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val selectedSet = MutableStateFlow<String?>(null)

    /** Bumped when the signer opened private items, so lists re-read them from the cache. */
    private val unlocks = MutableStateFlow(0)

    /** Ciphertexts the signer was asked for and couldn't open. */
    private val unreadable = MutableStateFlow<Set<String>>(emptySet())

    /** The main list's bookmarks. Eager: the note menu reads this synchronously to label its action. */
    val bookmarks: StateFlow<List<Bookmark>> = combine(mainList, unlocks) { event, _ -> event }
        .mapLatest { event -> bookmarksOf(event, decryptOwnListItems(event?.content.orEmpty(), interactive = false)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(BookmarksState(newBookmarksPrivate = userPreferences.getPrivateBookmarksFlow().value))
    val state: StateFlow<BookmarksState> = _state.asStateFlow()

    private val _pickerTarget = MutableStateFlow<Event?>(null)

    /** The note the "Add to list" sheet is open for. */
    val pickerTarget: StateFlow<Event?> = _pickerTarget.asStateFlow()

    /** Every bookmark set, marked by whether [pickerTarget] is in it. */
    val pickerChoices: StateFlow<List<BookmarkSetChoice>> = combine(sets, _pickerTarget, unlocks) { sets, target, _ -> sets to target }
        .mapLatest { (sets, target) ->
            if (target == null) {
                emptyList()
            } else {
                sets.map { set ->
                    BookmarkSetChoice(set.identifier, set.title, contains = savedTargetIn(itemsOf(set.event), target) != null)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val userRepositoryPublic: UserRepository get() = userRepository
    val mediaDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()
    val canBookmark: Boolean get() = actions.canSignEvents()

    init {
        viewModelScope.launch {
            combine(mainList, sets, selectedSet, unlocks, unreadable) { main, sets, selected, _, unreadable ->
                val set = selected?.let { id -> sets.firstOrNull { it.identifier == id } }
                Shown(event = set?.event ?: if (selected == null) main else null, unreadable = unreadable)
            }.collectLatest { shown ->
                val content = shown.event?.content.orEmpty()
                val decrypted = decryptOwnListItems(content, interactive = false)
                val privateState = when {
                    decrypted != null -> PrivateBookmarksState.READ
                    content in shown.unreadable -> PrivateBookmarksState.UNREADABLE
                    else -> PrivateBookmarksState.LOCKED
                }
                _state.update { it.copy(privateState = privateState) }
                loadItems(bookmarksOf(shown.event, decrypted))
            }
        }
        viewModelScope.launch {
            var knownIds = emptySet<String>()
            combine(sets, selectedSet) { sets, selected -> sets to selected }.collect { (sets, selected) ->
                val ids = sets.mapTo(HashSet()) { it.identifier }
                // A shown set deleted elsewhere falls back to the main list. One just created
                // isn't known yet, so it stays selected until it arrives.
                if (selected != null && selected in knownIds && selected !in ids) selectedSet.value = null
                knownIds = ids
                _state.update { state ->
                    state.copy(
                        sets = sets.map { BookmarkSetChip(it.identifier, it.title) },
                        selectedSet = selectedSet.value
                    )
                }
            }
        }
        viewModelScope.launch {
            userPreferences.getPrivateBookmarksFlow().collect { private ->
                _state.update { it.copy(newBookmarksPrivate = private) }
            }
        }
    }

    private data class Shown(val event: Event?, val unreadable: Set<String>)

    fun isBookmarked(event: Event): Boolean = savedTargetIn(bookmarks.value, event) != null

    /** The target [event] is saved under in [items], if any — another client may have saved an article by id. */
    private fun savedTargetIn(items: List<Bookmark>, event: Event): BookmarkTarget? {
        val saved = items.mapTo(HashSet()) { it.target }
        return event.savedAs().firstOrNull { it in saved }
    }

    private fun Event.savedAs(): List<BookmarkTarget> =
        listOf(BookmarkTarget.of(this), BookmarkTarget.Note(id.lowercase())).distinct()

    private suspend fun itemsOf(list: Event?): List<Bookmark> =
        bookmarksOf(list, decryptOwnListItems(list?.content.orEmpty(), interactive = false))

    /** Adds [event] to the main list, or removes it when it is already there. */
    fun toggle(event: Event) = toggleIn(event, bookmarks.value, identifier = null)

    /** Adds [event] to the set [identifier], or removes it when it is already there. */
    fun toggleInSet(event: Event, identifier: String) {
        val set = sets.value.firstOrNull { it.identifier == identifier } ?: return
        viewModelScope.launch { toggleIn(event, itemsOf(set.event), identifier) }
    }

    private fun toggleIn(event: Event, items: List<Bookmark>, identifier: String?) {
        if (!actions.canSignEvents()) return
        val saved = savedTargetIn(items, event)
        val remove = saved != null
        val target = saved ?: BookmarkTarget.of(event)
        val privately = !remove && userPreferences.getPrivateBookmarksFlow().value
        actions.requestSignAndPublish(
            buildEventJson = {
                actions.buildListEdit(
                    if (identifier == null) Event.KIND_BOOKMARK_LIST else Event.KIND_BOOKMARK_SET,
                    if (remove) ListEdit(target.tagName, remove = setOf(target.value)) else ListEdit(target.tagName, add = setOf(target.value)),
                    fallbackValues = items.filter { !it.isPrivate && it.target.tagName == target.tagName }
                        .mapTo(HashSet()) { it.target.value },
                    privately = privately,
                    identifier = identifier
                )
            },
            currentUserHex = userPreferences.getPublicKey()
        )
    }

    fun openPicker(event: Event) {
        _pickerTarget.value = event
    }

    fun closePicker() {
        _pickerTarget.value = null
    }

    /** Creates a set titled [title], holding [initial] when given. */
    fun createSet(title: String, initial: Event? = null) {
        val name = title.trim()
        if (name.isEmpty() || !actions.canSignEvents()) return
        val identifier = UUID.randomUUID().toString()
        val target = initial?.let(BookmarkTarget::of)
        val privately = target != null && userPreferences.getPrivateBookmarksFlow().value
        actions.requestSignAndPublish(
            buildEventJson = {
                actions.buildListEdit(
                    Event.KIND_BOOKMARK_SET,
                    if (target == null) ListEdit("e") else ListEdit(target.tagName, add = setOf(target.value)),
                    fallbackValues = emptySet(),
                    privately = privately,
                    identifier = identifier,
                    newTitle = name
                )
            },
            currentUserHex = userPreferences.getPublicKey(),
            onSigned = { if (initial == null) selectedSet.value = identifier }
        )
    }

    fun renameSet(identifier: String, title: String) {
        val name = title.trim()
        if (name.isEmpty() || !actions.canSignEvents()) return
        actions.requestSignAndPublish(
            buildEventJson = {
                actions.buildListEdit(
                    Event.KIND_BOOKMARK_SET,
                    ListEdit("e"),
                    fallbackValues = emptySet(),
                    identifier = identifier,
                    newTitle = name
                )
            },
            currentUserHex = userPreferences.getPublicKey()
        )
    }

    /** Deletes the set with a NIP-09 request for its address. */
    fun deleteSet(identifier: String) {
        val me = userPreferences.getPublicKey() ?: return
        val set = sets.value.firstOrNull { it.identifier == identifier } ?: return
        actions.deleteEvent(set.event, me, onDeleteConfirmed = { selectedSet.value = null })
    }

    fun selectSet(identifier: String?) {
        selectedSet.value = identifier
        _state.update { it.copy(selectedSet = identifier) }
    }

    fun setNewBookmarksPrivate(private: Boolean) = userPreferences.setPrivateBookmarks(private)

    /** Asks the signer to read the shown list's private items — the user's explicit request. */
    fun unlockPrivate() {
        val selected = selectedSet.value
        val event = if (selected == null) mainList.value else sets.value.firstOrNull { it.identifier == selected }?.event
        val content = event?.content.orEmpty()
        viewModelScope.launch {
            if (decryptOwnListItems(content, interactive = true) == null) {
                unreadable.update { it + content }
            } else {
                unlocks.update { it + 1 }
            }
        }
    }

    private suspend fun loadItems(bookmarks: List<Bookmark>) {
        if (bookmarks.isEmpty()) {
            _state.update { it.copy(items = emptyList(), isLoading = false) }
            return
        }
        _state.update { it.copy(isLoading = it.items.isEmpty()) }
        val privacy = bookmarks.associate { it.target to it.isPrivate }
        val noteIds = bookmarks.mapNotNull { (it.target as? BookmarkTarget.Note)?.id }
        val cached = eventRepository.getEventsByIds(noteIds).associateBy { it.id.lowercase() }
        // Anything not cached is looked up over Tor through the pooled event lookup.
        val events = coroutineScope {
            val notes = noteIds.filterNot { it in cached }.take(MAX_FETCH).map { id ->
                async { eventRepository.fetchEventById(id) }
            }
            val addresses = bookmarks.mapNotNull { (it.target as? BookmarkTarget.Address)?.coordinate }.take(MAX_FETCH).map { address ->
                async { eventRepository.fetchAddressableEvent(address.kind, address.pubkey, address.identifier) }
            }
            cached.values + (notes + addresses).awaitAll().filterNotNull()
        }
        val items = events.mapNotNull { event ->
            event.savedAs().firstNotNullOfOrNull { privacy[it] }?.let { BookmarkedEvent(event, it) }
        }.distinctBy { it.event.id }.sortedByDescending { it.event.createdAt }
        val profiles = userRepository.getProfilesByPubkey(items.map { it.event.pubkey.lowercase() }.toSet())
        _state.update { it.copy(items = items, profiles = profiles, isLoading = false) }
    }

    private companion object {
        const val MAX_FETCH = 100
        const val MAX_SETS = 200
    }
}
