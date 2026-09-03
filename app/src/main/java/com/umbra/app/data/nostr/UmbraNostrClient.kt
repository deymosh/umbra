package com.umbra.app.data.nostr

import com.umbra.app.TorProxyConfig
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.nip67.EoseSignal
import com.umbra.app.domain.nip77.NegSignal
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.RelayPublishResult
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.TorCircuitHealthTracker
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Named
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.Response
import com.umbra.app.util.logging.LogScrubber.scrubMessageForLogs
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.LogScrubber.scrubUrlForLogs
import com.umbra.app.util.logging.UmbraLog
import java.util.concurrent.ConcurrentHashMap

private const val EVENT_FRAME_PREFIX = "[\"EVENT\",\""
private const val EVENT_ID_KEY = "\"id\":\""

internal class ScannedEventFrame(val subId: String, val eventId: String)

/**
 * Extracts (subId, eventId) from a compact `["EVENT","<subId>",{...}]` frame without invoking
 * the JSON parser, or null when the frame isn't an EVENT or anything about it is unusual — null
 * means "do the full parse". A cache hit on [ScannedEventFrame.eventId] lets
 * [UmbraNostrClient.onWebSocketMessage] skip the full parse for a duplicate delivery entirely —
 * the same event commonly arrives once per matching subscription AND once per connected relay.
 * Deliberately conservative — bails to the full parse on anything unusual rather than risk a
 * false-positive cache hit:
 *  - the frame must start exactly with `["EVENT","` (any whitespace variant bails);
 *  - the subscription id must contain no escapes;
 *  - the id must be found as the literal `"id":"` key with a 64-lowercase-hex value. Valid JSON
 *    guarantees this substring cannot occur inside a string value (embedded quotes are escaped
 *    as `\"`, e.g. a kind-6 repost embedding a full event JSON in `content`), so a match is
 *    always the top-level id key.
 *
 * A top-level, dependency-free function (not a method on [UmbraNostrClient]) purely so it's
 * directly unit-testable without instantiating the client's real Tor/OkHttp dependencies.
 */
internal fun scanEventFrame(text: String): ScannedEventFrame? {
    if (!text.startsWith(EVENT_FRAME_PREFIX)) return null

    // subId: read up to the closing quote; any escape → bail.
    var i = EVENT_FRAME_PREFIX.length
    val subStart = i
    while (i < text.length) {
        val c = text[i]
        if (c == '\\') return null
        if (c == '"') break
        i++
    }
    if (i >= text.length) return null
    val subId = text.substring(subStart, i)

    // id: the literal top-level key with a 64-char lowercase-hex value.
    val idKeyIndex = text.indexOf(EVENT_ID_KEY, i)
    if (idKeyIndex < 0) return null
    val idStart = idKeyIndex + EVENT_ID_KEY.length
    val idEnd = idStart + 64
    if (idEnd >= text.length || text[idEnd] != '"') return null
    for (j in idStart until idEnd) {
        val c = text[j]
        if (c !in '0'..'9' && c !in 'a'..'f') return null
    }
    return ScannedEventFrame(subId, text.substring(idStart, idEnd))
}

/**
 * Centralizes shared WebSocket/relay state and connection-lifecycle helper methods. Per-message
 * parsing/dispatch (RelayMessageHandling.kt) and the OkHttp [okhttp3.WebSocketListener]
 * (RelayWebSocketListener.kt) live in separate files as `internal` extension functions/a
 * standalone class operating on this client's `internal`-visibility state, rather than as members
 * here — see those files' doc comments for why.
 */
@Singleton
class UmbraNostrClient @Inject constructor(
    @Named("tor") protected val torClient: OkHttpClient,
    protected val orBotCheck: OrBotConnectivityCheck
) : NostrClient {

    companion object {
        // internal (not private): read from RelayMessageHandling.kt's moved onWebSocket*/handle*
        // extension functions, which — being top-level extension functions in a different file —
        // can only see internal/public members of UmbraNostrClient, never private/protected ones.
        internal const val TAG = "UmbraRelayWSBase"
        internal const val RELAY_ACTIVITY_LOG_EVERY = 250
        // Cooldown durations (in ms) for each failure tier. Tightened again from the previous
        // 0s/5s/30s/60s ladder — at a 60s ceiling, reaching
        // MAX_CONSECUTIVE_FAILURES_BEFORE_AUTO_DISABLE (20) took roughly 17 minutes for a
        // genuinely dead relay. Capping at 30s instead gets there in ~9 minutes without
        // materially increasing Tor load: this only accelerates the *rate* of retries for
        // relays that are already failing — the total number of attempts before auto-disable
        // (20) is unchanged either way, this just compresses the same 20 attempts into a
        // shorter window. The first retry is no longer instant (0s) — a short 2s floor avoids
        // hammering a relay that just dropped the connection an instant ago.
        private val RECONNECT_DELAYS_MS = longArrayOf(
            2_000,
            5_000,
            15_000,
            30_000
        )
        // SOCKS error specific retry: more aggressive for .onion relays
        private const val SOCKS_MAX_IMMEDIATE_RETRIES = 2
        private const val SOCKS_MAX_IMMEDIATE_RETRIES_ONION = 4
        // A rate-limit notice means "same volume, slow down" — short throttle, self-expiring.
        // A block/forbidden notice means "this client isn't welcome" — much longer, since
        // repeating the same REQ volume immediately after either is exactly what caused it.
        private const val RATE_LIMIT_THROTTLE_MS = 5 * 60_000L
        private const val BLOCKED_THROTTLE_MS = 60 * 60_000L
        // Retry ladder while waiting for Orbot's SOCKS proxy to become available — see
        // orbotWaitDelayMs. Deliberately short/low-ceiling: this is "is the proxy up yet," not a
        // per-relay health signal.
        private val ORBOT_WAIT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
        // Bound for recentEventsById — the same popular event (a busy note's replies/reactions)
        // commonly arrives from many of the relays in the pool independently; this just needs to
        // be large enough that "the same event again a little later" is still a hit, not an
        // attempt at long-term storage (that's EventRepositoryImpl's job).
        private const val MAX_RECENT_EVENT_CACHE = 5000
        // A relay that's failed to connect this many times *in a row* (relayFailureCount, reset
        // on any successful reconnect) is assumed dead for this session and gets auto-disabled
        // (see RelayIssueKind.AUTO_DISABLED / NostrSessionManager) instead of retrying forever at
        // RECONNECT_DELAYS_MS's 60s ceiling. Given that ladder, 20 failures is roughly 17 minutes
        // of genuine, uninterrupted failure before giving up — not hasty. The user can always
        // re-enable it (resets the count, see resetFailureCount) for another run.
        const val MAX_CONSECUTIVE_FAILURES_BEFORE_AUTO_DISABLE = 20
    }

    internal val logger = UmbraLog.tag(TAG)

    internal val webSockets = ConcurrentHashMap<String, WebSocket>()
    // Per-relayUrl dial-in-flight guard for connect() — see that function's own doc comment for
    // the race it closes. Deliberately keyed by relayUrl only (not a single global lock), so
    // dials to *different* relays stay fully concurrent; this only dedupes overlapping dials to
    // the *same* relay.
    internal val dialingRelays = ConcurrentHashMap.newKeySet<String>()
    internal val activeRelayUrls = ConcurrentHashMap.newKeySet<String>()
    internal val intentionalDisconnects = ConcurrentHashMap.newKeySet<String>()
    internal val relayEventCounters = ConcurrentHashMap<String, Int>()

    internal val relayFailureCount = ConcurrentHashMap<String, Int>()
    internal val relayCooldownUntil = ConcurrentHashMap<String, Long>()
    internal val socksRetryCount = ConcurrentHashMap<String, Int>()
    // Separate from relayFailureCount: counts retries while waiting for Orbot's SOCKS proxy
    // itself to become available, not relay-specific failures. See orbotWaitDelayMs.
    internal val orbotWaitRetryCount = ConcurrentHashMap<String, Int>()
    // Set when a relay tells us (NOTICE/CLOSED/OK) it's rate-limiting or rejecting us — REQs are
    // withheld until this expires instead of repeating the exact volume that triggered it.
    protected val relayThrottledUntil = ConcurrentHashMap<String, Long>()
    // Relays that closed a REQ telling us they don't accept subscriptions at all (e.g. nosflare's
    // broadcast-only "sendit" variant). Unlike relayThrottledUntil this never expires — it's a
    // capability, not a transient condition — but it's only kept in memory for the process
    // lifetime, no NIP-11 field advertises this in advance so it can only be learned at runtime.
    internal val relayReqUnsupported = ConcurrentHashMap.newKeySet<String>()
    // Relays that closed a REQ telling us every filter must include a NIP-50 `search` field
    // (e.g. relays running searchnos) — the mirror case of relayReqUnsupported: these relays are
    // reachable, just search-only, so non-search REQs should be withheld rather than retried.
    internal val relayRequiresSearch = ConcurrentHashMap.newKeySet<String>()
    // Relays that have told us (NOTICE/CLOSED) they're over their concurrent-subscription count —
    // see isSubscriptionLimitMessage/ChannelPriority. Non-essential channels are withheld from
    // these relays so the essential ones (own notes/DMs/interactions) don't keep losing their
    // slot to background hydration/sweep/search REQs.
    protected val relaySubscriptionLimited = ConcurrentHashMap.newKeySet<String>()
    // Relays that closed a REQ telling us its subscription id is a "duplicate" — NIP-01 requires
    // treating a REQ that reuses an already-open subscription_id as a silent filter replace, but
    // some relay implementations instead CLOSE it as an error. Umbra intentionally reuses one
    // subId per (relay, channel) for as long as a channel stays open (see
    // EventRepositoryImpl.getOrCreateSubId) — cheap and spec-correct against a compliant relay,
    // but against one of these it means every legitimate filter update (e.g. the pooled
    // event-lookup channel's pending-id set changing) gets rejected. Once a relay is marked here,
    // getOrCreateSubId mints a fresh subId on its next reapply instead of reusing the mapped one.
    internal val relayRejectsSubIdReuse = ConcurrentHashMap.newKeySet<String>()
    // Relays whose NIP-11 document claims NIP-77 support but rejected a NEG-OPEN with a generic
    // NOTICE saying negentropy is actually disabled (see isNegentropyUnsupportedMessage) — e.g.
    // strfry with the feature compiled in but turned off in config. Never expires, same rationale
    // as relayReqUnsupported: it's a capability mismatch discovered at runtime, not a transient
    // condition, and NIP-11 gave us no advance warning. NegentropySyncOrchestrator checks this
    // before attempting a sync against a relay again.
    protected val relayNegentropyUnsupported = ConcurrentHashMap.newKeySet<String>()
    // The same popular event (a busy note's replies/reactions) commonly arrives independently
    // from many relays in the pool — this avoids reconstructing the Event (tag-list mapping,
    // field extraction) for every duplicate delivery. See handleEventMessage.
    internal val recentEventsById = BoundedEventCache(MAX_RECENT_EVENT_CACHE)
    // Channel<->subscriptionId lifecycle bookkeeping (subId minting/reuse, subId->channel
    // history, per-channel reapply fingerprint dedup) — see applyChannel and its neighbors below.
    internal val subscriptions = RelaySubscriptionRegistry()
    // Diagnostic only — see TorCircuitHealthTracker's own doc. Fed from onWebSocketOpen (success)
    // and onWebSocketFailure/onWebSocketClosed (failure, only while Tor itself reports ready).
    internal val torCircuitHealthTracker = TorCircuitHealthTracker()

    internal val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Per-relay challenge string received via a real ["AUTH", challenge] frame (NIP-42).
     * ONLY this stored value should be signed and sent back as AUTH response.
     * Error texts from CLOSED/OK ("auth-required: ...") are NOT challenges.
     */
    internal val storedAuthChallenges = ConcurrentHashMap<String, String>()

    internal val _eventFlow = MutableSharedFlow<Pair<String, Event>>()
    override val eventFlow: SharedFlow<Pair<String, Event>> = _eventFlow.asSharedFlow()
    protected val _reqFlow = MutableSharedFlow<RelayRequestInfo>(extraBufferCapacity = 128)
    override val reqFlow: SharedFlow<RelayRequestInfo> = _reqFlow.asSharedFlow()
    internal val _subscriptionEventFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 256)
    override val subscriptionEventFlow: SharedFlow<Pair<String, String>> = _subscriptionEventFlow.asSharedFlow()
    internal val _eoseFlow = MutableSharedFlow<EoseSignal>(extraBufferCapacity = 256)
    override val eoseFlow: SharedFlow<EoseSignal> = _eoseFlow.asSharedFlow()
    internal val _countFlow = MutableSharedFlow<RelayCountResult>(extraBufferCapacity = 128)
    override val countFlow: SharedFlow<RelayCountResult> = _countFlow.asSharedFlow()
    internal val _negMessageFlow = MutableSharedFlow<NegSignal>(extraBufferCapacity = 128)
    override val negMessageFlow: SharedFlow<NegSignal> = _negMessageFlow.asSharedFlow()
    // RelayConfigViewModel (screen-scoped, likely re-created each time the Relay Config screen
    // opens) accumulates its own capped history from this flow — but a *fresh* collector only
    // ever receives the replay cache, never anything emitted before it started collecting. A
    // replay of 64 was fine when the relay pool was small; with it now able to reach ~250 (see
    // UserRepositoryImpl.MAX_TOTAL_DISCOVERED_RELAYS), even the one-time "Connected" message from
    // every relay can exceed 64, silently pushing an already-connected relay's own message out of
    // what a freshly (re)opened screen ever sees. Matches RelayConfigViewModel.MAX_RELAY_ISSUES.
    protected val _relayIssueFlow = MutableSharedFlow<RelayIssue>(replay = 3000, extraBufferCapacity = 128)
    override val relayIssueFlow: SharedFlow<RelayIssue> = _relayIssueFlow.asSharedFlow()
    protected val _connectedRelayUrlsFlow = MutableStateFlow<Set<String>>(emptySet())
    override val connectedRelayUrlsFlow: StateFlow<Set<String>> = _connectedRelayUrlsFlow
    internal val _relayOpenedFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val relayOpenedFlow: SharedFlow<String> = _relayOpenedFlow.asSharedFlow()
    internal val _publishResultFlow = MutableSharedFlow<RelayPublishResult>(extraBufferCapacity = 64)
    override val publishResultFlow: SharedFlow<RelayPublishResult> = _publishResultFlow.asSharedFlow()

    internal fun publishConnectedRelaySnapshot() {
        _connectedRelayUrlsFlow.value = activeRelayUrls.toSet()
    }

    protected fun closeRelaySocket(relayUrl: String, reason: String, markIntentional: Boolean) {
        if (markIntentional) {
            intentionalDisconnects.add(relayUrl)
        }
        val webSocket = webSockets.remove(relayUrl)
        activeRelayUrls.remove(relayUrl)
        publishConnectedRelaySnapshot()
        if (webSocket != null) {
            webSocket.close(1000, reason)
        }
    }

    protected fun isInCooldown(relayUrl: String): Boolean {
        val until = relayCooldownUntil[relayUrl] ?: return false
        return System.currentTimeMillis() < until
    }

    internal fun recordFailureAndScheduleReconnect(relayUrl: String, reconnectAction: suspend () -> Unit) {
        val count = (relayFailureCount[relayUrl] ?: 0) + 1
        relayFailureCount[relayUrl] = count

        val delayMs = RECONNECT_DELAYS_MS.getOrLast(count - 1)
        relayCooldownUntil[relayUrl] = System.currentTimeMillis() + delayMs

        logger.d { "Relay failure #$count for ${scrubUrlForLogs(relayUrl)}: backoff ${delayMs / 1000}s" }
        emitRelayIssue(
            relayUrl = relayUrl,
            kind = RelayIssueKind.NETWORK,
            message = "Relay connection failed. Retrying in ${delayMs / 1000}s.",
            cooldownSeconds = delayMs / 1000
        )

        // == not >=: fires exactly once per disable, not on every failure past the threshold —
        // relayFailureCount only keeps climbing from here since this relay stays in the pool
        // (isEnabled flips false asynchronously, downstream — see NostrSessionManager) until the
        // user re-enables it, which resets the count via resetFailureCount.
        if (count == MAX_CONSECUTIVE_FAILURES_BEFORE_AUTO_DISABLE) {
            logger.d { "Relay ${scrubUrlForLogs(relayUrl)} hit $count consecutive failures — auto-disabling" }
            emitRelayIssue(
                relayUrl = relayUrl,
                kind = RelayIssueKind.AUTO_DISABLED,
                message = "Relay failed to connect $count times in a row and was disabled."
            )
        }

        clientScope.launch {
            delay(delayMs)
            reconnectAction()
        }
    }

    override fun resetFailureCount(relayUrl: String) {
        relayFailureCount.remove(relayUrl)
    }

    override fun resetAllBackoff() {
        relayFailureCount.clear()
        relayCooldownUntil.clear()
        socksRetryCount.clear()
        orbotWaitRetryCount.clear()
    }

    private fun LongArray.getOrLast(index: Int): Long = if (index < size) this[index] else last()

    internal fun classifyNotice(notice: String): RelayIssueKind = classifyRelayNotice(notice)

    /**
     * Sticky for the process lifetime, same permanence model as [relayReqUnsupported]/
     * [relayRequiresSearch] — a relay's concurrent-subscription cap isn't something that resolves
     * itself mid-session, so once learned there's no reason to keep testing it.
     */
    internal fun markIfSubscriptionLimited(relayUrl: String, kind: RelayIssueKind) {
        if (kind != RelayIssueKind.SUBSCRIPTION_LIMIT) return
        if (relaySubscriptionLimited.add(relayUrl)) {
            logger.d { "${scrubUrlForLogs(relayUrl)} is subscription-limited — withholding non-essential channels" }
        }
    }

    internal fun markIfNegentropyUnsupported(relayUrl: String, kind: RelayIssueKind) {
        if (kind != RelayIssueKind.NEGENTROPY_UNSUPPORTED) return
        if (relayNegentropyUnsupported.add(relayUrl)) {
            logger.d { "${scrubUrlForLogs(relayUrl)} has NIP-77 disabled — no longer attempting sync against it" }
        }
    }

    internal fun emitRelayIssue(
        relayUrl: String,
        kind: RelayIssueKind,
        message: String,
        cooldownSeconds: Long? = null,
        isAuthChallenge: Boolean = false
    ) {
        _relayIssueFlow.tryEmit(
            RelayIssue(
                relayUrl = relayUrl,
                kind = kind,
                rawMessage = message,
                cooldownSeconds = cooldownSeconds,
                isAuthChallenge = isAuthChallenge
            )
        )
    }

    internal fun isSocksFailure(throwable: Throwable): Boolean {
        val message = throwable.message.orEmpty()
        return message.contains("SOCKS", ignoreCase = true) ||
            (throwable is java.net.SocketException && message.contains("SOCKS", ignoreCase = true)) ||
            // Orbot's SOCKS proxy itself refusing/dropping the connection (restarting, between
            // states) — not the destination relay's fault, so it's routed through the same short
            // fixed-retry path as other SOCKS-layer hiccups rather than the relay's own long-term
            // failure backoff. See isLocalProxyRefusal.
            isLocalProxyRefusal(message, TorProxyConfig.host)
    }

    internal fun logWebSocketFailure(relayUrl: String, throwable: Throwable, response: Response?, isSocksError: Boolean) {
        val relay = scrubUrlForLogs(relayUrl)
        val responseInfo = response?.code?.let { " (HTTP $it)" }.orEmpty()
        val errorMessage = scrubThrowableMessageForLogs(throwable)

        if (isSocksError) {
            logger.d { "Transient SOCKS failure for $relay$responseInfo: $errorMessage" }
            return
        }

        logger.e(throwable) { "WebSocket error for $relay$responseInfo" }
    }


    /**
     * Connect to a Nostr relay via ORBOT SOCKS5 proxy (TOR)
     *
     * Three independent callers (EventRepositoryImpl's connectToEnabledRelays/
     * reconnectRelevantDiscoveredRelays/connectToRelayHints) each pre-check isConnected()/
     * hasActiveSocket() before calling this — but that check-then-act pair isn't atomic across
     * callers, so two of them racing for the same relayUrl (e.g. the reconcile loop and a Tor-
     * circuit-recovery retry firing close together) could both see "not connected yet" and both
     * proceed, each tearing down and redialing independently — the same shape of TOCTOU race as
     * the relay-list-save one closed via ConcurrentHashMap.compute() (see UserRepositoryImpl.
     * saveRelayList's own doc comment). dialingRelays.add() is the atomic compare-and-set here:
     * only the caller that wins gets past this point for a given relayUrl; a loser no-ops instead
     * of racing its own socket into existence. Scoped per relayUrl (not a single lock across the
     * whole client), so concurrent dials to *different* relays are entirely unaffected — the
     * batched-parallel-dial pacing in connectToEnabledRelays keeps working as designed.
     */
    override fun connect(relayUrl: String): Result<Unit> = runCatching {
        if (!dialingRelays.add(relayUrl)) {
            logger.d { "Dial already in flight for ${scrubUrlForLogs(relayUrl)} - skip" }
            return@runCatching
        }
        try {
            if (!TorProxyConfig.isReady) {
                emitRelayIssue(
                    relayUrl = relayUrl,
                    kind = RelayIssueKind.NETWORK,
                    message = "Tor proxy not ready. Skipping relay connection until Orbot is ready."
                )
                return@runCatching
            }

            if (isInCooldown(relayUrl)) {
                val remainingSecs = (relayCooldownUntil[relayUrl]!! - System.currentTimeMillis()) / 1000
                logger.d { "Relay ${scrubUrlForLogs(relayUrl)} is in cooldown for ${remainingSecs}s — skip" }
                return@runCatching
            }

            intentionalDisconnects.remove(relayUrl)
            closeRelaySocket(relayUrl, reason = "Reconnect", markIntentional = false)

            logger.d { "Connecting to relay: ${scrubUrlForLogs(relayUrl)}" }

            val isOnion = relayUrl.contains(".onion")

            if (!orBotCheck.isOrBotAvailable()) {
                // Growing delay, not a flat 1s retry — this isn't the relay's fault, but a flat retry
                // for every enabled relay every second for as long as Orbot stays down is exactly the
                // "hammering a dead proxy" pattern a transport-level gate should avoid. Kept separate
                // from relayFailureCount/the long-term backoff ladder either way.
                val attempt = (orbotWaitRetryCount[relayUrl] ?: 0) + 1
                orbotWaitRetryCount[relayUrl] = attempt
                val waitDelayMs = orbotWaitDelayMs(attempt, ORBOT_WAIT_DELAYS_MS)
                logger.d { "Orbot SOCKS5 server not available for ${scrubUrlForLogs(relayUrl)} - retrying in ${waitDelayMs}ms" }
                emitRelayIssue(
                    relayUrl = relayUrl,
                    kind = RelayIssueKind.NETWORK,
                    message = "Orbot SOCKS5 not available. Please ensure Orbot is running and TOR is initialized."
                )
                clientScope.launch {
                    delay(waitDelayMs)
                    connect(relayUrl)
                }
                return@runCatching
            }
            orbotWaitRetryCount.remove(relayUrl)

            when {
                relayUrl.startsWith("wss://") && !isOnion -> {
                    logger.d { "Clearnet WSS: Using TLS encryption over TOR" }
                }
                relayUrl.startsWith("ws://") && isOnion -> {
                    logger.d { ".onion relay detected: ${scrubUrlForLogs(relayUrl)} - Using CLEARTEXT via SOCKS proxy (TOR tunnel provides security)" }
                }
                relayUrl.startsWith("ws://") && !isOnion -> {
                    logger.d { "Clearnet WS: Using CLEARTEXT over TOR tunnel" }
                }
                relayUrl.startsWith("wss://") && isOnion -> {
                    logger.d { ".onion WSS: Using default TLS validation over TOR" }
                }
            }
            emitRelayIssue(
                relayUrl = relayUrl,
                kind = RelayIssueKind.CONNECTING,
                message = "Connecting to relay..."
            )
            val request = Request.Builder().url(relayUrl).build()
            val webSocket = torClient.newWebSocket(request, RelayWebSocketListener(relayUrl, isOnion, client = this, scope = clientScope))
            webSockets[relayUrl] = webSocket
            logger.d { "Connection initiated to relay via ORBOT SOCKS5: ${scrubUrlForLogs(relayUrl)}" }
        } finally {
            // Released the instant a socket is created (or the dial bails out early) — from that
            // point on hasActiveSocket()/isConnected() already correctly reflect this relay's
            // state, so holding the guard any longer would only block a legitimate later
            // reconnect attempt for no benefit.
            dialingRelays.remove(relayUrl)
        }
    }

    override fun subscribe(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {
        val webSocket = webSockets[relayUrl]
        if (webSocket == null) {
            logger.d { "WebSocket not connected for relay: ${scrubUrlForLogs(relayUrl)}" }
            return
        }

        val subscribePayload = NostrRequestBuilder.req(subscriptionId, filters)
        logger.d { "REQ relay=${scrubUrlForLogs(relayUrl)} subId=$subscriptionId filters=${filters.size}" }
        webSocket.send(subscribePayload)
        logger.d { "Subscribed to ${scrubUrlForLogs(relayUrl)} with ID: $subscriptionId" }

        _reqFlow.tryEmit(
            RelayRequestInfo(
                relayUrl = relayUrl,
                subscriptionId = subscriptionId,
                filters = filters
            )
        )
    }

    override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {
        val webSocket = webSockets[relayUrl]
        if (webSocket == null) {
            logger.d { "WebSocket not connected for relay: ${scrubUrlForLogs(relayUrl)}" }
            return
        }

        webSocket.send(NostrRequestBuilder.count(subscriptionId, filters))
        logger.d { "COUNT relay=${scrubUrlForLogs(relayUrl)} subId=$subscriptionId filters=${filters.size}" }
    }

    override fun negOpen(relayUrl: String, subscriptionId: String, filter: EventFilter, initialMessageHex: String) {
        val webSocket = webSockets[relayUrl] ?: return
        webSocket.send(NostrRequestBuilder.negOpen(subscriptionId, filter, initialMessageHex))
        logger.d { "NEG-OPEN relay=${scrubUrlForLogs(relayUrl)} subId=$subscriptionId" }
    }

    override fun negMsg(relayUrl: String, subscriptionId: String, messageHex: String) {
        val webSocket = webSockets[relayUrl] ?: return
        webSocket.send(NostrRequestBuilder.negMsg(subscriptionId, messageHex))
    }

    override fun negClose(relayUrl: String, subscriptionId: String) {
        val webSocket = webSockets[relayUrl] ?: return
        webSocket.send(NostrRequestBuilder.negClose(subscriptionId))
        logger.d { "NEG-CLOSE relay=${scrubUrlForLogs(relayUrl)} subId=$subscriptionId" }
    }

    override fun publishEvent(relayUrl: String, event: Event) {
        val webSocket = webSockets[relayUrl]
        if (webSocket == null) {
            logger.d { "WebSocket not connected for relay: ${scrubUrlForLogs(relayUrl)}" }
            return
        }

        webSocket.send(NostrRequestBuilder.event(event))
        logger.d { "Published event ${event.id.take(8)} to ${scrubUrlForLogs(relayUrl)}" }
    }

    override fun publishAuthEvent(relayUrl: String, event: Event) {
        val webSocket = webSockets[relayUrl]
        if (webSocket == null) {
            logger.d { "WebSocket not connected for relay: ${scrubUrlForLogs(relayUrl)}" }
            return
        }

        webSocket.send(NostrRequestBuilder.auth(event))
        logger.d { "Published AUTH event ${event.id.take(8)} to ${scrubUrlForLogs(relayUrl)}" }
    }

    override suspend fun publishEvent(event: Event) {
        publishEventToRelays(event, webSockets.keys.toList())
    }

    override suspend fun publishEventToRelays(event: Event, relayUrls: List<String>) {
        relayUrls.forEach { relayUrl ->
            publishEvent(relayUrl, event)
            kotlinx.coroutines.delay(100)
        }
    }

    override fun unsubscribe(relayUrl: String, subscriptionId: String) {
        val webSocket = webSockets[relayUrl]
        if (webSocket == null) {
            logger.d { "WebSocket not connected for relay: ${scrubUrlForLogs(relayUrl)}" }
            return
        }

        webSocket.send(NostrRequestBuilder.close(subscriptionId))
        logger.d { "Unsubscribed from ${scrubUrlForLogs(relayUrl)}: $subscriptionId" }
    }

    override fun disconnect(relayUrl: String) {
        closeRelaySocket(relayUrl, reason = "Client disconnect", markIntentional = true)
        logger.d { "Disconnected from relay: ${scrubUrlForLogs(relayUrl)}" }
    }

    override fun forgetRelay(relayUrl: String) {
        closeRelaySocket(relayUrl, reason = "Client disconnect", markIntentional = true)
        relayFailureCount.remove(relayUrl)
        relayCooldownUntil.remove(relayUrl)
        socksRetryCount.remove(relayUrl)
        orbotWaitRetryCount.remove(relayUrl)
        relayThrottledUntil.remove(relayUrl)
        relayReqUnsupported.remove(relayUrl)
        relayRequiresSearch.remove(relayUrl)
        relaySubscriptionLimited.remove(relayUrl)
        relayRejectsSubIdReuse.remove(relayUrl)
        relayEventCounters.remove(relayUrl)
        storedAuthChallenges.remove(relayUrl)
        subscriptions.forgetRelay(relayUrl)
        logger.d { "Forgot relay entirely: ${scrubUrlForLogs(relayUrl)}" }
    }

    override fun disconnectAll() {
        webSockets.keys.toList().forEach { relayUrl ->
            closeRelaySocket(relayUrl, reason = "Client disconnect all", markIntentional = true)
        }
        relayFailureCount.clear()
        relayCooldownUntil.clear()
        relayThrottledUntil.clear()
        subscriptions.resetAll()
        logger.d { "Disconnected from all relays" }
    }

    override fun isConnected(relayUrl: String): Boolean = activeRelayUrls.contains(relayUrl)

    override fun hasActiveSocket(relayUrl: String): Boolean = webSockets.containsKey(relayUrl)

    override fun isThrottled(relayUrl: String): Boolean {
        val until = relayThrottledUntil[relayUrl] ?: return false
        return System.currentTimeMillis() < until
    }

    override fun isReqUnsupported(relayUrl: String): Boolean = relayReqUnsupported.contains(relayUrl)

    override fun requiresSearchFilter(relayUrl: String): Boolean = relayRequiresSearch.contains(relayUrl)

    override fun isSubscriptionLimited(relayUrl: String): Boolean = relaySubscriptionLimited.contains(relayUrl)

    override fun isNegentropyUnsupported(relayUrl: String): Boolean = relayNegentropyUnsupported.contains(relayUrl)

    override fun rejectsSubIdReuse(relayUrl: String): Boolean = relayRejectsSubIdReuse.contains(relayUrl)

    override fun applyChannel(channelId: String, relayUrl: String, filters: List<EventFilter>): Boolean {
        if (!isConnected(relayUrl)) return false
        if (isThrottled(relayUrl)) return false
        if (isReqUnsupported(relayUrl)) return false
        if (requiresSearchFilter(relayUrl) && filters.none { !it.search.isNullOrBlank() }) return false
        if (!subscriptions.hasChanged(relayUrl, channelId, filters)) return false
        val subId = subscriptions.getOrCreateSubId(relayUrl, channelId, rejectsSubIdReuse(relayUrl))
        subscribe(relayUrl, subId, filters)
        subscriptions.recordSent(relayUrl, channelId, filters)
        return true
    }

    override fun currentSubscriptionId(relayUrl: String, channelId: String): String? =
        subscriptions.currentSubId(relayUrl, channelId)

    override fun subscribedChannelCount(relayUrl: String): Int = subscriptions.channelCount(relayUrl)

    override fun resolveChannelId(relayUrl: String, subscriptionId: String): String? =
        subscriptions.resolveChannelId(relayUrl, subscriptionId)

    override fun subscriptionsForChannel(channelId: String): Set<Pair<String, String>> =
        subscriptions.subscriptionsForChannel(channelId)

    override fun clearChannelSubscription(relayUrl: String, channelId: String): String? {
        val subId = subscriptions.remove(relayUrl, channelId) ?: return null
        unsubscribe(relayUrl, subId)
        return subId
    }

    override fun registerTrackedSubscription(relayUrl: String, channelId: String, filters: List<EventFilter>): String {
        val subId = subscriptions.getOrCreateSubId(relayUrl, channelId, rejectsSubIdReuse(relayUrl))
        _reqFlow.tryEmit(RelayRequestInfo(relayUrl = relayUrl, subscriptionId = subId, filters = filters))
        return subId
    }

    override fun unregisterTrackedSubscription(relayUrl: String, channelId: String): String? =
        subscriptions.remove(relayUrl, channelId)

    override fun resetSubscriptionBookkeeping() {
        subscriptions.resetAll()
    }

    internal fun applyThrottleIfNeeded(relayUrl: String, kind: RelayIssueKind) {
        val throttleMs = when (kind) {
            RelayIssueKind.RATE_LIMIT -> RATE_LIMIT_THROTTLE_MS
            RelayIssueKind.BLOCKED -> BLOCKED_THROTTLE_MS
            else -> return
        }
        relayThrottledUntil[relayUrl] = System.currentTimeMillis() + throttleMs
        logger.d { "Throttling ${scrubUrlForLogs(relayUrl)} for ${throttleMs / 1000}s after $kind" }
    }
}
