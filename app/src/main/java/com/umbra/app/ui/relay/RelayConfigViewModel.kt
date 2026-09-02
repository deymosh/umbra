package com.umbra.app.ui.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.R
import com.umbra.app.TorProxyConfig
import com.umbra.app.domain.nip44.Nip44Gateway
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.appendBoundedRelayIssues
import com.umbra.app.domain.nip77.SyncDirection
import com.umbra.app.domain.preferences.DeveloperFlag
import com.umbra.app.domain.preferences.DeveloperPreferences
import com.umbra.app.domain.preferences.SyncPreferences
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.RelayInfoRepository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.AddRelayUseCase
import com.umbra.app.domain.usecase.GetAllRelaysUseCase
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.RemoveRelayUseCase
import com.umbra.app.domain.usecase.UpdateRelayUseCase
import com.umbra.app.ui.common.UiMessage
import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

enum class RelayRole {
    OUTBOX,
    INBOX,
    DM,
    SEARCH,
    INDEX
}

data class RelayTelemetrySnapshot(
    val configured: Int = 0,
    val active: Int = 0,
    val connectedNow: Int = 0,
    val liveSubscriptions: Int = 0,
    val totalReceivedEvents: Int = 0,
    val nonConnectedIssues: Int = 0
)

data class RelayBuckets(
    val outbox: List<Relay> = emptyList(),
    val inbox: List<Relay> = emptyList(),
    val dm: List<Relay> = emptyList(),
    val search: List<Relay> = emptyList(),
    val index: List<Relay> = emptyList(),
    // Discovered relays split by current connection state so the connected ones (the ones
    // actually doing something useful right now) surface first, with the rest that never
    // connected/dropped grouped in their own section at the end rather than interleaved.
    // Disabled discovered relays get their own bucket too — otherwise a disabled relay and a
    // merely disconnected-but-enabled one would both land in discoveredOther, indistinguishable
    // except for the per-row status dot.
    val discoveredConnected: List<Relay> = emptyList(),
    val discoveredOther: List<Relay> = emptyList(),
    val discoveredDisabled: List<Relay> = emptyList(),
    val active: Int = 0,
    val connectedNow: Int = 0
)

@Immutable
data class RelayConfigState(
    val relays: List<Relay> = emptyList(),
    // False until observeRelays()'s first emission lands — lets RelayDetailsScreen tell "the
    // relay list just hasn't loaded yet" apart from "this relay genuinely doesn't exist," which
    // matters now that the feed's error banner can navigate straight to RelayDetails as the very
    // first screen in this graph this session (skipping RelayConfig, which used to give the list
    // time to settle first).
    val relaysLoaded: Boolean = false,
    val relayRequests: List<RelayRequestInfo> = emptyList(),
    val relayIssues: List<RelayIssue> = emptyList(),
    val connectedRelayUrls: Set<String> = emptySet(),
    val relayInfoLoading: Set<String> = emptySet(),
    val relayInfoRefreshResult: Map<String, Boolean> = emptyMap(),
    val isLoading: Boolean = false,
    val selectedRelay: Relay? = null,
    val isAnonymousSession: Boolean = false,
    val errorMessage: UiMessage? = null,
    val showAddDialog: Boolean = false,
    val editingRelay: Relay? = null,
    val addRole: RelayRole = RelayRole.OUTBOX,
    val currentUserPubkey: String? = null,
    // Set whenever a local edit changes what the corresponding relay-list kind would publish as
    // (kind 10002 outbox/inbox, 10050 DM, 10007 search, 10086 index) — cleared once that kind is
    // actually published. Drives RelayConfigScreen's top-bar Save button: local edits stay
    // immediate, publishing is a separate explicit action.
    val relayListDirty: Boolean = false,
    val dmRelayListDirty: Boolean = false,
    val searchListDirty: Boolean = false,
    val indexListDirty: Boolean = false,
    val isPublishing: Boolean = false,
    // Derived from relays/connectedRelayUrls/relayIssues/relayRequests by
    // observeDerivedRelayState() on Dispatchers.Default — see that function's doc comment for
    // why this isn't computed in the screen's own remember{} blocks.
    val relayBuckets: RelayBuckets = RelayBuckets(),
    val relayConnectionStates: Map<String, RelayConnectionIndicatorState> = emptyMap(),
    val telemetrySnapshot: RelayTelemetrySnapshot = RelayTelemetrySnapshot(),
    // Dev-flag gated (DeveloperFlag.SHOW_RELAY_TELEMETRY) — off by default, matches
    // SHOW_ALL_RELAY_BANNERS' existing default-hidden behavior for a comparable card.
    val showRelayTelemetry: Boolean = false,
    // NOT dev-flag gated, unlike showRelayTelemetry above — this is a real user-facing setting
    // (see NegentropySyncCard), not a diagnostic. Defaults to DOWNLOAD_ONLY, matching
    // SyncPreferencesImpl's own untouched-setting default.
    val negentropySyncDirection: SyncDirection = SyncDirection.DOWNLOAD_ONLY
)

@HiltViewModel
class RelayConfigViewModel @Inject constructor(
    private val relayInfoRepository: RelayInfoRepository,
    private val eventRepository: EventRepository,
    private val relayRepository: RelayRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val nip44Gateway: Nip44Gateway,
    private val getAllRelaysUseCase: GetAllRelaysUseCase,
    private val addRelayUseCase: AddRelayUseCase,
    private val updateRelayUseCase: UpdateRelayUseCase,
    private val removeRelayUseCase: RemoveRelayUseCase,
    private val publishSignedEventUseCase: PublishSignedEventUseCase,
    private val developerPreferences: DeveloperPreferences,
    private val syncPreferences: SyncPreferences
) : ViewModel() {

    companion object {
        // Per relay (by normalized URL), not global — see appendBoundedRelayIssue. A global cap
        // let one relay's burst of activity, or simply a large relay pool where every relay emits
        // its own one-time "Connected" message, evict a *different* relay's history first (e.g.
        // the user's own outbox/inbox relay's connection message from session start), even though
        // that's the history actually worth keeping. Matches RelayDetailsScreen's own
        // `.takeLast(50)` display truncation so nothing is lost between storage and display.
        private const val MAX_ISSUES_PER_RELAY = 50
        // A cold-start reconnect burst (many own + auto-discovered relays connecting within the
        // same reconcile() cycle) emits a RelayIssue per relay per state change in quick
        // succession. Applying each one as its own _state.update forced a full recomposition of
        // this screen's LazyColumn (relayBuckets/relayConnectionStates/telemetrySnapshot all key
        // off state.relayIssues) once per issue — dozens of times a second is exactly what made
        // opening this screen during startup feel stuck or ANR. Coalescing arrivals into a short
        // flush window decouples issue arrival rate from recomposition rate without dropping any
        // issue (unlike Flow.conflate/.sample, which would silently discard the coalesced ones).
        private const val RELAY_ISSUE_FLUSH_INTERVAL_MS = 250L

        // _relayRequestsFlow is a full-snapshot StateFlow bumped on every event received across
        // every open subscription (see EventRepositoryImpl). Forwarding each emission straight
        // into _state made Active Subscriptions/Relay Details recompose their whole subscription
        // list on every single incoming event, which is what makes those screens feel sluggish to
        // open while relays are actively streaming events in — same class of problem
        // RELAY_ISSUE_FLUSH_INTERVAL_MS above already fixed for relayIssues. Each relayRequests
        // emission is a full snapshot that supersedes the last, so — unlike relayIssues, a
        // discrete per-issue stream where every entry must survive the flush — only the latest
        // snapshot per window needs to be kept, not accumulated.
        private const val RELAY_REQUESTS_FLUSH_INTERVAL_MS = 400L

        // getAllRelaysUseCase() re-emits the *entire* relay list on every single write to the
        // relays table — including every one of the (potentially hundreds of) NIP-11 background
        // refreshes NostrSessionManager fires per relay-set-change reconcile, each landing at a
        // different time as its own Tor network round-trip completes. Unlike relayIssues/
        // relayRequests above, this collector had no flush window at all, so each of those
        // straggling writes drove its own immediate _state.update — the full O(relays) JSON-decode
        // in RelayMapper plus a full observeDerivedRelayState()/LazyColumn recompute, once per
        // relay per NIP-11 fetch completion, spread over a long tail. That's what made the relay
        // list feel like it never finished "settling" after opening the screen. Same
        // deliver-first-immediately-then-throttle-bursts shape as observeRelayRequests().
        private const val RELAY_LIST_FLUSH_INTERVAL_MS = 300L
    }

    private val _state = MutableStateFlow(RelayConfigState())
    val state: StateFlow<RelayConfigState> = _state.asStateFlow()
    private var defaultsBootstrapRequested = false

    // Manually constructed facade delegate (not Hilt-injected) — see ProfileObserversCoordinator's
    // precedent. Declared right after _state, not earlier, to avoid a Kotlin
    // forward-property-reference bug: a coordinator field declared before _state would see it as
    // uninitialized at construction time, since property initializers run top-to-bottom.
    private val relayCrudCoordinator = RelayCrudCoordinator(
        addRelayUseCase = addRelayUseCase,
        updateRelayUseCase = updateRelayUseCase,
        removeRelayUseCase = removeRelayUseCase,
        eventRepository = eventRepository,
        userPreferences = userPreferences,
        state = _state,
        scope = viewModelScope
    )

    // Manually constructed facade delegate (not Hilt-injected) — same NPE-avoidance rule as
    // relayCrudCoordinator above (declared after _state).
    private val relayListPublishingCoordinator = RelayListPublishingCoordinator(
        amberSignerGateway = amberSignerGateway,
        nip44Gateway = nip44Gateway,
        publishSignedEventUseCase = publishSignedEventUseCase,
        userPreferences = userPreferences,
        state = _state,
        scope = viewModelScope
    )

    init {
        _state.update {
            it.copy(
                isAnonymousSession = userPreferences.isAnonymousSession(),
                currentUserPubkey = userPreferences.getPublicKey()?.lowercase()
            )
        }
        observeRelays()
        observeRelayRequests()
        observeRelayIssues()
        observeConnectedRelays()
        observeDerivedRelayState()
        observeDeveloperOptions()
        observeSyncDirection()
    }

    private fun observeDeveloperOptions() {
        viewModelScope.launch {
            developerPreferences.observeEnabledFlags()
                .collect { flags ->
                    _state.update {
                        it.copy(showRelayTelemetry = flags.contains(DeveloperFlag.SHOW_RELAY_TELEMETRY))
                    }
                }
        }
    }

    private fun observeSyncDirection() {
        viewModelScope.launch {
            syncPreferences.observeNegentropySyncDirection()
                .collect { direction ->
                    _state.update { it.copy(negentropySyncDirection = direction) }
                }
        }
    }

    fun setNegentropySyncDirection(direction: SyncDirection) {
        syncPreferences.setNegentropySyncDirection(direction)
    }

    /**
     * Computes relayBuckets/relayConnectionStates/telemetrySnapshot on [Dispatchers.Default]
     * instead of the screen's own remember{} blocks, which ran this same work synchronously on
     * the main thread during RelayConfigScreen's composition every time it was navigated to.
     * relayConnectionStates in particular is O(relays × relayIssues) (see
     * resolveRelayConnectionIndicatorState), which is exactly the kind of first-frame cost that
     * made opening Relay Settings feel laggy — the nested-nav-graph fix (shared ViewModel across
     * Relay Settings/Details/Active Subscriptions) removed the *redundant* re-collection on every
     * hop between those three screens, but the very first entry into the relay section still paid
     * this cost inline. Maps over [_state] itself rather than the individual source flows so this
     * stays a single definition of "what feeds the derived state" — distinctUntilChanged on that
     * narrower projection is what keeps this from looping on its own _state.update below (only
     * the four raw fields it reads participate in the projection's equality, so writing back just
     * the three derived fields doesn't re-trigger it).
     */
    private fun observeDerivedRelayState() {
        viewModelScope.launch {
            _state
                .map { RelayDerivedStateInputs(it.relays, it.connectedRelayUrls, it.relayIssues, it.relayRequests) }
                .distinctUntilChanged()
                .map { inputs -> computeRelayDerivedState(inputs) }
                .flowOn(Dispatchers.Default)
                .collect { derived ->
                    _state.update {
                        it.copy(
                            relayBuckets = derived.buckets,
                            relayConnectionStates = derived.connectionStates,
                            telemetrySnapshot = derived.telemetry
                        )
                    }
                }
        }
    }

    private fun observeRelays() {
        var pendingRelays: List<Relay>? = null
        val pendingRelaysMutex = Mutex()
        // Deliver the first snapshot the instant it's collected — only throttle bursts after
        // that. Matches observeRelayRequests()'s reasoning: this ViewModel is screen-scoped, so
        // withholding even the first emission for a whole flush window would mean the relay list
        // rendered empty for RELAY_LIST_FLUSH_INTERVAL_MS on every fresh open.
        var deliveredFirst = false

        viewModelScope.launch {
            getAllRelaysUseCase().collect { relays ->
                val deliverNow = pendingRelaysMutex.withLock {
                    if (deliveredFirst) {
                        pendingRelays = relays
                        false
                    } else {
                        deliveredFirst = true
                        true
                    }
                }
                if (deliverNow) {
                    applyRelaysSnapshot(relays)
                }
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(RELAY_LIST_FLUSH_INTERVAL_MS)
                val snapshot = pendingRelaysMutex.withLock {
                    pendingRelays?.also { pendingRelays = null }
                } ?: continue
                applyRelaysSnapshot(snapshot)
            }
        }
    }

    private fun applyRelaysSnapshot(relays: List<Relay>) {
        if (relays.isEmpty() && !defaultsBootstrapRequested) {
            defaultsBootstrapRequested = true
            viewModelScope.launch {
                runCatching { relayRepository.bootstrapDefaultsOnFirstLogin() }
            }
        }
        _state.update {
            it.copy(
                relays = relays,
                relaysLoaded = true,
                isLoading = false,
                isAnonymousSession = userPreferences.isAnonymousSession()
            )
        }
        enforceAnonymousRelayPolicyIfNeeded(relays)
    }

    private fun enforceAnonymousRelayPolicyIfNeeded(relays: List<Relay>) {
        if (!userPreferences.isAnonymousSession()) return

        relays.asSequence()
            .filter { it.isReadEnabled || it.isDmEnabled }
            .forEach { relay ->
                viewModelScope.launch {
                    runCatching {
                        updateRelayUseCase(
                            relay.copy(
                                isReadEnabled = false,
                                isReadActive = false,
                                isDmEnabled = false,
                                isDmActive = false,
                                dmRequiresAuth = false,
                                isEnabled = relay.isWriteActive
                            )
                        )
                    }
                }
            }
    }

    private fun observeRelayRequests() {
        var pendingRequests: List<RelayRequestInfo>? = null
        val pendingRequestsMutex = Mutex()
        // This ViewModel is screen-scoped (a fresh instance per hiltViewModel() call at each of
        // RelayConfigScreen/RelayDetailsScreen/ActiveSubscriptionsScreen's nav destinations), so
        // observeRelayRequests() restarts from scratch on every screen open. Throttling every
        // emission — including the first — meant the screen rendered relayRequests = emptyList()
        // for up to RELAY_REQUESTS_FLUSH_INTERVAL_MS, then had the full (possibly large) snapshot
        // pop in all at once: a blank flash followed by its own layout hitch, worse than just
        // showing the real list from the start. Deliver the first snapshot the instant it's
        // collected (typically immediately, since this is a hot StateFlow that replays its
        // current value to new subscribers) and only throttle bursts after that.
        var deliveredFirst = false

        viewModelScope.launch {
            eventRepository.observeRelayRequests().collect { requests ->
                val deliverNow = pendingRequestsMutex.withLock {
                    if (deliveredFirst) {
                        pendingRequests = requests
                        false
                    } else {
                        deliveredFirst = true
                        true
                    }
                }
                if (deliverNow) {
                    _state.update { it.copy(relayRequests = requests) }
                }
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(RELAY_REQUESTS_FLUSH_INTERVAL_MS)
                val snapshot = pendingRequestsMutex.withLock {
                    pendingRequests?.also { pendingRequests = null }
                } ?: continue
                _state.update { it.copy(relayRequests = snapshot) }
            }
        }
    }

    private fun observeRelayIssues() {
        val pendingIssues = mutableListOf<RelayIssue>()
        val pendingIssuesMutex = Mutex()

        viewModelScope.launch {
            eventRepository.observeRelayIssues().collect { issue ->
                pendingIssuesMutex.withLock { pendingIssues += issue }
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(RELAY_ISSUE_FLUSH_INTERVAL_MS)
                val batch = pendingIssuesMutex.withLock {
                    if (pendingIssues.isEmpty()) {
                        null
                    } else {
                        pendingIssues.toList().also { pendingIssues.clear() }
                    }
                } ?: continue
                _state.update { state ->
                    state.copy(
                        relayIssues = appendBoundedRelayIssues(state.relayIssues, batch, MAX_ISSUES_PER_RELAY)
                    )
                }
            }
        }
    }

    private fun observeConnectedRelays() {
        viewModelScope.launch {
            eventRepository.observeConnectedRelayUrls().collect { connected ->
                _state.update {
                    it.copy(connectedRelayUrls = connected)
                }
            }
        }
    }

    fun publishRelayLists() = relayListPublishingCoordinator.publishRelayLists()

    fun selectRelay(relay: Relay) {
        _state.update { it.copy(selectedRelay = relay) }
    }

    fun openAddDialog(role: RelayRole = RelayRole.OUTBOX) {
        _state.update { it.copy(showAddDialog = true, addRole = role) }
    }

    fun closeAddDialog() {
        _state.update { it.copy(showAddDialog = false, editingRelay = null, addRole = RelayRole.OUTBOX) }
    }

    fun saveRelay(relay: Relay) = relayCrudCoordinator.saveRelay(relay)

    fun deleteRelay(relayId: String) = relayCrudCoordinator.deleteRelay(relayId)

    fun removeRelayRole(relayId: String, role: RelayRole) = relayCrudCoordinator.removeRelayRole(relayId, role)

    fun setOutboxEnabled(relayId: String, enabled: Boolean) = relayCrudCoordinator.setOutboxEnabled(relayId, enabled)

    fun setInboxEnabled(relayId: String, enabled: Boolean) = relayCrudCoordinator.setInboxEnabled(relayId, enabled)

    fun setDmEnabled(relayId: String, enabled: Boolean) = relayCrudCoordinator.setDmEnabled(relayId, enabled)

    fun setSearchEnabled(relayId: String, enabled: Boolean) = relayCrudCoordinator.setSearchEnabled(relayId, enabled)

    fun setIndexEnabled(relayId: String, enabled: Boolean) = relayCrudCoordinator.setIndexEnabled(relayId, enabled)

    /**
     * Toggle for the Discovered section only — a discovered relay never carries a real
     * isReadEnabled/isReadActive of its own (those exclusively reflect the user's genuine
     * kind:10002 declaration, see RelayRepositoryImpl.buildFirstLoginRelaySet /
     * UserRepositoryImpl.addDiscoveredRelays), so this must not route through setInboxEnabled —
     * doing so would fabricate a fake inbox declaration, which is exactly what made the Relay
     * Details "READ" badge (driven by isReadActive) incorrectly show ON for a relay that was
     * never actually declared as the user's inbox. This purely flips whether Umbra keeps using
     * this auto-discovered relay at all (mirrors connectToEnabledRelays()'s own `isEnabled ||
     * isDiscovered` eligibility check) — no relay-list kind becomes dirty, since a discovered
     * relay's on/off state is never part of anything published.
     */
    fun setDiscoveredRelayEnabled(relayId: String, enabled: Boolean) =
        relayCrudCoordinator.setDiscoveredRelayEnabled(relayId, enabled)

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun startEditingRelay(relay: Relay) {
        _state.update { it.copy(editingRelay = relay, showAddDialog = true) }
    }

    fun loadRelayInfo(relayUrl: String, forceRefresh: Boolean = false) {
        if (!TorProxyConfig.isReady) return
        if (relayUrl in _state.value.relayInfoLoading) return

        _state.update {
            val clearedStatus = if (forceRefresh) {
                it.relayInfoRefreshResult - relayUrl
            } else {
                it.relayInfoRefreshResult
            }
            it.copy(
                relayInfoLoading = it.relayInfoLoading + relayUrl,
                relayInfoRefreshResult = clearedStatus
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                relayInfoRepository.fetchAndPersist(relayUrl, force = forceRefresh)
            }

            _state.update {
                val refreshStatus = if (forceRefresh) {
                    it.relayInfoRefreshResult + (relayUrl to result.isSuccess)
                } else {
                    it.relayInfoRefreshResult
                }

                it.copy(
                    relayInfoLoading = it.relayInfoLoading - relayUrl,
                    relayInfoRefreshResult = refreshStatus,
                    errorMessage = if (!result.isSuccess && forceRefresh) {
                        UiMessage.Res(
                            R.string.relay_info_fetch_failed,
                            listOf(result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")
                        )
                    } else {
                        it.errorMessage
                    }
                )
            }
        }
    }

}

