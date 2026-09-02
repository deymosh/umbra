package com.umbra.app.data.nostr

/**
 * Delay for the Nth (1-indexed) retry while waiting for Orbot's SOCKS proxy to become available —
 * kept separate from the per-relay failure backoff ladder since this isn't the relay's fault and
 * shouldn't count against it. A flat, ungated retry would otherwise dial every enabled relay every
 * second for as long as Orbot stays down — this backoff exists specifically to avoid that
 * "hammering a dead proxy" pattern.
 */
internal fun orbotWaitDelayMs(attemptNumber: Int, delays: LongArray): Long {
    val index = (attemptNumber - 1).coerceAtLeast(0)
    return if (index < delays.size) delays[index] else delays.last()
}

/**
 * True for a connection failure that's actually Orbot's local SOCKS proxy refusing/dropping the
 * connection (e.g. Orbot restarting or between states) rather than the destination relay being
 * unreachable. A failed TCP connect specifically to the proxy's own host:port always names that
 * address in the exception message; a failure reaching a remote relay through an already-working
 * proxy never does. This shouldn't count against the *relay's* own failure tracking either way.
 */
internal fun isLocalProxyRefusal(message: String?, proxyHost: String): Boolean {
    if (message.isNullOrBlank() || proxyHost.isBlank()) return false
    return message.contains(proxyHost, ignoreCase = true)
}
