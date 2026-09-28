package com.umbra.app.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip51.ListEdit
import com.umbra.app.domain.nip51.extractBookmarkList
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.common.InteractionActionsCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The user's public NIP-51 bookmark list (kind 10003), read from their own archive (the login
 * subscription keeps it current) and edited through the shared list-edit path, so bookmarks
 * made in other clients — including bookmarked articles and hashtags — are never dropped.
 */
@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val videoCacheDataSourceProvider: VideoCacheDataSourceProvider,
    coordinatorFactory: InteractionActionsCoordinator.Factory
) : ViewModel() {

    private val actions = coordinatorFactory.create(viewModelScope)

    /** Eager: the note menu reads this synchronously to label its action. */
    val bookmarkedIds: StateFlow<Set<String>> = run {
        val me = userPreferences.getPublicKey()?.takeIf { userPreferences.canSignWithAmber() }
        val source = if (me == null) emptyFlow() else eventRepository.observeEventsByPubkeyAndKind(me, Event.KIND_BOOKMARK_LIST, 1)
        source.map { events -> events.firstOrNull()?.let(::extractBookmarkList)?.noteIds.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    }

    private val _notes = MutableStateFlow<List<Event>>(emptyList())
    val notes: StateFlow<List<Event>> = _notes.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, UserProfile>> = _profiles.asStateFlow()

    val userRepositoryPublic: UserRepository get() = userRepository
    val mediaDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()
    val canBookmark: Boolean get() = actions.canSignEvents()

    init {
        viewModelScope.launch {
            bookmarkedIds.collectLatest { ids ->
                if (ids.isEmpty()) {
                    _notes.value = emptyList()
                    return@collectLatest
                }
                val cached = eventRepository.getEventsByIds(ids.toList()).associateBy { it.id }
                // Anything not cached is looked up over Tor through the pooled event lookup.
                val fetched = ids.filterNot { it in cached }.take(MAX_FETCH).map { id ->
                    async { eventRepository.fetchEventById(id) }
                }.awaitAll().filterNotNull()
                val notes = (cached.values + fetched).sortedByDescending { it.createdAt }
                _notes.value = notes
                _profiles.value = userRepository.getProfilesByPubkey(notes.map { it.pubkey.lowercase() }.toSet())
            }
        }
    }

    fun toggle(event: Event) {
        if (!actions.canSignEvents()) return
        val remove = event.id in bookmarkedIds.value
        actions.requestSignAndPublish(
            buildEventJson = {
                actions.buildListEdit(
                    Event.KIND_BOOKMARK_LIST,
                    if (remove) ListEdit("e", remove = setOf(event.id)) else ListEdit("e", add = setOf(event.id)),
                    fallbackValues = bookmarkedIds.value
                )
            },
            currentUserHex = userPreferences.getPublicKey()
        )
    }

    private companion object {
        const val MAX_FETCH = 100
    }
}
