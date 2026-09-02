package com.umbra.app.ui.broadcast

import androidx.lifecycle.ViewModel
import com.umbra.app.domain.broadcast.BroadcastEvent
import com.umbra.app.domain.usecase.DismissBroadcastUseCase
import com.umbra.app.domain.usecase.ObserveActiveBroadcastsUseCase
import com.umbra.app.domain.usecase.RetryBroadcastRelaysUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Hosts the always-on "tracked broadcasts" banner/sheet — see [com.umbra.app.domain.repository.BroadcastRepository].
 * Instantiated once at the nav-host level (not per-screen) so the banner survives navigation
 * between the screen that triggered a publish and wherever the user goes next.
 */
@HiltViewModel
class BroadcastViewModel @Inject constructor(
    observeActiveBroadcastsUseCase: ObserveActiveBroadcastsUseCase,
    private val retryBroadcastRelaysUseCase: RetryBroadcastRelaysUseCase,
    private val dismissBroadcastUseCase: DismissBroadcastUseCase
) : ViewModel() {
    val activeBroadcasts: StateFlow<List<BroadcastEvent>> = observeActiveBroadcastsUseCase()

    fun retryFailedRelays(broadcastId: String) {
        retryBroadcastRelaysUseCase(broadcastId)
    }

    fun dismiss(broadcastId: String) {
        dismissBroadcastUseCase(broadcastId)
    }
}
