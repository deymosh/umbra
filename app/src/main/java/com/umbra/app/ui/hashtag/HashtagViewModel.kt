package com.umbra.app.ui.hashtag

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.ui.common.InteractionActionsCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Immutable
data class HashtagState(
    val tag: String,
    val notes: List<Event> = emptyList(),
    val profiles: Map<String, UserProfile> = emptyMap(),
    val isLoading: Boolean = true
)

/**
 * Notes tagged `#tag`, fetched with a dedicated `#t` subscription and read back from the event
 * cache — so the user's own feed filters (excluded hashtags, NSFW) still decide what is kept, and
 * their mute list is applied on top.
 */
@HiltViewModel
class HashtagViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    muteListRepository: MuteListRepository,
    private val trackReferencedAuthor: TrackReferencedAuthorUseCase,
    private val videoCacheDataSourceProvider: VideoCacheDataSourceProvider,
    coordinatorFactory: InteractionActionsCoordinator.Factory
) : ViewModel() {

    private val tag: String = checkNotNull(savedStateHandle.get<String>("tag")).removePrefix("#").lowercase()
    private val channelId = NostrChannels.hashtag(tag)
    private val actions = coordinatorFactory.create(viewModelScope)

    private val _state = MutableStateFlow(HashtagState(tag = tag))
    val state: StateFlow<HashtagState> = _state.asStateFlow()

    private val _shareUrl = MutableSharedFlow<String>()
    val shareUrl: SharedFlow<String> = _shareUrl.asSharedFlow()

    val userRepositoryPublic: UserRepository get() = userRepository
    val mediaDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()

    init {
        eventRepository.subscribeChannel(
            channelId,
            listOf(EventFilter(kinds = setOf(Event.KIND_TEXT_NOTE), tagFilters = mapOf("t" to setOf(tag)), limit = FETCH_LIMIT))
        )
        val me = userPreferences.getPublicKey()
        val mutes = if (me.isNullOrBlank()) flowOf(emptySet()) else muteListRepository.getMuteList(me)
            .map { list -> list?.mutedPubkeys.orEmpty().map(String::lowercase).toSet() }
        viewModelScope.launch {
            combine(eventRepository.observeEventsWithTag("t", tag, setOf(Event.KIND_TEXT_NOTE)), mutes) { notes, muted ->
                notes.filterNot { it.pubkey.lowercase() in muted || it.isFromFuture() }
            }.collect { notes ->
                val authors = notes.map { it.pubkey.lowercase() }.toSet()
                val profiles = userRepository.getProfilesByPubkey(authors)
                authors.filterNot { it in profiles }.forEach { trackReferencedAuthor(it) }
                _state.update { it.copy(notes = notes, profiles = profiles, isLoading = false) }
            }
        }
    }

    fun currentUserPubkey(): String? = userPreferences.getPublicKey()

    fun like(event: Event, content: String, emoji: CustomEmoji?): Boolean {
        if (!actions.canSignEvents()) return false
        actions.requestSignAndPublish(
            NostrEventBuilder.reaction(event, content, emoji, relayHint(event.id)),
            userPreferences.getPublicKey()
        )
        return true
    }

    fun repost(event: Event) {
        if (!actions.canSignEvents()) return
        actions.requestSignAndPublish(
            NostrEventBuilder.repost(event, relayHint(event.id)),
            userPreferences.getPublicKey()
        )
    }

    /**
     * Relay hint for reaction/repost tag relay slots: the first relay the target was seen on;
     * empty when unknown. Blocking is fine here — the backing lookup is an in-memory map hit.
     */
    private fun relayHint(eventId: String): String = runBlocking {
        eventRepository.getEventRelays(eventId).firstOrNull() ?: ""
    }

    fun share(event: Event) {
        viewModelScope.launch { _shareUrl.emit(actions.buildShareUrl(event.id)) }
    }

    fun eventJson(event: Event): String = actions.getEventJson(event)

    override fun onCleared() {
        eventRepository.clearChannel(channelId)
    }

    private companion object {
        const val FETCH_LIMIT = 200
    }
}
