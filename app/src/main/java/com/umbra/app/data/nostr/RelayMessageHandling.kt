package com.umbra.app.data.nostr

import com.umbra.app.TorProxyConfig
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip67.EoseSignal
import com.umbra.app.domain.nip67.parseEoseCompleteness
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.nip77.NegSignal
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.RelayPublishResult
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.util.LogScrubber.scrubMessageForLogs
import com.umbra.app.util.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.LogScrubber.scrubUrlForLogs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket

/**
 * [UmbraNostrClient]'s per-message parsing/dispatch, split out of the client's own file purely
 * for readability — this was ~430 lines living inline inside a 1165-line file. Every function
 * here is an `internal`/private extension function operating on [UmbraNostrClient]'s state
 * (widened from private/protected to internal specifically so this file can reach it — see that
 * class's own companion/field doc comments) rather than a member, since [UmbraNostrClient] itself
 * is not `open` and has no real subclass to justify keeping this as inheritable member surface.
 *
 * Only [onWebSocketOpen]/[onWebSocketMessage]/[onWebSocketClosing]/[onWebSocketClosed]/
 * [onWebSocketFailure] are `internal` — those are the ones [RelayWebSocketListener] (a different
 * file) calls. Everything else here is file-private, since it's only ever called from within this
 * file.
 */

internal fun UmbraNostrClient.onWebSocketOpen(relayUrl: String, webSocket: WebSocket, response: Response) {
    // Mirrors onWebSocketClosed/onWebSocketFailure's own webSockets.remove(relayUrl, webSocket)
    // conditional check — this socket may have already been superseded (closeRelaySocket() removes
    // the map entry and calls close() on the old socket *before* the new one is dialed, but OkHttp
    // doesn't guarantee an in-flight handshake's onOpen callback is cancelled just because close()
    // was called on it) by a newer connect() for the same relayUrl. Without this, a stale socket
    // completing its handshake late would still get treated as authoritative: marked active,
    // emitted as its own CONNECTED issue, and reset this relay's failure/backoff state — even
    // though it isn't the socket actually in use. Close it and bail rather than acting on it.
    if (webSockets[relayUrl] !== webSocket) {
        logger.d { "Ignoring open for superseded socket: ${scrubUrlForLogs(relayUrl)}" }
        webSocket.close(1000, "Superseded by newer connection")
        return
    }
    logger.d { "WebSocket opened for ${scrubUrlForLogs(relayUrl)}: ${response.code}" }
    // A fresh socket has no memory of anything previously sent to the old one — clear before
    // anything downstream (relayOpenedFlow collectors, including a channel reapply) could
    // possibly call applyChannel() for this relay, so that reapply is guaranteed to actually
    // resend rather than being skipped as a stale no-op.
    subscriptions.clearFingerprint(relayUrl)
    intentionalDisconnects.remove(relayUrl)
    activeRelayUrls.add(relayUrl)
    publishConnectedRelaySnapshot()
    _relayOpenedFlow.tryEmit(relayUrl)
    // Emit a connected/info message so the UI can show a green success message
    emitRelayIssue(relayUrl = relayUrl, kind = RelayIssueKind.CONNECTED, message = "Connected to relay")
    // Reset unconditionally on every successful open. A previous revision only reset this
    // after the connection proved "stable" (open >=60s) to avoid a fast reconnect loop against
    // an unhealthy relay — in practice, real Tor-routed relay connections commonly drop well
    // under 60s for reasons that have nothing to do with relay health (circuit rotation,
    // transient congestion), so that gate left the backoff stuck climbing to its 5-minute
    // ceiling and never recovering — most relays sitting in cooldown with no active
    // subscriptions most of the time. Reverted: a relay that reconnects at all deserves a
    // fresh fast retry cadence.
    relayFailureCount.remove(relayUrl)
    relayCooldownUntil.remove(relayUrl)
    socksRetryCount.remove(relayUrl)
    orbotWaitRetryCount.remove(relayUrl)
    if (torCircuitHealthTracker.recordSuccess()) {
        // This success ends a confirmed TOR_CIRCUITS_LIKELY_DEAD episode — direct proof the
        // shared transport works again, so forgive every OTHER relay's accumulated backoff
        // too rather than making each one independently wait out its own window to rediscover
        // the same thing. NostrSessionManager reacts to this issue by calling
        // resetAllBackoff() and immediately retrying every relay that needs it.
        emitRelayIssue(
            relayUrl = "",
            kind = RelayIssueKind.TOR_CIRCUITS_RECOVERED,
            message = "Tor circuits recovered — reconnecting relays"
        )
    }
}

private fun UmbraNostrClient.recordCircuitFailureIfTorReady() {
    if (!TorProxyConfig.isReady) return
    if (torCircuitHealthTracker.recordFailure(System.currentTimeMillis())) {
        emitRelayIssue(
            relayUrl = "",
            kind = RelayIssueKind.TOR_CIRCUITS_LIKELY_DEAD,
            message = "Tor reports ready, but connections keep failing across multiple relays."
        )
    }
}

/**
 * Parses and dispatches one raw relay text frame. `suspend` (not a plain callback) because
 * [dispatchEventMessage] now emits into `_eventFlow` directly instead of spawning its own child
 * coroutine per event — see that function's doc comment. Called from [RelayWebSocketListener]'s
 * per-connection drain coroutine, never from the OkHttp reader thread directly.
 */
internal suspend fun UmbraNostrClient.onWebSocketMessage(relayUrl: String, text: String) {
    try {
        // Fast path: skip the full JSON parse entirely for a duplicate EVENT frame — the same
        // event commonly arrives once per matching subscription AND once per connected relay.
        // See scanEventFrame's doc comment for the safety rules; any ambiguity falls through
        // to the normal full parse below.
        val scanned = scanEventFrame(text)
        if (scanned != null) {
            val cached = recentEventsById.get(scanned.eventId)
            if (cached != null) {
                dispatchEventMessage(relayUrl, scanned.subId, cached)
                return
            }
        }

        val element = JsonUtils.NostrJson.parseToJsonElement(text)
        val jsonArray = element as? JsonArray
        if (jsonArray == null || jsonArray.isEmpty()) return

        val messageType = (jsonArray[0] as? JsonPrimitive)?.content

        when (messageType) {
            "EVENT" -> handleEventMessage(relayUrl, jsonArray)
            "COUNT" -> handleCountMessage(relayUrl, jsonArray)
            "NOTICE" -> handleNoticeMessage(relayUrl, jsonArray)
            "OK" -> handleOkMessage(relayUrl, jsonArray)
            "CLOSED" -> handleClosedMessage(relayUrl, jsonArray)
            "AUTH" -> handleAuthMessage(relayUrl, jsonArray)
            "EOSE" -> handleEoseMessage(relayUrl, jsonArray)
            "NEG-MSG" -> handleNegMsgMessage(relayUrl, jsonArray)
            "NEG-ERR" -> handleNegErrMessage(relayUrl, jsonArray)
            else -> handleUnknownMessage(relayUrl, messageType)
        }
    } catch (e: Exception) {
        logger.d { "Error processing message from ${scrubUrlForLogs(relayUrl)}: ${scrubThrowableMessageForLogs(e)}" }
    }
}

private suspend fun UmbraNostrClient.handleEventMessage(relayUrl: String, jsonArray: JsonArray) {
    if (jsonArray.size < 3) return
    val subscriptionId = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content.orEmpty()
    val eventJson = jsonArray[2] as? JsonObject ?: return

    // A cache hit reuses the already-built Event instead of re-parsing tags/fields for a
    // duplicate delivery — every downstream call below still runs exactly as it would for a
    // first-time delivery (same emit/bookkeeping), just fed the reused object.
    val eventId = (eventJson["id"] as? JsonPrimitive)?.content
    val event = if (eventId.isNullOrBlank()) {
        Event.fromJsonObject(eventJson)
    } else {
        recentEventsById.getOrPut(eventId) { Event.fromJsonObject(eventJson) }
    }

    dispatchEventMessage(relayUrl, subscriptionId, event)
}

/**
 * Directly suspends into `_eventFlow.emit` instead of spawning a `clientScope.launch { ... }`
 * child coroutine per event (as this used to). That old pattern gave no ordering guarantee across
 * concurrent launches for the same relay; since this is now called from
 * [RelayWebSocketListener]'s single per-connection drain coroutine — itself fed by OkHttp's own
 * in-order `onMessage` callbacks for that socket — emitting directly here means events for one
 * relay are now delivered to `_eventFlow` strictly in the order OkHttp delivered them. A
 * correctness improvement, not just a refactor.
 */
private suspend fun UmbraNostrClient.dispatchEventMessage(relayUrl: String, subscriptionId: String, event: Event) {
    _eventFlow.emit(relayUrl to event)
    if (subscriptionId.isNotBlank()) {
        _subscriptionEventFlow.tryEmit(relayUrl to subscriptionId)
    }
    val count = (relayEventCounters[relayUrl] ?: 0) + 1
    relayEventCounters[relayUrl] = count
    if (count % UmbraNostrClient.RELAY_ACTIVITY_LOG_EVERY == 0) {
        logger.d { "Raw relay traffic for ${scrubUrlForLogs(relayUrl)}: $count events across all active subscriptions" }
    }
}

private fun UmbraNostrClient.handleNoticeMessage(relayUrl: String, jsonArray: JsonArray) {
    val notice = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content
    logger.d { "Notice from ${scrubUrlForLogs(relayUrl)}: ${scrubMessageForLogs(notice)}" }
    if (!notice.isNullOrBlank()) {
        val kind = classifyNotice(notice)
        markIfSubscriptionLimited(relayUrl, kind)
        markIfNegentropyUnsupported(relayUrl, kind)
        applyThrottleIfNeeded(relayUrl, kind)
        emitRelayIssue(relayUrl = relayUrl, kind = kind, message = notice)
    }
}

private fun UmbraNostrClient.handleCountMessage(relayUrl: String, jsonArray: JsonArray) {
    if (jsonArray.size < 3) return
    val subscriptionId = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content.orEmpty()
    val payload = jsonArray.getOrNull(2) as? JsonObject ?: return
    val count = (payload["count"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
    val approximate = (payload["approximate"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    _countFlow.tryEmit(
        RelayCountResult(
            relayUrl = relayUrl,
            subscriptionId = subscriptionId,
            count = count,
            approximate = approximate
        )
    )
}

private fun UmbraNostrClient.handleOkMessage(relayUrl: String, jsonArray: JsonArray) {
    val eventId = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content.orEmpty()
    val accepted = (jsonArray.getOrNull(2) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    val message = (jsonArray.getOrNull(3) as? JsonPrimitive)?.content.orEmpty()
    _publishResultFlow.tryEmit(RelayPublishResult(relayUrl = relayUrl, eventId = eventId, accepted = accepted, message = message))
    if (!accepted) {
        val kind = classifyNotice(message.ifBlank { "event rejected" })
        val isAuthRequired = message.lowercase().startsWith("auth-required:")
        // If relay says auth-required and we have a stored challenge, re-emit that challenge
        // so the upper layer can respond with a proper AUTH event.
        if (isAuthRequired) {
            val storedChallenge = storedAuthChallenges[relayUrl]
            if (!storedChallenge.isNullOrBlank()) {
                emitRelayIssue(
                    relayUrl = relayUrl,
                    kind = RelayIssueKind.AUTH,
                    message = storedChallenge,
                    isAuthChallenge = true
                )
                return
            }
        }
        applyThrottleIfNeeded(relayUrl, kind)
        emitRelayIssue(
            relayUrl = relayUrl,
            kind = kind,
            message = "Relay rejected event ${eventId.take(8)}: ${message.ifBlank { "no reason" }}"
        )
    }
}

private fun UmbraNostrClient.handleClosedMessage(relayUrl: String, jsonArray: JsonArray) {
    val subId = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content.orEmpty()
    val reason = (jsonArray.getOrNull(2) as? JsonPrimitive)?.content.orEmpty()
    val isAuthRequired = reason.lowercase().startsWith("auth-required:")
    // If relay demands auth and we have a stored challenge (received via ["AUTH"] frame),
    // re-emit that challenge so the upper layer can respond with a proper AUTH event.
    if (isAuthRequired) {
        val storedChallenge = storedAuthChallenges[relayUrl]
        if (!storedChallenge.isNullOrBlank()) {
            emitRelayIssue(
                relayUrl = relayUrl,
                kind = RelayIssueKind.AUTH,
                message = storedChallenge,
                isAuthChallenge = true
            )
            return
        }
    }
    if (isReqRejectionMessage(reason)) {
        relayReqUnsupported.add(relayUrl)
        logger.d { "${scrubUrlForLogs(relayUrl)} does not accept REQ — no longer querying it" }
        emitRelayIssue(
            relayUrl = relayUrl,
            kind = RelayIssueKind.REQ_UNSUPPORTED,
            message = reason.ifBlank { "This relay does not accept subscriptions" }
        )
        return
    }
    if (isSearchRequiredMessage(reason)) {
        relayRequiresSearch.add(relayUrl)
        logger.d { "${scrubUrlForLogs(relayUrl)} requires a search filter — withholding non-search REQs" }
        emitRelayIssue(
            relayUrl = relayUrl,
            kind = RelayIssueKind.SEARCH_REQUIRED,
            message = reason.ifBlank { "This relay only accepts search queries" }
        )
        return
    }
    if (isDuplicateSubscriptionMessage(reason)) {
        relayRejectsSubIdReuse.add(relayUrl)
        logger.d { "${scrubUrlForLogs(relayUrl)} rejects reused subscription ids — minting a fresh one on next reapply" }
        emitRelayIssue(
            relayUrl = relayUrl,
            kind = RelayIssueKind.DUPLICATE_SUBSCRIPTION,
            message = reason.ifBlank { "This relay rejected a reused subscription id" }
        )
        return
    }
    val kind = classifyNotice(reason.ifBlank { "subscription closed" })
    markIfSubscriptionLimited(relayUrl, kind)
    applyThrottleIfNeeded(relayUrl, kind)
    emitRelayIssue(
        relayUrl = relayUrl,
        kind = kind,
        message = "Subscription closed ($subId): ${reason.ifBlank { "no reason" }}"
    )
}

private fun UmbraNostrClient.handleAuthMessage(relayUrl: String, jsonArray: JsonArray) {
    val challenge = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content.orEmpty().trim()
    if (challenge.isBlank()) return
    // Store as the active challenge for this relay (replaces any previous one per NIP-42)
    storedAuthChallenges[relayUrl] = challenge
    emitRelayIssue(
        relayUrl = relayUrl,
        kind = RelayIssueKind.AUTH,
        message = challenge,
        isAuthChallenge = true
    )
}

private fun UmbraNostrClient.handleEoseMessage(relayUrl: String, jsonArray: JsonArray) {
    val subscriptionId = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content
    // NIP-67: optional third element, "finish" | "more" | absent — see parseEoseCompleteness.
    val completeness = parseEoseCompleteness((jsonArray.getOrNull(2) as? JsonPrimitive)?.content)
    logger.d { "End of stored events from ${scrubUrlForLogs(relayUrl)} for: $subscriptionId ($completeness)" }
    if (!subscriptionId.isNullOrBlank()) {
        _eoseFlow.tryEmit(EoseSignal(relayUrl, subscriptionId, completeness))
    }
}

private fun UmbraNostrClient.handleNegMsgMessage(relayUrl: String, jsonArray: JsonArray) {
    val subscriptionId = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content
    val messageHex = (jsonArray.getOrNull(2) as? JsonPrimitive)?.content
    if (subscriptionId.isNullOrBlank() || messageHex.isNullOrBlank()) return
    _negMessageFlow.tryEmit(NegSignal.Msg(relayUrl, subscriptionId, messageHex))
}

private fun UmbraNostrClient.handleNegErrMessage(relayUrl: String, jsonArray: JsonArray) {
    val subscriptionId = (jsonArray.getOrNull(1) as? JsonPrimitive)?.content
    val reason = (jsonArray.getOrNull(2) as? JsonPrimitive)?.content.orEmpty()
    if (subscriptionId.isNullOrBlank()) return
    logger.d { "NEG-ERR from ${scrubUrlForLogs(relayUrl)} for $subscriptionId: ${scrubMessageForLogs(reason)}" }
    _negMessageFlow.tryEmit(NegSignal.Err(relayUrl, subscriptionId, reason))
}

private fun UmbraNostrClient.handleUnknownMessage(relayUrl: String, messageType: String?) {
    logger.d { "Unknown message type from ${scrubUrlForLogs(relayUrl)}: $messageType" }
}

internal fun UmbraNostrClient.onWebSocketClosing(relayUrl: String, webSocket: WebSocket, code: Int, reason: String) {
    logger.d { "WebSocket closing for ${scrubUrlForLogs(relayUrl)}: $code ${scrubMessageForLogs(reason)}" }
    webSocket.close(1000, null)
}

internal fun UmbraNostrClient.onWebSocketClosed(relayUrl: String, webSocket: WebSocket, code: Int, reason: String) {
    logger.d { "WebSocket closed for ${scrubUrlForLogs(relayUrl)}: $code ${scrubMessageForLogs(reason)}" }
    val removedCurrentSocket = webSockets.remove(relayUrl, webSocket)
    if (removedCurrentSocket) {
        activeRelayUrls.remove(relayUrl)
        publishConnectedRelaySnapshot()
    }
    // Challenge is only valid for the lifetime of the connection (NIP-42)
    storedAuthChallenges.remove(relayUrl)

    val intentionalClose = intentionalDisconnects.remove(relayUrl)
    if (!intentionalClose && removedCurrentSocket) {
        recordCircuitFailureIfTorReady()
        emitRelayIssue(relayUrl = relayUrl, kind = RelayIssueKind.NETWORK, message = "Relay closed connection unexpectedly.")
        recordFailureAndScheduleReconnect(relayUrl) { connect(relayUrl) }
    }
}

internal fun UmbraNostrClient.onWebSocketFailure(relayUrl: String, webSocket: WebSocket, t: Throwable, response: Response?) {
    val isSocksError = isSocksFailure(t)
    logWebSocketFailure(relayUrl, t, response, isSocksError)
    val removedCurrentSocket = webSockets.remove(relayUrl, webSocket)
    if (removedCurrentSocket) {
        activeRelayUrls.remove(relayUrl)
        publishConnectedRelaySnapshot()
    }

    val intentionalClose = intentionalDisconnects.remove(relayUrl)
    if (intentionalClose || !removedCurrentSocket) {
        return
    }

    val kind = when {
        response?.code == 429 -> RelayIssueKind.RATE_LIMIT
        response?.code == 401 || response?.code == 403 -> RelayIssueKind.AUTH
        t.message?.contains("SSL", ignoreCase = true) == true || t.message?.contains("TLS", ignoreCase = true) == true -> RelayIssueKind.TLS
        t.message?.contains("CLEARTEXT", ignoreCase = true) == true -> RelayIssueKind.CLEARTEXT_BLOCKED
        isSocksError -> RelayIssueKind.NETWORK
        else -> RelayIssueKind.NETWORK
    }

    val responseInfo = response?.code?.let { " (HTTP $it)" } ?: ""
    val errorMsg = "WebSocket failure${responseInfo}: ${t.message ?: "unknown"}"

    // Deterministic policy-level block (network security config denies cleartext to this
    // host) — retrying can never succeed without a config/relay-URL change, so fast-path
    // straight to AUTO_DISABLED instead of exhausting the normal retry/backoff ladder.
    if (kind == RelayIssueKind.CLEARTEXT_BLOCKED) {
        emitRelayIssue(relayUrl = relayUrl, kind = RelayIssueKind.CLEARTEXT_BLOCKED, message = errorMsg)
        emitRelayIssue(relayUrl = relayUrl, kind = RelayIssueKind.AUTO_DISABLED, message = "Relay blocked by cleartext policy and was disabled.")
        return
    }

    emitRelayIssue(relayUrl = relayUrl, kind = kind, message = errorMsg)
    // Only a plain connectivity failure counts toward the circuit-health streak — a relay
    // actively responding with rate-limit/auth/TLS specifics means it's reachable, just
    // uncooperative, which isn't evidence of dead Tor circuits.
    if (kind == RelayIssueKind.NETWORK) {
        recordCircuitFailureIfTorReady()
    }

    if (isSocksError) {
        val current = (socksRetryCount[relayUrl] ?: 0) + 1
        socksRetryCount[relayUrl] = current
        val max = if (relayUrl.contains(".onion")) 4 else 2
        if (current <= max) {
            clientScope.launch {
                delay(1000L)
                connect(relayUrl)
            }
            return
        }
    }

    recordFailureAndScheduleReconnect(relayUrl) { connect(relayUrl) }
}

/**
 * No NIP-11 field advertises "this relay doesn't accept REQ at all" (nosflare's broadcast-only
 * "sendit" variant, for example, still lists NIP-01 in supported_nips) — the only way to learn
 * it is a CLOSED response to a REQ whose reason says so, e.g.
 * `["CLOSED", subId, "restricted: this relay does not accept REQs"]`.
 */
private fun isReqRejectionMessage(reason: String): Boolean {
    val r = reason.lowercase()
    val mentionsReqOrSub = r.contains("req") || r.contains("subscription")
    val mentionsRejection = r.contains("not accept") || r.contains("not support") ||
        r.contains("not allowed") || r.contains("not permitted")
    return (mentionsReqOrSub && mentionsRejection) ||
        r.contains("write-only") || r.contains("write only") ||
        r.contains("broadcast-only") || r.contains("broadcast only")
}

/**
 * Search-only relays (searchnos, for example) close any REQ lacking a `search` field with a
 * reason like `["CLOSED", subId, "error: search filter is required"]` — distinct from
 * [isReqRejectionMessage] since these relays do accept REQs, just only ones carrying a search term.
 */
private fun isSearchRequiredMessage(reason: String): Boolean {
    val r = reason.lowercase()
    return r.contains("search") && (r.contains("required") || r.contains("must") || r.contains("mandatory"))
}

/**
 * A relay closing a REQ specifically because it considers the subscription_id a duplicate —
 * e.g. `["CLOSED", subId, "duplicate: subscription id already open"]`. See
 * [UmbraNostrClient.relayRejectsSubIdReuse]'s doc comment for why this needs its own sticky
 * tracking rather than falling through to the generic NOTICE classification.
 */
private fun isDuplicateSubscriptionMessage(reason: String): Boolean {
    val r = reason.lowercase()
    return r.contains("duplicate") && (r.contains("sub") || r.contains("req"))
}
