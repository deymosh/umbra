package com.umbra.app.data.tor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.umbra.app.TorProxyConfig
import com.umbra.app.data.nostr.OrBotConnectivityCheck
import com.umbra.app.domain.tor.TorRuntimeController
import com.umbra.app.domain.tor.TorRuntimeState
import com.umbra.app.domain.tor.TorRuntimeStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.umbra.app.util.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Tor runtime lifecycle and state, integrating with Orbot and system connectivity.
 * Handles Tor startup, status monitoring, and exposes state via StateFlow.
 */
@Singleton
class TorRuntimeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orBotCheck: OrBotConnectivityCheck
) : TorRuntimeController {
    companion object {
        private const val TAG = "UmbraTorRuntime"
        private const val ORBOT_PACKAGE = "org.torproject.android"
        private const val ACTION_START = "org.torproject.android.intent.action.START"
        private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
        private const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"
        private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
        private const val EXTRA_SOCKS_HOST = "org.torproject.android.intent.extra.SOCKS_PROXY_HOST"
        private const val EXTRA_SOCKS_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
        private const val STATUS_ON = "ON"
        private const val STATUS_STARTING = "STARTING"
        private const val REQUEST_START_EVERY_MS = 5_000L
        private const val STARTING_TIMEOUT_MS = 20_000L
        // Orbot's STATUS broadcast is the primary, reactive signal (registerStatusReceiver
        // below) — but it's only as reliable as Orbot actually sending it, and its own
        // "Disconnect" action (as opposed to fully quitting the app) isn't guaranteed to fire a
        // STOPPING/OFF broadcast in every Orbot version/flow. Without a fallback, a manual
        // Orbot-side disconnect while ready=true would never be noticed: nothing re-verifies
        // readiness once it's set, so the dot would stay green indefinitely against a dead
        // proxy. isOrBotAvailable() is a local 127.0.0.1:9050 socket connect (OrBotConnectivityCheck) —
        // microseconds when Orbot is healthy, so this isn't a UI-lag concern at any reasonable
        // interval. Matched to REQUEST_START_EVERY_MS (this check only actually runs on
        // maintenanceJob's own tick, so anything below that wouldn't fire any faster anyway) —
        // 5s instead of the previous 30s cuts worst-case detection latency 6x. Deliberately not
        // pushed lower still (e.g. 1s): a local socket probe running for the app's entire
        // foreground+background lifetime is cheap per-call but not free — very short, indefinite
        // polling intervals fight Android's Doze/App Standby expectations and read as
        // wakelock-abuse to OEM battery managers, for a detection-latency win past this point
        // that isn't perceptible to the user.
        private const val LIVENESS_CHECK_INTERVAL_MS = 5_000L
    }

    /**
     * Coroutine scope for Tor runtime operations. Uses SupervisorJob and IO dispatcher for background tasks.
     * Should be cancelled appropriately to avoid leaks.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = UmbraLog.tag(TAG)
    private val _state = MutableStateFlow(
        TorRuntimeState(
            status = TorRuntimeStatus.CHECKING,
            networkAvailable = false,
            host = TorProxyConfig.DEFAULT_HOST,
            port = TorProxyConfig.DEFAULT_PORT,
            ready = false
        )
    )
    /**
     * Exposes the current Tor runtime state as a StateFlow.
     */
    override val state: StateFlow<TorRuntimeState> = _state.asStateFlow()

    private var statusReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var maintenanceJob: Job? = null
    private var startingSinceMs: Long? = null
    private var lastLivenessCheckMs: Long = 0L

    @Volatile
    private var started = false

    override fun start() {
        if (started) return
        started = true

        registerStatusReceiver()
        registerNetworkCallback()
        refreshStateFromNetwork()

        if (_state.value.networkAvailable && !_state.value.ready) {
            requestOrbotStart()
        }

        maintenanceJob = scope.launch {
            while (isActive) {
                val snapshot = _state.value
                if (!snapshot.ready && snapshot.networkAvailable) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLivenessCheckMs >= LIVENESS_CHECK_INTERVAL_MS) {
                        lastLivenessCheckMs = now
                        // Orbot's STATUS broadcast only fires on a state *transition* — if Orbot
                        // was already running before we registered our receiver (e.g. cold app
                        // start with Orbot already connected), sending ACTION_START below is a
                        // no-op transition-wise and never gets answered, leaving us stuck showing
                        // "starting" until STARTING_TIMEOUT_MS even though Tor is already usable
                        // right now. A direct socket probe of the SOCKS endpoint (the same check
                        // used to detect Orbot going away below) catches that immediately instead
                        // of waiting on a broadcast that may never come.
                        if (orBotCheck.isOrBotAvailable()) {
                            TorProxyConfig.update(TorProxyConfig.host, TorProxyConfig.port)
                            startingSinceMs = null
                            updateState(
                                status = TorRuntimeStatus.READY,
                                ready = true,
                                host = TorProxyConfig.host,
                                port = TorProxyConfig.port
                            )
                            logger.d { "Orbot SOCKS proxy already answering — ready without waiting on a STATUS broadcast" }
                        }
                    }
                    if (!_state.value.ready) {
                        requestOrbotStart()
                    }
                } else if (snapshot.ready && snapshot.networkAvailable) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLivenessCheckMs >= LIVENESS_CHECK_INTERVAL_MS) {
                        lastLivenessCheckMs = now
                        // Blocking call, but this scope runs on Dispatchers.IO already — no
                        // separate withContext needed.
                        if (!orBotCheck.isOrBotAvailable()) {
                            logger.d { "Liveness probe failed while ready — Orbot went away without a STATUS broadcast" }
                            TorProxyConfig.reset()
                            startingSinceMs = null
                            updateState(
                                status = TorRuntimeStatus.WAITING_FOR_TOR,
                                ready = false,
                                host = TorProxyConfig.host,
                                port = TorProxyConfig.port
                            )
                            // Immediately try to bring it back (flips to STARTING_TOR/amber)
                            // instead of waiting up to REQUEST_START_EVERY_MS for the next tick.
                            requestOrbotStart()
                        }
                    }
                }

                val startedAt = startingSinceMs
                if (
                    startedAt != null &&
                    !snapshot.ready &&
                    snapshot.status == TorRuntimeStatus.STARTING_TOR &&
                    SystemClock.elapsedRealtime() - startedAt >= STARTING_TIMEOUT_MS
                ) {
                    startingSinceMs = null
                    updateState(
                        status = TorRuntimeStatus.WAITING_FOR_TOR,
                        ready = false,
                        host = TorProxyConfig.host,
                        port = TorProxyConfig.port
                    )
                }

                delay(REQUEST_START_EVERY_MS)
            }
        }
    }

    override fun stop() {
        started = false
        maintenanceJob?.cancel()
        maintenanceJob = null
        unregisterStatusReceiver()
        unregisterNetworkCallback()
    }

    private fun registerStatusReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_STATUS) return

                val status = intent.getStringExtra(EXTRA_STATUS) ?: return
                when (status) {
                    STATUS_ON -> {
                        startingSinceMs = null
                        val host = intent.getStringExtra(EXTRA_SOCKS_HOST)
                            ?: TorProxyConfig.DEFAULT_HOST
                        val port = intent.getIntExtra(EXTRA_SOCKS_PORT, -1)
                            .takeIf { it > 0 }
                            ?: intent.getStringExtra(EXTRA_SOCKS_PORT)?.toIntOrNull()
                            ?: TorProxyConfig.DEFAULT_PORT

                        if (!TorProxyConfig.update(host, port)) {
                            TorProxyConfig.reset()
                            updateState(
                                status = if (_state.value.networkAvailable) TorRuntimeStatus.WAITING_FOR_TOR else TorRuntimeStatus.WAITING_FOR_NETWORK,
                                ready = false,
                                host = TorProxyConfig.host,
                                port = TorProxyConfig.port
                            )
                            logger.d { "Rejected invalid Orbot SOCKS endpoint from STATUS broadcast" }
                            return
                        }

                        updateState(
                            status = TorRuntimeStatus.READY,
                            ready = true,
                            host = TorProxyConfig.host,
                            port = TorProxyConfig.port
                        )
                        logger.d { "Tor ready" }
                    }
                    STATUS_STARTING -> {
                        TorProxyConfig.reset()
                        startingSinceMs = SystemClock.elapsedRealtime()
                        updateState(
                            status = if (_state.value.networkAvailable) TorRuntimeStatus.STARTING_TOR else TorRuntimeStatus.WAITING_FOR_NETWORK,
                            ready = false,
                            host = TorProxyConfig.host,
                            port = TorProxyConfig.port
                        )
                        logger.d { "Tor status STARTING" }
                    }
                    else -> {
                        TorProxyConfig.reset()
                        startingSinceMs = null
                        updateState(
                            status = if (_state.value.networkAvailable) TorRuntimeStatus.WAITING_FOR_TOR else TorRuntimeStatus.WAITING_FOR_NETWORK,
                            ready = false,
                            host = TorProxyConfig.host,
                            port = TorProxyConfig.port
                        )
                        logger.d { "Tor status $status" }
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_STATUS),
            ContextCompat.RECEIVER_EXPORTED
        )
        statusReceiver = receiver
    }

    private fun unregisterStatusReceiver() {
        statusReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            statusReceiver = null
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshStateFromNetwork()
            }

            override fun onLost(network: Network) {
                TorProxyConfig.reset()
                refreshStateFromNetwork()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                refreshStateFromNetwork()
            }
        }

        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            logger.d { "Network callback registration failed: ${scrubThrowableMessageForLogs(it)}" }
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
            networkCallback = null
        }
    }

    private fun refreshStateFromNetwork() {
        val networkAvailable = isNetworkAvailable()

        if (!networkAvailable) {
            startingSinceMs = null
            updateState(
                status = TorRuntimeStatus.WAITING_FOR_NETWORK,
                networkAvailable = false,
                ready = false,
                host = TorProxyConfig.host,
                port = TorProxyConfig.port
            )
            return
        }

        // Use TorProxyConfig.isReady as the source of truth, not _state.value.ready.
        // This prevents false negatives when network transitions occur while Orbot is ready.
        if (TorProxyConfig.isReady) {
            updateState(
                status = TorRuntimeStatus.READY,
                networkAvailable = true,
                ready = true,
                host = TorProxyConfig.host,
                port = TorProxyConfig.port
            )
        } else {
            val preserveStartingState =
                startingSinceMs != null || _state.value.status == TorRuntimeStatus.STARTING_TOR
            updateState(
                status = if (preserveStartingState) TorRuntimeStatus.STARTING_TOR else TorRuntimeStatus.WAITING_FOR_TOR,
                networkAvailable = true,
                ready = false,
                host = TorProxyConfig.host,
                port = TorProxyConfig.port
            )
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun requestOrbotStart() {
        runCatching {
            // The maintenance loop calls this every REQUEST_START_EVERY_MS as long as we're not
            // ready — that used to mean re-broadcasting ACTION_START to Orbot every 5s for the
            // entire time it was already busy starting (a real boot can easily take longer than
            // that), which is what was destabilizing/crashing Orbot (telling it to keep connecting
            // the whole time it was already connecting) and, in turn, causing the STATUS broadcasts
            // driving TorGateScreen to flicker between "starting" and "not running." Only actually
            // send the broadcast on a genuine new attempt — first request, or after the
            // STARTING_TIMEOUT_MS watchdog above has reverted status back out of STARTING_TOR —
            // not on every tick while Orbot already knows we want it started.
            val alreadyStarting = _state.value.status == TorRuntimeStatus.STARTING_TOR
            if (!_state.value.ready && _state.value.networkAvailable && !alreadyStarting) {
                startingSinceMs = SystemClock.elapsedRealtime()
                updateState(
                    status = TorRuntimeStatus.STARTING_TOR,
                    ready = false,
                    host = TorProxyConfig.host,
                    port = TorProxyConfig.port
                )
            }
            if (alreadyStarting) return@runCatching

            val intent = Intent(ACTION_START).apply {
                setPackage(ORBOT_PACKAGE)
                putExtra(EXTRA_PACKAGE_NAME, context.packageName)
            }
            context.sendBroadcast(intent)
            logger.d { "Requested Orbot start/status" }
        }.onFailure {
            logger.d { "Failed to request Orbot start: ${scrubThrowableMessageForLogs(it)}" }
        }
    }

    private fun updateState(
        status: TorRuntimeStatus,
        networkAvailable: Boolean = _state.value.networkAvailable,
        host: String,
        port: Int,
        ready: Boolean
    ) {
        _state.update {
            TorRuntimeState(
                status = status,
                networkAvailable = networkAvailable,
                host = host,
                port = port,
                ready = ready
            )
        }
    }
}
