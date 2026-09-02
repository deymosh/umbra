package com.umbra.app.domain.usecase

import com.umbra.app.domain.broadcast.BroadcastEvent
import com.umbra.app.domain.repository.BroadcastRepository
import kotlinx.coroutines.flow.StateFlow

/** Exposes the always-on broadcast tracker's live state — see [BroadcastRepository]. */
class ObserveActiveBroadcastsUseCase(
    private val broadcastRepository: BroadcastRepository
) {
    operator fun invoke(): StateFlow<List<BroadcastEvent>> = broadcastRepository.activeBroadcasts
}

/** Manually re-publishes a broadcast's currently-failed/timed-out relays. */
class RetryBroadcastRelaysUseCase(
    private val broadcastRepository: BroadcastRepository
) {
    operator fun invoke(broadcastId: String) {
        broadcastRepository.retryFailedRelays(broadcastId)
    }
}

/** Removes a broadcast from the tracker (banner/sheet dismissal). */
class DismissBroadcastUseCase(
    private val broadcastRepository: BroadcastRepository
) {
    operator fun invoke(broadcastId: String) {
        broadcastRepository.dismiss(broadcastId)
    }
}
