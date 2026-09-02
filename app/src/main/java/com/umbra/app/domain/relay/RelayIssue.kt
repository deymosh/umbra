package com.umbra.app.domain.relay

/**
 * Normalized relay issue emitted from the websocket layer.
 * Helps the UI present consistent messages for rate limits, auth, and network failures.
 */
data class RelayIssue(
    val relayUrl: String,
    val kind: RelayIssueKind,
    val rawMessage: String,
    val cooldownSeconds: Long? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    /**
     * True only for a real NIP-42 ["AUTH", challenge] frame from the relay.
     * False for auth-required errors inside CLOSED/OK/NOTICE — those must NOT
     * be used as challenges; they should trigger re-use of the stored challenge.
     */
    val isAuthChallenge: Boolean = false
)

enum class RelayIssueKind {
    RATE_LIMIT,
    AUTH,
    BLOCKED,
    // Emitted right when a dial attempt starts (before the handshake resolves either way) — a
    // transient "in progress" state, not an error. See UmbraNostrClient.connect().
    CONNECTING,
    CONNECTED,
    NOTICE,
    NETWORK,
    TLS,
    // Android's network security config (network_security_config.xml) blocks ws:// to any
    // non-onion, non-localhost host — the exception message contains "CLEARTEXT". Deterministic:
    // no retry will ever succeed without a config or relay-URL change, so this fast-paths straight
    // to AUTO_DISABLED instead of exhausting RECONNECT_DELAYS_MS/MAX_CONSECUTIVE_FAILURES_BEFORE_AUTO_DISABLE.
    CLEARTEXT_BLOCKED,
    // Relay closes every REQ with a rejection (e.g. nosflare's broadcast-only "sendit" variant),
    // distinct from AUTH so it isn't mistaken for a NIP-42 challenge and doesn't get retried.
    REQ_UNSUPPORTED,
    // Relay closes any REQ that lacks a NIP-50 `search` field (e.g. searchnos), the mirror image
    // of REQ_UNSUPPORTED: not "never send REQ" but "only send REQ when it carries a search term."
    SEARCH_REQUIRED,
    // Relay is over its concurrent-subscription count (distinct from RATE_LIMIT, which is about
    // request *rate* — this is about how many can be open at once). See ChannelPriority: Umbra
    // responds by withholding non-essential channels from this relay rather than repeating the
    // same channel count and getting closed again.
    SUBSCRIPTION_LIMIT,
    // Relay rejects a REQ that reuses an already-open subscription_id as a "duplicate" instead of
    // the NIP-01-mandated silent filter replace. Umbra responds by no longer reusing a mapped
    // subId for this relay's channels — see UmbraNostrClient.relayRejectsSubIdReuse.
    DUPLICATE_SUBSCRIPTION,
    // Relay's NIP-11 document advertised NIP-77 support but it's actually disabled at runtime
    // (e.g. strfry with negentropy compiled in but turned off in config) — learned only from a
    // generic NOTICE rejecting the NEG-OPEN, since NIP-77 has no CLOSED-style per-subscription
    // rejection for "I don't recognize/allow this command" the way REQ does. Umbra responds by no
    // longer attempting NIP-77 sync against this relay for the rest of the session — see
    // UmbraNostrClient.relayNegentropyUnsupported.
    NEGENTROPY_UNSUPPORTED,
    // Relay-agnostic (relayUrl is blank): Tor reports itself ready, but connections keep failing
    // across multiple relays — see TorCircuitHealthTracker. Diagnostic only; there's no control-
    // port access to force new circuits, so this just tells the user to restart Orbot themselves.
    TOR_CIRCUITS_LIKELY_DEAD,
    // Relay-agnostic (relayUrl is blank): a relay just connected successfully after a confirmed
    // TOR_CIRCUITS_LIKELY_DEAD episode — direct proof the shared Tor transport works again. Signals
    // NostrSessionManager to forgive every other relay's accumulated backoff and immediately
    // retry them, instead of each independently waiting out its own (possibly still long) window
    // to rediscover the same thing. See UmbraNostrClient.resetAllBackoff.
    TOR_CIRCUITS_RECOVERED,
    // Emitted once, the moment a relay's consecutive connect-failure count hits
    // UmbraNostrClient.MAX_CONSECUTIVE_FAILURES_BEFORE_AUTO_DISABLE — a signal for
    // NostrSessionManager to flip that relay's isEnabled to false so a genuinely dead relay
    // doesn't sit in the pool retrying (at a 5-minute cadence, per RECONNECT_DELAYS_MS) forever.
    // Re-enabling the relay (RelayConfigViewModel) resets the failure count, so it gets a fresh
    // run at the same threshold rather than immediately re-tripping on its very next failure.
    AUTO_DISABLED,
    UNKNOWN
}


