package com.umbra.app.ui.readlater

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.ReadLaterRepository
import com.umbra.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReadLaterViewModel @Inject constructor(
    private val repository: ReadLaterRepository,
    private val userRepository: UserRepository,
    private val videoCacheDataSourceProvider: VideoCacheDataSourceProvider
) : ViewModel() {
    val items: StateFlow<List<Event>> = repository.items

    /** Eager: the note menu reads this synchronously to label its action. */
    val savedIds: StateFlow<Set<String>> = repository.items
        .map { list -> list.mapTo(HashSet()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _profiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, UserProfile>> = _profiles.asStateFlow()

    val userRepositoryPublic: UserRepository get() = userRepository
    val mediaDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()

    init {
        viewModelScope.launch {
            repository.items.collect { list ->
                _profiles.value = userRepository.getProfilesByPubkey(list.map { it.pubkey.lowercase() }.toSet())
            }
        }
    }

    fun toggle(event: Event) = repository.toggle(event)

    fun remove(eventId: String) = repository.remove(eventId)
}
