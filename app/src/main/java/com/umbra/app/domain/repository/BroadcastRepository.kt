package com.umbra.app.domain.repository

import com.umbra.app.domain.broadcast.BroadcastEvent
import com.umbra.app.domain.nip01.Event
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks per-relay delivery of events this client publishes — the "tracked broadcasts" feature.
 * Always on: every publish through
 * [com.umbra.app.domain.usecase.PublishSignedEventUseCase] is tracked, with a bounded number of
 * automatic retries for relays that fail or time out (see the impl's MAX_ATTEMPTS).
 */
interface BroadcastRepository {
    /** Snapshot of every broadcast still being tracked, most recent last. */
    val activeBroadcasts: StateFlow<List<BroadcastEvent>>

    /**
     * Starts tracking a publish already sent to [targetRelays] (the same relay set
     * [com.umbra.app.data.repository.EventRepositoryImpl.publishEvent] resolved and published
     * to) — this does not itself publish, only observes the relay OK responses and auto-retries
     * failed/timed-out relays up to the configured attempt limit.
     */
    fun trackPublish(event: Event, targetRelays: Set<String>)

    /** Re-publishes [broadcastId]'s currently-failed/timed-out relays, outside the auto-retry budget. */
    fun retryFailedRelays(broadcastId: String)

    /** Stops tracking [broadcastId] and removes it from [activeBroadcasts]. */
    fun dismiss(broadcastId: String)
}
