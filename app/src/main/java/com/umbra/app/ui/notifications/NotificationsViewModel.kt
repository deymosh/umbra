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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
    val isAnonymous: Boolean = false
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

    private companion object {
        const val MAX_AVATARS = 5
        const val MAX_TARGET_LOOKUPS = 60
    }
}
