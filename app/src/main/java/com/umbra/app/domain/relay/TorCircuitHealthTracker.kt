package com.umbra.app.domain.relay

/**
 * Detects "Orbot reports Tor is Active, but every actual connection attempt is failing" — a state
 * invisible to ordinary Tor-readiness checks, which only ask "is Tor ready," not "are connections
 * actually succeeding" (stale consensus, exit-node exhaustion, etc.). Umbra has no Orbot
 * control-port access, so there's no automatic fix —
 * this only powers a diagnosis surfaced to the user (see RelayIssueKind.TOR_CIRCUITS_LIKELY_DEAD)
 * so they know to restart Orbot themselves, instead of seeing an unexplained wall of per-relay
 * network errors with no obvious common cause.
 *
 * Only meaningful while Tor itself reports ready — callers should gate calls to [recordFailure] on
 * that condition; a failure streak while Tor is not ready is already expected/handled elsewhere,
 * not this diagnosis. This class doesn't know about Tor readiness itself.
 */
class TorCircuitHealthTracker(
    private val failureStreakThreshold: Int = 8,
    private val minSpanMs: Long = 30_000L
) {
    private var streakCount = 0
    private var streakStartedAtMillis: Long = 0L
    private var alreadySignaled = false

    /**
     * Records a connection failure at [nowMillis]. Returns true the first time the streak crosses
     * both the count and time-span thresholds — a one-shot trigger; call [recordSuccess] to allow
     * it to fire again on a later streak.
     */
    @Synchronized
    fun recordFailure(nowMillis: Long): Boolean {
        if (streakCount == 0) {
            streakStartedAtMillis = nowMillis
        }
        streakCount++
        if (alreadySignaled) return false

        val spanMs = nowMillis - streakStartedAtMillis
        val crossed = streakCount >= failureStreakThreshold && spanMs >= minSpanMs
        if (crossed) {
            alreadySignaled = true
        }
        return crossed
    }

    /**
     * Any successful connection resets the streak — circuits are demonstrably working again.
     * Returns true if this success ends a streak that had already crossed the threshold and
     * fired [recordFailure]'s signal — i.e. this isn't just "one relay happened to connect," it's
     * proof recovery from a confirmed likely-dead-circuits episode, which callers can use to
     * decide whether it's worth forgiving every other relay's accumulated backoff too (see
     * UmbraNostrClient.resetAllBackoff).
     */
    @Synchronized
    fun recordSuccess(): Boolean {
        val wasSignaled = alreadySignaled
        streakCount = 0
        streakStartedAtMillis = 0L
        alreadySignaled = false
        return wasSignaled
    }
}
