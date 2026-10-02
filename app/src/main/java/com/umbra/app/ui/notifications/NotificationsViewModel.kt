package com.umbra.app.ui.notifications

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.notifications.NotificationGroup
import com.umbra.app.domain.notifications.NotificationType
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.ObserveNotificationsUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.domain.model.NostrChannels
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NotificationFilter(val types: Set<NotificationType>) {
    ALL(NotificationType.entries.toSet()),
    REPLIES(setOf(NotificationType.REPLY, NotificationType.MENTION)),
    ZAPS(setOf(NotificationType.ZAP)),
    REACTIONS(setOf(NotificationType.REACTION, NotificationType.REPOST))
}

@Immutable
data class NotificationsState(
    val groups: List<NotificationGroup> = emptyList(),
    val profiles: Map<String, UserProfile> = emptyMap(),
    /** Notes that were reacted to / zapped / reposted, for the quoted snippet. */
    val targets: Map<String, Event> = emptyMap(),
    val filter: NotificationFilter = NotificationFilter.ALL,
    /** Rows newer than this were unseen when the screen opened. */
    val seenAt: Long = 0,
    val isLoading: Boolean = true,
    val isAnonymous: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** A page came back with nothing older than what was already loaded. */
    val olderExhausted: Boolean = false
) {
    val visible: List<NotificationGroup> get() = groups.filter { it.type in filter.types }
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    observeNotifications: ObserveNotificationsUseCase,
    private val userPreferences: UserPreferences,
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val trackReferencedAuthor: TrackReferencedAuthorUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(
        NotificationsState(
            // Captured once: rows unseen at open stay highlighted for this visit.
            seenAt = userPreferences.getNotificationsSeenAtFlow().value,
            isAnonymous = !userPreferences.canSignWithAmber()
        )
    )
    val state: StateFlow<NotificationsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeNotifications(userPreferences.getPublicKey().takeIf { userPreferences.canSignWithAmber() })
                .collect { groups ->
                    val actors = groups.asSequence().flatMap { it.actorPubkeys.take(MAX_AVATARS) }.toSet()
                    val profiles = userRepository.getProfilesByPubkey(actors)
                    actors.filterNot { it in profiles }.forEach { trackReferencedAuthor(it) }
                    val known = _state.value.targets
                    val targets = known + groups.mapNotNull { it.targetEventId }
                        .filterNot { it in known }
                        .distinct()
                        .take(MAX_TARGET_LOOKUPS)
                        .mapNotNull { id -> eventRepository.getEventById(id)?.let { id to it } }
                    _state.update { it.copy(groups = groups, profiles = profiles, targets = targets, isLoading = false) }
                    groups.firstOrNull()?.let { userPreferences.markNotificationsSeen(it.latestAt) }
                }
        }
    }

    fun setFilter(filter: NotificationFilter) = _state.update { it.copy(filter = filter) }

    private var requestedOlderAnchor: Long? = null
    private var olderPageJob: Job? = null

    /** Asks the inbox relays for the page of notifications just older than the oldest loaded. */
    fun loadOlder() {
        val current = _state.value
        if (current.isAnonymous || current.isLoading || current.isLoadingMore || current.olderExhausted) return
        val pubkey = userPreferences.getPublicKey() ?: return
        _state.update { it.copy(isLoadingMore = true) }
        olderPageJob?.cancel()
        olderPageJob = viewModelScope.launch {
            val oldest = eventRepository.getOldestInboxNoteTimestamp(pubkey)
            if (oldest == null || oldest == requestedOlderAnchor) {
                // Nothing loaded yet to page from, or the last page for this anchor added nothing.
                _state.update { it.copy(isLoadingMore = false, olderExhausted = oldest != null) }
                return@launch
            }
            requestedOlderAnchor = oldest
            eventRepository.loadOlderEvents(NostrChannels.INBOX_NOTES, oldest)
            delay(OLDER_PAGE_TIMEOUT_MS)
            _state.update { it.copy(isLoadingMore = false) }
        }
    }

    private companion object {
        const val MAX_AVATARS = 5
        const val MAX_TARGET_LOOKUPS = 60
        // Long enough for the page's one retry (EventRepositoryImpl) to finish too.
        const val OLDER_PAGE_TIMEOUT_MS = 32_000L
    }
}
