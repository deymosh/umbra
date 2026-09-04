@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.umbra.app.data.nostr

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.feed.mergeActiveFeedFilters
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nostr.NostrSessionController
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.data.tor.TorRuntimeManager
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.RelayInfoRepository
import com.umbra.app.data.repository.RelayListDecryptionCoordinator
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.BootstrapOwnProfileUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.umbra.app.util.coroutines.runCatchingCancellable
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level Nostr bootstrap that keeps baseline subscriptions alive for the whole process.
 */
@Singleton
class NostrSessionManager @Inject constructor(
    private val eventRepository: EventRepository,
    private val relayRepository: RelayRepository,
    private val feedRepository: FeedRepository,
    private val userRepository: UserRepository,
    private val contactListRepository: ContactListRepository,
    private val userPreferences: UserPreferences,
    private val torRuntimeManager: TorRuntimeManager,
    private val relayInfoRepository: RelayInfoRepository,
    private val backfillAnchorStore: BackfillAnchorStore,
    private val bootstrapOwnProfileUseCase: BootstrapOwnProfileUseCase,
    private val relayListDecryptionCoordinator: RelayListDecryptionCoordinator
) : NostrSessionController {
    companion object {
        private const val TAG = "UmbraNostrSession"
        private const val RETRY_DELAY_MS = 8_000L
        private const val BACKFILL_MIN_WINDOW_SECS = 12 * 60 * 60L
        private const val BACKFILL_START_WINDOW_SECS = 3 * 24 * 60 * 60L
        private const val BACKFILL_MAX_WINDOW_SECS = 10L * 365 * 24 * 60 * 60L
        private const val BACKFILL_MIN_LIMIT = 120
        private const val BACKFILL_MAX_LIMIT = 600
        private const val BACKFILL_STABILIZE_DELAY_MS = 1_200L
        private const val BACKFILL_IDLE_WINDOW_THRESHOLD = 3
        private const val BACKFILL_ACTIVE_CADENCE_MS = 12_000L
        private const val BACKFILL_IDLE_CADENCE_MS = 90_000L
        private const val BACKFILL_DEEP_HISTORY_CADENCE_MS = 15 * 60_000L
        // UserRepositoryImpl.saveRelayList() now adds a tracked author's new outbox relays to the
        // pool the moment each kind:10002 arrives, rather than in small periodic batches — so
        // during a hydration burst (many tracked authors' relay lists landing within seconds of
        // each other), relayRepository.getAllRelays() can emit many times in quick succession.
        // Without debouncing, every single one of those emissions changed the relay-set signature
        // and re-triggered a full reconcile(): reapplying every channel's REQ to every already-
        // connected relay (see connectToEnabledRelays's per-relay reapply loop) — expensive, and
        // some relay implementations respond to a same-subId REQ resent this often with a
        // "duplicate subscription" CLOSE instead of the NIP-01-mandated silent replace. Coalescing
        // a burst into one reconcile fixes both without changing steady-state connect behavior.
        private const val RELAY_SET_DEBOUNCE_MS = 3_000L

        // Every relay-set-change reconcile resubmits NIP-11 refresh requests for the *entire*
        // current enabled relay list (TTL-gated inside fetchAndPersist, but still a real launch +
        // Room read per relay to find that out) — with the discovered-relay pool now able to
        // reach hundreds, cold start used to fire that many concurrent Tor network round-trips at
        // once, each completing independently and writing its result to the same `relays` table
        // RelayConfigViewModel observes. Bounding concurrency doesn't change which relays get
        // refreshed or the TTL gating, just how many are in flight at once — smooths out the
        // resulting write burst (and the relay-list-flow re-emissions it causes) instead of
        // firing them all simultaneously.
        private const val MAX_CONCURRENT_NIP11_FETCHES = 8

        // How often maybeBootstrapOwnProfile's watcher re-checks whether the real kind:10002 has
        // arrived yet. Cheap (a local getRelayList() read, no network), so a short interval costs
        // nothing — this just bounds how quickly the bootstrap channel gets torn down once it's no
        // longer needed.
        private const val OWN_PROFILE_BOOTSTRAP_POLL_MS = 5_000L
        // Safety ceiling: if the user's own kind:10002 genuinely can't be found anywhere in the
        // discovered/bootstrap pool within this long, stop asking rather than keeping the channel
        // (and the REQ it sends to every newly-connecting relay — see
        // BootstrapOwnProfileUseCase's doc comment) registered forever.
        private const val OWN_PROFILE_BOOTSTRAP_MAX_MS = 3 * 60_000L

        /**
         * URL + role signature for a relay set — the single definition both
         * [OrchestratorSnapshot.signature] (gates whether [reconcile] runs at all) and
         * [reconcile]'s own `relaysChanged` check reuse, so the two can't drift apart again the
         * way they did when `relaysChanged` compared URLs only: a relay whose isReadActive/
         * isWriteActive flipped without its URL changing (e.g. a bootstrap default that's also in
         * the user's real NIP-65 list, just with different roles) passed the URL-only check as
         * "unchanged," so connectToEnabledRelays() — the only place that refreshes
         * EventRepositoryImpl's per-relay role cache — never ran for it.
         */
        fun relaySetSignature(relays: List<Relay>): String =
            relays
                .sortedBy { it.id }
                .joinToString("|") { "${it.id}:${it.url}:${it.isEnabled}:${it.isReadActive}:${it.isWriteActive}" }

        /**
         * Narrower than [relaySetSignature] — scoped to relays that actually carry an active
         * read/write role for the signed-in user, excluding isDiscovered-only rows. Used only to
         * gate [startUserHistoryBackfill]'s restart/resync decision in [reconcile]: the full relay
         * table (what [relaySetSignature] hashes) grows an isDiscovered=true row every time ANY
         * tracked author's — not just the signed-in user's — kind:10002 arrives (see
         * UserRepositoryImpl.addDiscoveredRelays), which has nothing to do with the user's own
         * outbox/inbox subscriptions. Gating the backfill restart on the broad signature meant that
         * noise alone cancelled the running backfill job (discarding its window/limit pacing) and
         * fired a redundant resyncRecentHistory([anchor, now)) resync, even though the relays the
         * user's own subscriptions actually run against hadn't changed.
         */
        fun activeRelaySetSignature(relays: List<Relay>): String =
            relays
                .filter { it.isEnabled && (it.isReadActive || it.isWriteActive) }
                .sortedBy { it.id }
                .joinToString("|") { "${it.id}:${it.url}:${it.isReadActive}:${it.isWriteActive}" }
    }

    private data class BackfillAnchors(
        val outbox: Long,
        val inbox: Long
    )

    private val logger = UmbraLog.tag(TAG)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nip11FetchSemaphore = Semaphore(MAX_CONCURRENT_NIP11_FETCHES)
    // bootstrapJob, autoDisableRelayJob, and torCircuitRecoveryJob are written only by start()
    // and stop() — the volatile started flag above serializes calls to that pair, and neither
    // field is ever read or reassigned from reconcile()'s concurrently-reachable paths (the
    // combine()-driven collect and retryJob's own delayed relaunch). An atomic holder would add
    // no guarantee here, and it could not make the multi-field start()/stop() sequence atomic as
    // a whole anyway — so these three stay plain nullable fields.
    private var bootstrapJob: Job? = null
    private var autoDisableRelayJob: Job? = null
    private var torCircuitRecoveryJob: Job? = null
    private val retryJob = AtomicReference<Job?>(null)
    private var appStartMs: Long = 0L
    // The five fields below are all read and written from reconcile()'s two genuinely-concurrent
    // entry points on this class's own multi-threaded scope (Dispatchers.IO, not confined to one
    // thread): the bootstrapJob's combine()-driven collect loop, and scheduleRetry()'s own delayed
    // relaunch (see retryJob above and reconcile()'s doc comment). @Volatile guarantees the write
    // from one of those threads is visible to a read on the other — it does not make a
    // read-check-write sequence atomic, but every actual mutation here is a single unconditional
    // assignment (never a read-modify-write of its own prior value), so visibility is the only gap
    // that existed. stop() also writes several of these from whatever thread calls it; @Volatile
    // covers that cross-thread visibility too.
    @Volatile
    private var firstRelayConnectedLogged = false
    @Volatile
    private var relaysConnected = false
    @Volatile
    private var lastSnapshot: OrchestratorSnapshot? = null
    private val userBackfillJob = AtomicReference<Job?>(null)
    @Volatile
    private var backfillPubkey: String? = null
    // Pubkey the own-profile bootstrap channel (BootstrapOwnProfileUseCase) is currently open
    // for — null when no bootstrap is active. Keyed by pubkey (not a plain boolean) so a session
    // that switches identity without a full stop()/start() cycle still bootstraps the new one.
    @Volatile
    private var ownProfileBootstrapPubkey: String? = null
    private val ownProfileBootstrapWatcherJob = AtomicReference<Job?>(null)
    // Guards maybeBootstrapOwnProfile's own compound check-then-act sequence
    // (ownProfileBootstrapPubkey != pubkey -> stop -> assign -> start) — @Volatile on
    // ownProfileBootstrapPubkey alone only makes each individual read/write visible across
    // threads, it does not make that whole sequence exclusive. Without this, two overlapping
    // reconcile() calls (this class's own two documented concurrent entry points) for the same
    // pubkey can both observe the stale value and both call bootstrapOwnProfileUseCase.start(),
    // a duplicate channel start.
    private val ownProfileBootstrapMutex = Mutex()

    @Volatile
    private var started = false

    override fun start() {
        if (started) return
        started = true
        appStartMs = System.currentTimeMillis()
        firstRelayConnectedLogged = false

        torRuntimeManager.start()
        // Session-lifetime, not tied to any particular screen — see its own doc comment for why
        // that matters (search/index relay-list decryption used to silently never run at all
        // unless the user had opened Relay Settings).
        relayListDecryptionCoordinator.start(scope)

        bootstrapJob?.cancel()

        // A relay that's failed to connect MAX_CONSECUTIVE_FAILURES_BEFORE_AUTO_DISABLE times in
        // a row (see UmbraNostrClient.recordFailureAndScheduleReconnect) emits AUTO_DISABLED once
        // — this is the one place with both EventRepository (to observe it) and RelayRepository
        // (to actually persist isEnabled=false) available to react to it.
        autoDisableRelayJob?.cancel()
        autoDisableRelayJob = scope.launch {
            eventRepository.observeRelayIssues()
                .filter { it.kind == RelayIssueKind.AUTO_DISABLED }
                .collect { issue -> disableDeadRelay(issue.relayUrl) }
        }

        // A relay just connecting again after a confirmed TOR_CIRCUITS_LIKELY_DEAD episode is
        // direct proof the shared Tor transport works again (see
        // UmbraNostrClient.onWebSocketOpen/TorCircuitHealthTracker) — forgive every relay's
        // accumulated backoff and immediately retry the ones that still need it, instead of each
        // independently waiting out its own (possibly still long) window to rediscover the same
        // thing.
        torCircuitRecoveryJob?.cancel()
        torCircuitRecoveryJob = scope.launch {
            eventRepository.observeRelayIssues()
                .filter { it.kind == RelayIssueKind.TOR_CIRCUITS_RECOVERED }
                .collect {
                    eventRepository.resetAllRelayBackoff()
                    lastSnapshot?.relays?.let { relays -> eventRepository.connectToEnabledRelays(relays) }
                }
        }

        bootstrapJob = scope.launch {
            combine(
                relayRepository.getAllRelays(),
                feedRepository.getActiveFilters(),
                torRuntimeManager.state,
                userPreferences.getPublicKeyFlow()
            ) { relays, activeFilters, torState, pubkey ->
                // Mirrors FeedViewModel.configureReqChannels: merge ALL active filters (not just
                // the first) — this class used to take feedRepository.getActiveFilters()'s
                // firstOrNull() and never pass authors at all, so any reconcile() firing after
                // FeedViewModel's own (correct) activateUserSession() call would silently widen a
                // scoped feed back to unscoped, or drop a second active filter's excludes/mutes.
                //
                // authors is a plain suspend lookup rather than its own combined Flow: wiring a
                // reactive contact-list Flow in here previously added a second hydration-driven
                // re-emission source feeding straight into RELAY_SET_DEBOUNCE_MS below, which
                // reset that debounce's timer on every contact-list load — delaying first relay
                // connect (and therefore maybeBootstrapOwnProfile) for every session, including
                // the common case (DefaultFeedFilters.DEFAULT) where scopeToFollows is false and
                // authors is never even used. getCurrentFollowedPubkeys() only pays a real lookup
                // cost when a scoped filter is actually active, and doesn't re-trigger this combine.
                val mergedFilter = mergeActiveFeedFilters(activeFilters)
                val authors = if (mergedFilter.scopeToFollows) {
                    contactListRepository.getCurrentFollowedPubkeys()
                } else {
                    emptySet()
                }
                OrchestratorSnapshot(
                    relays = relays,
                    feedFilter = mergedFilter,
                    authors = authors,
                    pubkey = pubkey,
                    torReady = torState.ready,
                    networkAvailable = torState.networkAvailable,
                    torStatus = torState.status.name
                )
            }
                .debounce(RELAY_SET_DEBOUNCE_MS)
                .distinctUntilChanged { old, new -> old.signature() == new.signature() }
                .collect { state ->
                    val previous = lastSnapshot
                    reconcile(state, previous)
                    lastSnapshot = state
                }
        }

        bootstrapJob?.invokeOnCompletion {
            retryJob.getAndSet(null)?.cancel()
            userBackfillJob.getAndSet(null)?.cancel()
            relaysConnected = false
            logger.d { "Bootstrap job completed/cancelled" }
        }
    }

    override fun stop() {
        started = false
        retryJob.getAndSet(null)?.cancel()
        bootstrapJob?.cancel()
        userBackfillJob.getAndSet(null)?.cancel()
        autoDisableRelayJob?.cancel()
        torCircuitRecoveryJob?.cancel()
        bootstrapJob = null
        autoDisableRelayJob = null
        torCircuitRecoveryJob = null
        backfillPubkey = null
        relaysConnected = false
        stopOwnProfileBootstrap()
        relayListDecryptionCoordinator.stop()
        torRuntimeManager.stop()
        eventRepository.disconnectFromAll()
    }

    /**
     * Flips [relayUrl]'s isEnabled to false — only that flag, not its read/write/DM role flags,
     * so re-enabling it (RelayConfigViewModel, which also resets the failure count) restores
     * whatever role configuration it had rather than making the user reconfigure it from scratch.
     * No-ops if the relay was already disabled (e.g. a duplicate/racing AUTO_DISABLED signal) or
     * no longer exists.
     */
    private suspend fun disableDeadRelay(relayUrl: String) {
        val normalizedUrl = normalizeRelayUrl(relayUrl)
        val relay = relayRepository.getAllRelays().first()
            .firstOrNull { normalizeRelayUrl(it.url) == normalizedUrl }
            ?: return
        if (!relay.isEnabled) return
        runCatchingCancellable { relayRepository.updateRelay(relay.copy(isEnabled = false)) }
            .onSuccess {
                eventRepository.disconnectRelay(relay.url)
            }
            .onFailure { e ->
                logger.d { "Failed to auto-disable relay: ${scrubThrowableMessageForLogs(e)}" }
            }
    }

    private suspend fun reconcile(state: OrchestratorSnapshot, previousState: OrchestratorSnapshot?) {
        if (!state.networkAvailable || !state.torReady) {
            if (relaysConnected) {
                logger.d { "Tor/network not ready (${state.torStatus}); disconnecting relays" }
                eventRepository.disconnectFromAll()
                relaysConnected = false
            } else {
                logger.d { "Waiting for Tor readiness: status=${state.torStatus}, network=${state.networkAvailable}" }
            }
            retryJob.getAndSet(null)?.cancel()
            userBackfillJob.getAndSet(null)?.cancel()
            backfillPubkey = null
            return
        }

        eventRepository.activateUserSession(state.pubkey, state.feedFilter, state.authors)
        // Same relay+role signature OrchestratorSnapshot.signature() uses for its own
        // distinctUntilChanged gating above (see relaySetSignature) — comparing URLs alone missed
        // a relay whose isReadActive/isWriteActive flipped without its URL set changing (e.g. a
        // bootstrap default relay that's also in the user's real NIP-65 list, just with different
        // roles), which meant connectToEnabledRelays() — the only place that refreshes
        // EventRepositoryImpl's per-relay role cache — silently never ran for that relay.
        val previousRelaysSignature = previousState?.relays?.let(::relaySetSignature) ?: ""
        val currentRelaysSignature = relaySetSignature(state.relays)
        val relaysChanged = previousRelaysSignature != currentRelaysSignature
        val shouldReconnect = SessionReconnectPolicy.shouldReconnect(
            relaysConnected = relaysConnected,
            relaysChanged = relaysChanged
        )

        // Deliberately narrower than relaysChanged above — see activeRelaySetSignature's doc
        // comment. Only a change to the user's own active relay set should restart the backfill
        // job/fire a resync; reconnecting to a newly discovered relay (relaysChanged/shouldReconnect
        // above) is a separate, harmless concern that stays gated on the broad signature.
        val previousActiveRelaysSignature = previousState?.relays?.let(::activeRelaySetSignature) ?: ""
        val currentActiveRelaysSignature = activeRelaySetSignature(state.relays)
        val activeRelaysChanged = previousActiveRelaysSignature != currentActiveRelaysSignature

        if (!shouldReconnect) {
            retryJob.getAndSet(null)?.cancel()
            startUserHistoryBackfill(state.pubkey)
            return
        }

        val result = eventRepository.connectToEnabledRelays(state.relays)
        result.onSuccess {
            relaysConnected = true
            retryJob.getAndSet(null)?.cancel()

            // isDiscovered included alongside isReadActive/isWriteActive for the same reason as
            // FeedViewModel's relayCount — a discovered/bootstrap relay (the only kind that
            // exists before the user has any kind:10002) never carries a real active role.
            if (!firstRelayConnectedLogged && state.relays.any { it.isEnabled && (it.isReadActive || it.isWriteActive || it.isDiscovered) }) {
                firstRelayConnectedLogged = true
                val elapsed = System.currentTimeMillis() - appStartMs
                logger.d { "First relay connected over Tor in ${elapsed}ms" }
            }

            maybeBootstrapOwnProfile(state.pubkey)

            // Refresh stale NIP-11 info in the background (TTL-gated, non-blocking, bounded
            // concurrency — see MAX_CONCURRENT_NIP11_FETCHES)
            state.relays
                .filter { it.isEnabled }
                .forEach { relay ->
                    scope.launch {
                        nip11FetchSemaphore.withPermit {
                            relayInfoRepository.fetchAndPersist(relay.url, force = false)
                        }
                    }
                }

            // If the user's own active relay set changed, we need to restart the user history
            // backfill to ensure all channels are properly loaded on the new relays. If the pubkey
            // is null/invalid, the backfill will be a no-op and the job will be cancelled, so it's
            // safe to call on every connect. Gated on activeRelaysChanged, not relaysChanged — see
            // activeRelaySetSignature's doc comment for why the broad signature is wrong here.
            if (activeRelaysChanged) {
                userBackfillJob.getAndSet(null)?.cancel()
                logger.d { "Active relay set changed, restarting user backfill from now" }
            }
            startUserHistoryBackfill(state.pubkey, resyncFromNow = activeRelaysChanged)
        }.onFailure { error ->
            relaysConnected = false
            logger.d { "Relay connect failed (${state.torStatus}) -> scheduling retry: ${scrubThrowableMessageForLogs(error)}" }
            scheduleRetry()
        }
    }

    /**
     * Opens [BootstrapOwnProfileUseCase]'s channel for [pubkey] — only when that pubkey doesn't
     * already have a known outbox relay list, i.e. only for the cold-start case OUTBOX_PROFILE's
     * own isWriteActive gating can't reach yet (see EventRepositoryImpl.canApplyChannelToRelay's
     * doc comment). Kept open (not torn down after one EOSE/short timeout) so every relay that
     * connects later — Tor connections trickle in over many seconds, especially for a large
     * discovered pool on a cold start — also gets asked, via EventRepositoryImpl's existing
     * relayOpenedFlow reapply. A lightweight watcher polls for the real kind:10002 arriving (via
     * this fetch or any other path) and closes the channel as soon as it does, or after
     * [OWN_PROFILE_BOOTSTRAP_MAX_MS] regardless, so it can't linger forever if the identity's
     * relay list genuinely isn't reachable from the current pool.
     */
    private suspend fun maybeBootstrapOwnProfile(pubkey: String?) {
        if (pubkey.isNullOrBlank()) return
        // The check-then-act sequence below (read ownProfileBootstrapPubkey, decide, then stop/
        // assign/start) must run as one exclusive unit — reconcile()'s two documented concurrent
        // entry points can otherwise both observe the pre-write state and both start a duplicate
        // bootstrap channel for the same pubkey.
        ownProfileBootstrapMutex.withLock {
            if (userRepository.getRelayList(pubkey)?.getOutboxRelays()?.isNotEmpty() == true) {
                stopOwnProfileBootstrap()
                return@withLock
            }
            if (ownProfileBootstrapPubkey == pubkey) return@withLock
            stopOwnProfileBootstrap()
            ownProfileBootstrapPubkey = pubkey
            bootstrapOwnProfileUseCase.start(pubkey)
            ownProfileBootstrapWatcherJob.launchReplacing(scope) {
                val deadline = System.currentTimeMillis() + OWN_PROFILE_BOOTSTRAP_MAX_MS
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(OWN_PROFILE_BOOTSTRAP_POLL_MS)
                    if (userRepository.getRelayList(pubkey)?.getOutboxRelays()?.isNotEmpty() == true) break
                }
                stopOwnProfileBootstrap()
            }
        }
    }

    private fun stopOwnProfileBootstrap() {
        if (ownProfileBootstrapPubkey == null) return
        ownProfileBootstrapWatcherJob.getAndSet(null)?.cancel()
        ownProfileBootstrapPubkey = null
        bootstrapOwnProfileUseCase.stop()
    }

    /**
     * @param resyncFromNow When true (a relay-set change — see [reconcile]), issues one
     * [EventRepository.resyncRecentHistory] pass per category for `[lastAnchor, now)` against the
     * *current* relay set before resuming normal backfill from `lastAnchor` — that window was
     * only ever asked of whichever relays were connected before this change, and a newly added/
     * re-enabled relay (e.g. the user's real NIP-65 list replacing bootstrap defaults) can hold
     * events in it the old set never had (public relays prune/lose history; a private relay may
     * keep everything). History *older* than `lastAnchor` doesn't need this — it was never fetched
     * by any relay set yet, old or new, so the normal below-the-anchor walk already covers it.
     */
    private fun startUserHistoryBackfill(pubkey: String?, resyncFromNow: Boolean = false) {
        val normalized = pubkey
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.length == 64 }
            ?.takeIf { it != UserPreferences.ANONYMOUS_PUBKEY }

        if (normalized == null) {
            userBackfillJob.getAndSet(null)?.cancel()
            backfillPubkey = null
            return
        }

        if (userBackfillJob.get()?.isActive == true && backfillPubkey == normalized && !resyncFromNow) return

        backfillPubkey = normalized

        userBackfillJob.launchReplacing(scope) {
            var windowSeconds = BACKFILL_START_WINDOW_SECS
            var limit = BACKFILL_MIN_LIMIT
            var idleWindows = 0
            var laneIndex = 0

            // Backfill moves backward in time from an anchor, using the oldest loaded event as
            // the new anchor for the next query. Resume from the last durably-recorded watermark
            // per category rather than always starting at "now" — outbox (own posts) already had
            // an effectively-durable anchor via the encrypted DB query in resolveAnchors, but
            // inbox (notes + reactions/reposts, one merged lane) only ever lived in the in-memory
            // cache, so without this every cold start silently forgot all inbox backfill progress
            // and re-walked the same recent window forever.
            val startAnchor = System.currentTimeMillis() / 1000L
            val anchors0 = BackfillAnchors(
                outbox = backfillAnchorStore.get(normalized, BackfillAnchorStore.CATEGORY_OUTBOX) ?: startAnchor,
                inbox = backfillAnchorStore.get(normalized, BackfillAnchorStore.CATEGORY_INBOX) ?: startAnchor
            )
            var anchors = anchors0

            if (resyncFromNow) {
                eventRepository.resyncRecentHistory(
                    channelId = NostrChannels.OUTBOX_NOTES,
                    sinceTimestamp = anchors0.outbox,
                    untilTimestamp = startAnchor,
                    limit = BACKFILL_MAX_LIMIT
                )
                eventRepository.resyncRecentHistory(
                    channelId = NostrChannels.INBOX_NOTES,
                    sinceTimestamp = anchors0.inbox,
                    untilTimestamp = startAnchor,
                    limit = BACKFILL_MAX_LIMIT
                )
            }

            while (isActive) {
                anchors = resolveAnchors(normalized, anchors)

                // Low-priority background backfill: only one lane per cycle so live feed REQs
                // keep higher responsiveness.
                when (laneIndex % 2) {
                    0 -> eventRepository.loadOlderEvents(
                        channelId = NostrChannels.OUTBOX_NOTES,
                        untilTimestamp = anchors.outbox,
                        windowSeconds = windowSeconds,
                        limit = limit
                    )
                    else -> eventRepository.loadOlderEvents(
                        channelId = NostrChannels.INBOX_NOTES,
                        untilTimestamp = anchors.inbox,
                        windowSeconds = windowSeconds,
                        limit = limit
                    )
                }
                laneIndex += 1

                delay(BACKFILL_STABILIZE_DELAY_MS)

                val before = anchors
                val after = resolveAnchors(normalized, anchors)
                val outboxProgress = (before.outbox - after.outbox).coerceAtLeast(0L)
                val inboxProgress = (before.inbox - after.inbox).coerceAtLeast(0L)
                val progressedSeconds = maxOf(outboxProgress, inboxProgress)
                val hadProgress = progressedSeconds > 0L

                anchors = after
                backfillAnchorStore.set(normalized, BackfillAnchorStore.CATEGORY_OUTBOX, after.outbox)
                backfillAnchorStore.set(normalized, BackfillAnchorStore.CATEGORY_INBOX, after.inbox)

                if (hadProgress) {
                    idleWindows = 0
                    windowSeconds = (progressedSeconds * 2)
                        .coerceIn(BACKFILL_MIN_WINDOW_SECS, BACKFILL_MAX_WINDOW_SECS)
                    limit = ((progressedSeconds / 86_400L).toInt() * 150)
                        .coerceIn(BACKFILL_MIN_LIMIT, BACKFILL_MAX_LIMIT)
                } else {
                    idleWindows += 1
                    windowSeconds = (windowSeconds * 2).coerceAtMost(BACKFILL_MAX_WINDOW_SECS)
                    limit = (limit + 150).coerceAtMost(BACKFILL_MAX_LIMIT)
                }

                val reachedDeepHistory =
                    anchors.outbox <= 1L &&
                        anchors.inbox <= 1L

                val cadenceMs = when {
                    reachedDeepHistory -> BACKFILL_DEEP_HISTORY_CADENCE_MS
                    idleWindows >= BACKFILL_IDLE_WINDOW_THRESHOLD -> BACKFILL_IDLE_CADENCE_MS
                    else -> BACKFILL_ACTIVE_CADENCE_MS
                }

                delay(cadenceMs)
            }
        }
    }

    /**
     * Anchors only ever move backward (toward older history), never forward — clamped to
     * `min(live query result, fallback)`. The live query (Room for outbox, the shared in-memory
     * LRU cache for inbox) reports whatever's oldest *currently retained*, which for the
     * in-memory-only inbox category can regress: unrelated general-feed activity can evict an old
     * inbox event from that shared cache before backfill gets back around to seeing it as
     * "already reached," which would otherwise silently un-walk real progress and repeat the same
     * window forever.
     *
     * Inbox notes and reactions/reposts now share one merged subscription/backfill lane (see
     * NostrChannels.INBOX_NOTES), so their two live queries are combined into one anchor: the
     * shallower (more recent) of the two oldest-retained timestamps is used, since that's the
     * weaker-link kind actually limiting how far back the merged lane can trust it's covered —
     * using the deeper one instead could wrongly treat the shallower kind's gap as already filled.
     */
    private suspend fun resolveAnchors(pubkey: String, fallback: BackfillAnchors): BackfillAnchors {
        val outbox = eventRepository.getOldestAuthorNoteTimestamp(pubkey)
            ?.takeIf { it > 0L }
            ?.let { minOf(it, fallback.outbox) }
            ?: fallback.outbox
        val oldestInboxNotes = eventRepository.getOldestInboxNoteTimestamp(pubkey)?.takeIf { it > 0L }
        val oldestInboxReactions = eventRepository.getOldestInboxReactionTimestamp(pubkey)?.takeIf { it > 0L }
        val inbox = when {
            oldestInboxNotes != null && oldestInboxReactions != null -> minOf(maxOf(oldestInboxNotes, oldestInboxReactions), fallback.inbox)
            oldestInboxNotes != null -> minOf(oldestInboxNotes, fallback.inbox)
            oldestInboxReactions != null -> minOf(oldestInboxReactions, fallback.inbox)
            else -> fallback.inbox
        }
        return BackfillAnchors(
            outbox = outbox,
            inbox = inbox
        )
    }

    private fun scheduleRetry() {
        retryJob.launchIfIdle(scope) {
            delay(RETRY_DELAY_MS)
            if (!isActive) return@launchIfIdle
            val snapshot = lastSnapshot ?: return@launchIfIdle
            reconcile(snapshot, snapshot)
        }
    }

    private data class OrchestratorSnapshot(
        val relays: List<Relay>,
        val feedFilter: FeedFilter,
        val authors: Set<String>,
        val pubkey: String?,
        val torReady: Boolean,
        val networkAvailable: Boolean,
        val torStatus: String
    ) {
        fun signature(): String {
            // authors is included separately from feedFilter (which doesn't carry it) so that a
            // contact-list change while scopeToFollows is active — same feedFilter, different
            // authors set — still counts as a real change instead of being swallowed by
            // distinctUntilChanged below.
            val authorsSignature = authors.sorted().joinToString(",")
            return "${pubkey ?: "anon"}|$torReady|$networkAvailable|$torStatus|$feedFilter|$authorsSignature|${relaySetSignature(relays)}"
        }
    }
}

