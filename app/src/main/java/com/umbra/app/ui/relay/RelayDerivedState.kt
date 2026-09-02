package com.umbra.app.ui.relay

import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.normalizeRelayUrl

enum class RelayConnectionIndicatorState {
    CONNECTED,
    CONNECTING,
    FAILED,
    // Relay has no active role (isEnabled/hasAnyActiveRole false) — not attempting to
    // connect at all, so CONNECTED/CONNECTING/FAILED (all implying a live connection
    // attempt) would misrepresent it, especially with a stale CONNECTED/CONNECTING issue
    // left over from before the user disabled it.
    DISABLED
}

private val RELAY_CONNECTION_FAILURE_KINDS = setOf(
    RelayIssueKind.RATE_LIMIT,
    RelayIssueKind.AUTH,
    RelayIssueKind.BLOCKED,
    RelayIssueKind.NETWORK,
    RelayIssueKind.TLS,
    RelayIssueKind.CLEARTEXT_BLOCKED
)

internal fun resolveRelayConnectionIndicatorState(
    relayUrl: String,
    isEnabled: Boolean,
    connectedRelayUrls: Set<String>,
    relayIssues: List<RelayIssue>
): RelayConnectionIndicatorState {
    val normalizedRelayUrl = normalizeRelayUrl(relayUrl)

    if (!isEnabled) {
        // Disabled relays can still be connected momentarily while a disconnect is in
        // flight, or leave a stale issue behind — isEnabled wins regardless, since it
        // reflects what the user actually asked for.
        return RelayConnectionIndicatorState.DISABLED
    }

    if (normalizedRelayUrl in connectedRelayUrls) {
        return RelayConnectionIndicatorState.CONNECTED
    }

    val latestIssue = relayIssues.lastOrNull {
        normalizeRelayUrl(it.relayUrl) == normalizedRelayUrl
    }

    return if (latestIssue != null && latestIssue.kind in RELAY_CONNECTION_FAILURE_KINDS) {
        RelayConnectionIndicatorState.FAILED
    } else {
        RelayConnectionIndicatorState.CONNECTING
    }
}

internal data class RelayDerivedStateInputs(
    val relays: List<Relay>,
    val connectedRelayUrls: Set<String>,
    val relayIssues: List<RelayIssue>,
    val relayRequests: List<RelayRequestInfo>
)

internal data class RelayDerivedState(
    val buckets: RelayBuckets,
    val connectionStates: Map<String, RelayConnectionIndicatorState>,
    val telemetry: RelayTelemetrySnapshot
)

/**
 * Computes [RelayBuckets], per-relay [RelayConnectionIndicatorState], and [RelayTelemetrySnapshot]
 * together from the same [RelayDerivedStateInputs] in a single pass, deliberately not split into
 * three separate functions — all three outputs are derived from the same four inputs so they
 * cannot drift apart from each other (e.g. telemetry.active disagreeing with buckets.active).
 * Pure and side-effect free: safe to call directly from a unit test with no ViewModel/Flow
 * dependency.
 */
internal fun computeRelayDerivedState(inputs: RelayDerivedStateInputs): RelayDerivedState {
    val normalizedConnectedRelayUrls = inputs.connectedRelayUrls.mapTo(mutableSetOf()) { normalizeRelayUrl(it) }

    val outbox = mutableListOf<Relay>()
    val inbox = mutableListOf<Relay>()
    val dm = mutableListOf<Relay>()
    val search = mutableListOf<Relay>()
    val index = mutableListOf<Relay>()
    val discoveredConnected = mutableListOf<Relay>()
    val discoveredOther = mutableListOf<Relay>()
    val discoveredDisabled = mutableListOf<Relay>()
    var active = 0
    var connectedNow = 0

    inputs.relays.forEach { relay ->
        val normalizedRelayUrl = normalizeRelayUrl(relay.url)
        if (relay.isDiscovered) {
            if (!relay.isEnabled) {
                discoveredDisabled += relay
            } else if (normalizedRelayUrl in normalizedConnectedRelayUrls) {
                discoveredConnected += relay
            } else {
                discoveredOther += relay
            }
        } else {
            if (relay.isWriteEnabled) outbox += relay
            if (relay.isReadEnabled) inbox += relay
            if (relay.isDmEnabled) dm += relay
            if (relay.isSearchEnabled) search += relay
            if (relay.isIndexEnabled) index += relay
        }
        if (relay.isEnabled) active += 1
        if (normalizedRelayUrl in normalizedConnectedRelayUrls) connectedNow += 1
    }

    val buckets = RelayBuckets(
        outbox = outbox,
        inbox = inbox,
        dm = dm,
        search = search,
        index = index,
        discoveredConnected = discoveredConnected,
        discoveredOther = discoveredOther,
        discoveredDisabled = discoveredDisabled,
        active = active,
        connectedNow = connectedNow
    )

    val connectionStates = inputs.relays.associate { relay ->
        normalizeRelayUrl(relay.url) to resolveRelayConnectionIndicatorState(
            relayUrl = relay.url,
            isEnabled = relay.isEnabled,
            connectedRelayUrls = normalizedConnectedRelayUrls,
            relayIssues = inputs.relayIssues
        )
    }

    val telemetry = RelayTelemetrySnapshot(
        configured = inputs.relays.size,
        active = buckets.active,
        connectedNow = buckets.connectedNow,
        liveSubscriptions = inputs.relayRequests.size,
        totalReceivedEvents = inputs.relayRequests.sumOf { it.receivedEventCount },
        nonConnectedIssues = inputs.relayIssues.count { it.kind != RelayIssueKind.CONNECTED }
    )

    return RelayDerivedState(buckets, connectionStates, telemetry)
}
