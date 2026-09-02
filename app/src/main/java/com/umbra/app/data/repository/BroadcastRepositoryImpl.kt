package com.umbra.app.data.repository

import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.domain.broadcast.BroadcastEvent
import com.umbra.app.domain.broadcast.RelayBroadcastResult
import com.umbra.app.domain.broadcast.RelayBroadcastStatus
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.repository.BroadcastRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Always-on "tracked broadcasts" implementation (see BroadcastRepository's doc comment). One
 * [runAttemptRound] per publish attempt, re-invoking itself for still-failing relays until
 * [MAX_ATTEMPTS] is reached, racing the shared [NostrClient.publishResultFlow] against a timeout
 * so a relay that never answers is treated the same as one that answers with a rejection — both
 * become retry candidates.
 */
@Singleton
class BroadcastRepositoryImpl @Inject constructor(
    private val nostrClient: NostrClient
) : BroadcastRepository {

    companion object {
        // How long the FIRST attempt waits for every targeted relay to answer — generous because
        // Tor round trips are much slower than clearnet (matches other Tor-aware timeouts in this
        // codebase, e.g. LOAD_OLDER_TIMEOUT_MS in FeedViewModel).
        private const val INITIAL_TIMEOUT_MS = 10_000L
        // Retries wait less: a relay that already failed once is less likely to need the full
        // window, and a shorter timeout keeps a multi-retry publish from dragging on for a minute.
        private const val RETRY_TIMEOUT_MS = 7_000L
        private const val RETRY_BACKOFF_MS = 2_000L
        // Total attempts including the first — i.e. up to 2 automatic retries beyond the initial
        // publish, per product decision (kept low: a relay still failing after 3 tries is more
        // likely down/blocking than transiently slow).
        private const val MAX_ATTEMPTS = 3
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeBroadcasts = MutableStateFlow<List<BroadcastEvent>>(emptyList())
    override val activeBroadcasts: StateFlow<List<BroadcastEvent>> = _activeBroadcasts.asStateFlow()

    override fun trackPublish(event: Event, targetRelays: Set<String>) {
        if (targetRelays.isEmpty()) return
        val broadcast = BroadcastEvent(
            id = "${event.id}-${System.currentTimeMillis()}",
            event = event,
            targetRelays = targetRelays.toList(),
            startedAtMs = System.currentTimeMillis()
        )
        _activeBroadcasts.update { it + broadcast }
        // UNDISPATCHED for the same reason runAttemptRound's own inner collectJob is: without it,
        // trackPublish() could return before the coroutine that will collect
        // nostrClient.publishResultFlow has actually started running (a REGULAR launch only
        // schedules it) — publishResultFlow has no replay, so a result answered fast enough to
        // arrive in that gap is silently lost, and this specific relay then just sits PENDING
        // until the full attempt timeout elapses. Rare in production (network I/O is always far
        // slower than a coroutine actually getting scheduled), but was exactly the cause of a real
        // CI test flake under full-suite Dispatchers.Default thread-pool contention.
        repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // The EVENT frame for attempt 1 was already sent by EventRepositoryImpl.publishEvent()
            // — this round only observes the results, it doesn't resend (sendEvent = false).
            runAttemptRound(broadcastId = broadcast.id, event = event, relaysToTry = targetRelays, attempt = 1, sendEvent = false)
        }
    }

    override fun retryFailedRelays(broadcastId: String) {
        val broadcast = _activeBroadcasts.value.firstOrNull { it.id == broadcastId } ?: return
        val failedRelays = broadcast.failedRelayUrls.toSet()
        if (failedRelays.isEmpty()) return
        // Manual retry is outside the auto-retry budget — always run at attempt 1's timeout/labeling
        // so it reads as a fresh try, not "attempt 4" of the automatic sequence. Unlike trackPublish,
        // this DOES resend: these relays already reached a terminal failure/timeout state, so
        // there's no earlier in-flight EVENT frame still worth waiting on.
        // UNDISPATCHED — see trackPublish's matching comment; here it also guarantees the listener
        // is attached before the resent EVENT frame(s) go out below, not just before this function
        // returns.
        repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runAttemptRound(broadcastId = broadcastId, event = broadcast.event, relaysToTry = failedRelays, attempt = 1, sendEvent = true)
        }
    }

    override fun dismiss(broadcastId: String) {
        _activeBroadcasts.update { list -> list.filterNot { it.id == broadcastId } }
    }

    private suspend fun runAttemptRound(
        broadcastId: String,
        event: Event,
        relaysToTry: Set<String>,
        attempt: Int,
        sendEvent: Boolean
    ) {
        val pendingStatus = if (attempt == 1) RelayBroadcastStatus.PENDING else RelayBroadcastStatus.RETRYING
        updateResults(broadcastId) { results ->
            results + relaysToTry.associateWith { RelayBroadcastResult(pendingStatus, attempts = attempt) }
        }

        val remaining = relaysToTry.toMutableSet()
        val allAnswered = CompletableDeferred<Unit>()
        // UNDISPATCHED so the collector is guaranteed attached to the shared flow before the EVENT
        // frame(s) below are sent — otherwise a relay fast enough to answer before the (normally
        // dispatched) coroutine actually starts collecting would be missed. publishResultFlow has
        // no replay buffer, so a late-attaching collector simply never sees it.
        val collectJob = repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            nostrClient.publishResultFlow.collect { result ->
                if (result.eventId != event.id || result.relayUrl !in remaining) return@collect
                remaining.remove(result.relayUrl)
                updateResults(broadcastId) { results ->
                    results + (result.relayUrl to RelayBroadcastResult(
                        status = if (result.accepted) RelayBroadcastStatus.SUCCESS else RelayBroadcastStatus.FAILED,
                        message = result.message.ifBlank { null },
                        attempts = attempt
                    ))
                }
                if (remaining.isEmpty()) allAnswered.complete(Unit)
            }
        }

        if (sendEvent) {
            relaysToTry.forEach { relayUrl -> nostrClient.publishEvent(relayUrl, event) }
        }

        withTimeoutOrNull(if (attempt == 1) INITIAL_TIMEOUT_MS else RETRY_TIMEOUT_MS) { allAnswered.await() }
        collectJob.cancel()

        if (remaining.isNotEmpty()) {
            updateResults(broadcastId) { results ->
                results + remaining.associateWith { RelayBroadcastResult(RelayBroadcastStatus.TIMEOUT, attempts = attempt) }
            }
        }

        if (attempt < MAX_ATTEMPTS) {
            val stillFailing = _activeBroadcasts.value.firstOrNull { it.id == broadcastId }
                ?.failedRelayUrls
                ?.toSet()
                .orEmpty()
            if (stillFailing.isNotEmpty()) {
                delay(RETRY_BACKOFF_MS)
                runAttemptRound(broadcastId, event, stillFailing, attempt + 1, sendEvent = true)
            }
        }
    }

    private fun updateResults(broadcastId: String, transform: (Map<String, RelayBroadcastResult>) -> Map<String, RelayBroadcastResult>) {
        _activeBroadcasts.update { list ->
            list.map { broadcast -> if (broadcast.id == broadcastId) broadcast.copy(results = transform(broadcast.results)) else broadcast }
        }
    }
}
