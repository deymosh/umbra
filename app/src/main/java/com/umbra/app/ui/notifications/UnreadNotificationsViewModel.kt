package com.umbra.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.usecase.ObserveNotificationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Just the feed bell's unread dot, kept out of FeedViewModel. */
@HiltViewModel
class UnreadNotificationsViewModel @Inject constructor(
    observeNotifications: ObserveNotificationsUseCase,
    userPreferences: UserPreferences
) : ViewModel() {
    val hasUnread: StateFlow<Boolean> = combine(
        observeNotifications(userPreferences.getPublicKey().takeIf { userPreferences.canSignWithAmber() }),
        userPreferences.getNotificationsSeenAtFlow()
    ) { groups, seenAt -> (groups.firstOrNull()?.latestAt ?: 0) > seenAt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
