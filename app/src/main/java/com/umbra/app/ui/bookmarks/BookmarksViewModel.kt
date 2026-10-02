package com.umbra.app.ui.bookmarks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip51.Bookmark
import com.umbra.app.domain.nip51.BookmarkTarget
import com.umbra.app.domain.nip51.ListEdit
import com.umbra.app.domain.nip51.bookmarksOf
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.DecryptOwnListItemsUseCase
import com.umbra.app.ui.common.InteractionActionsCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A bookmarked event as the Bookmarks screen shows it. */
@Immutable
data class BookmarkedEvent(val event: Event, val isPrivate: Boolean)

/** How much of the list's private part Umbra could read. */
enum class PrivateBookmarksState {
    /** Nothing private, or all of it is shown. */
    READ,
    /** The signer hasn't been asked yet; the user can unlock them. */
    LOCKED,
    /** The signer refused, or they were saved in a format it can't read here. */
    UNREADABLE
}

@Immutable
data class BookmarksState(
    val items: List<BookmarkedEvent> = emptyList(),
    val profiles: Map<String, UserProfile> = emptyMap(),
    val privateState: PrivateBookmarksState = PrivateBookmarksState.READ,
    val newBookmarksPrivate: Boolean = true,
    val isLoading: Boolean = false
)

/**
 * The user's NIP-51 bookmark list (kind 10003), read from their own archive (the login
 * subscription keeps it current) and edited through the shared list-edit path, so bookmarks made
 * in other clients are never dropped.
 *
 * Bookmarks can be private: encrypted to the user by the signer, so relays and other people only
 * see that a list exists. New ones are private unless the user turns that off. Private items are
 * read with a permission the signer already holds; when it would have to ask, the screen offers
 * to unlock them instead of prompting on its own.
 */
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

    private val listEvent: StateFlow<Event?> = run {
        val me = userPreferences.getPublicKey()?.takeIf { userPreferences.canSignWithAmber() }
        val source = if (me == null) emptyFlow() else eventRepository.observeEventsByPubkeyAndKind(me, Event.KIND_BOOKMARK_LIST, 1)
        source.map { it.firstOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    /** The decrypted private items of the current list, or null while they can't be read. */
    private val privateTags = MutableStateFlow<List<List<String>>?>(emptyList())

    /** Eager: the note menu reads this synchronously to label its action. */
    val bookmarks: StateFlow<List<Bookmark>> = combine(listEvent, privateTags) { event, private ->
        bookmarksOf(event, private)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(BookmarksState(newBookmarksPrivate = userPreferences.getPrivateBookmarksFlow().value))
    val state: StateFlow<BookmarksState> = _state.asStateFlow()

    val userRepositoryPublic: UserRepository get() = userRepository
    val mediaDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()
    val canBookmark: Boolean get() = actions.canSignEvents()

    init {
        viewModelScope.launch {
            listEvent.collectLatest { event ->
                val content = event?.content.orEmpty()
                val decrypted = decryptOwnListItems(content, interactive = false)
                privateTags.value = decrypted
                setPrivateState(if (decrypted == null) PrivateBookmarksState.LOCKED else PrivateBookmarksState.READ)
            }
        }
        viewModelScope.launch {
            bookmarks.collectLatest(::loadItems)
        }
        viewModelScope.launch {
            userPreferences.getPrivateBookmarksFlow().collect { private ->
                _state.update { it.copy(newBookmarksPrivate = private) }
            }
        }
    }

    fun isBookmarked(event: Event): Boolean = savedTarget(event) != null

    /** The target [event] is saved under, if any — another client may have saved an article by id. */
    private fun savedTarget(event: Event): BookmarkTarget? {
        val saved = bookmarks.value.mapTo(HashSet()) { it.target }
        return event.savedAs().firstOrNull { it in saved }
    }

    private fun Event.savedAs(): List<BookmarkTarget> =
        listOf(BookmarkTarget.of(this), BookmarkTarget.Note(id.lowercase())).distinct()

    fun toggle(event: Event) {
        if (!actions.canSignEvents()) return
        val saved = savedTarget(event)
        val remove = saved != null
        val target = saved ?: BookmarkTarget.of(event)
        val privately = !remove && userPreferences.getPrivateBookmarksFlow().value
        actions.requestSignAndPublish(
            buildEventJson = {
                actions.buildListEdit(
                    Event.KIND_BOOKMARK_LIST,
                    if (remove) ListEdit(target.tagName, remove = setOf(target.value)) else ListEdit(target.tagName, add = setOf(target.value)),
                    fallbackValues = bookmarks.value.filter { !it.isPrivate && it.target.tagName == target.tagName }
                        .mapTo(HashSet()) { it.target.value },
                    privately = privately
                )
            },
            currentUserHex = userPreferences.getPublicKey()
        )
    }

    fun setNewBookmarksPrivate(private: Boolean) = userPreferences.setPrivateBookmarks(private)

    /** Asks the signer to read the private bookmarks — the user's explicit request. */
    fun unlockPrivate() {
        val content = listEvent.value?.content.orEmpty()
        viewModelScope.launch {
            val decrypted = decryptOwnListItems(content, interactive = true)
            if (decrypted != null) privateTags.value = decrypted
            setPrivateState(if (decrypted == null) PrivateBookmarksState.UNREADABLE else PrivateBookmarksState.READ)
        }
    }

    private fun setPrivateState(privateState: PrivateBookmarksState) =
        _state.update { it.copy(privateState = privateState) }

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
    }
}
