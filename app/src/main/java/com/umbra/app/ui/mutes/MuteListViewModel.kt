package com.umbra.app.ui.mutes

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip51.MuteItem
import com.umbra.app.domain.nip51.normalizeHashtag
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.DecryptOwnListItemsUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.ui.common.InteractionActionsCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class MuteListState(
    val items: List<MuteItem> = emptyList(),
    val profiles: Map<String, UserProfile> = emptyMap(),
    /** The list has private items the signer hasn't been asked to open yet. */
    val privateLocked: Boolean = false,
    /** The signer was asked and couldn't open them. */
    val privateUnreadable: Boolean = false,
    val newMutesPrivate: Boolean = true,
    val canEdit: Boolean = false
) {
    fun of(kind: MuteItem.Kind): List<MuteItem> = items.filter { it.kind == kind }
}

/**
 * The user's NIP-51 mute list in full — people, hashtags, words and threads — wherever it was
 * edited. Hashtags and words are added here; people and threads from their own menus. Every
 * entry is removable: what is hidden is the user's call.
 */
@HiltViewModel
class MuteListViewModel @Inject constructor(
    private val muteListRepository: MuteListRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val decryptOwnListItems: DecryptOwnListItemsUseCase,
    private val trackReferencedAuthor: TrackReferencedAuthorUseCase,
    coordinatorFactory: InteractionActionsCoordinator.Factory
) : ViewModel() {

    private val actions = coordinatorFactory.create(viewModelScope)
    private val me = userPreferences.getPublicKey()?.takeIf { it.length == 64 }?.lowercase()
    private val unreadable = MutableStateFlow<Set<String>>(emptySet())

    private val _state = MutableStateFlow(
        MuteListState(newMutesPrivate = userPreferences.getPrivateMutesFlow().value, canEdit = actions.canSignEvents())
    )
    val state: StateFlow<MuteListState> = _state.asStateFlow()

    val userRepositoryPublic: UserRepository get() = userRepository

    init {
        if (me != null) {
            viewModelScope.launch {
                muteListRepository.getMuteList(me).collectLatest { list ->
                    val content = currentContent()
                    _state.update {
                        it.copy(
                            items = list?.items.orEmpty(),
                            privateLocked = list?.privateLocked == true && content !in unreadable.value,
                            privateUnreadable = list?.privateLocked == true && content in unreadable.value
                        )
                    }
                    loadProfiles(list?.items.orEmpty().filter { it.kind == MuteItem.Kind.PERSON }.map { it.value })
                }
            }
        }
        viewModelScope.launch {
            userPreferences.getPrivateMutesFlow().collect { private -> _state.update { it.copy(newMutesPrivate = private) } }
        }
    }

    private suspend fun currentContent(): String =
        me?.let { eventRepository.observeEventsByPubkeyAndKind(it, Event.KIND_MUTED_USERS, 1).first().firstOrNull()?.content }.orEmpty()

    /** Profiles for [pubkeys]; unknown ones are looked up and read again once they've had time to arrive. */
    private suspend fun loadProfiles(pubkeys: List<String>) {
        var profiles = userRepository.getProfilesByPubkey(pubkeys)
        _state.update { it.copy(profiles = profiles) }
        val missing = pubkeys.filterNot { it in profiles }
        if (missing.isEmpty()) return
        missing.forEach { trackReferencedAuthor(it) }
        delay(PROFILE_RETRY_MS)
        profiles = userRepository.getProfilesByPubkey(pubkeys)
        _state.update { it.copy(profiles = profiles) }
    }

    /** Mutes the hashtag [raw] (with or without its `#`). */
    fun muteHashtag(raw: String) {
        val tag = normalizeHashtag(raw) ?: return
        if (_state.value.items.none { it.kind == MuteItem.Kind.HASHTAG && it.value == tag }) {
            actions.editMuteList(MuteItem.Kind.HASHTAG, tag, mute = true)
        }
    }

    /** Mutes the word or phrase [raw]; matched as whole words, ignoring case. */
    fun muteWord(raw: String) {
        val word = raw.trim().lowercase().takeIf { it.isNotEmpty() } ?: return
        if (_state.value.items.none { it.kind == MuteItem.Kind.WORD && it.value == word }) {
            actions.editMuteList(MuteItem.Kind.WORD, word, mute = true)
        }
    }

    fun unmute(item: MuteItem) = actions.editMuteList(item.kind, item.value, mute = false)

    fun setNewMutesPrivate(private: Boolean) = userPreferences.setPrivateMutes(private)

    /** Asks the signer to open the private mutes — the user's explicit request. */
    fun unlockPrivate() {
        viewModelScope.launch {
            val content = currentContent()
            if (decryptOwnListItems(content, interactive = true) == null) {
                unreadable.update { it + content }
                _state.update { it.copy(privateLocked = false, privateUnreadable = true) }
            }
        }
    }

    private companion object {
        const val PROFILE_RETRY_MS = 4_000L
    }
}
