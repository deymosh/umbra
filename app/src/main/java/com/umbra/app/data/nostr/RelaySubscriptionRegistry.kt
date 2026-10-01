package com.umbra.app.data.nostr

import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.relay.randomSubscriptionId
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-relay channel<->subscriptionId lifecycle bookkeeping: subId minting/reuse, the stable
 * subId->channel history used to attribute wire-level frames back to Umbra's internal channel
 * concept, and the per-(relay, channel) no-op/reapply fingerprint dedup. Owned by
 * [UmbraNostrClient] as a plain field — every method here is synchronous, so this class has no
 * [kotlinx.coroutines.CoroutineScope] of its own.
 */
internal class RelaySubscriptionRegistry {

    companion object {
        // Bounds subIdHistory's per-relay history. Same numeric value as EventRepositoryImpl's
        // MAX_REQUESTS_PER_RELAY (which bounds a different, UI-facing collection) purely by
        // convention — the two bound unrelated collections that happen to agree on 30.
        private const val MAX_TRACKED_SUBSCRIPTIONS_PER_RELAY = 30
    }

    // relayUrl -> (channelId -> subId), forward, mutated in place on every reapply.
    private val currentSubIds = ConcurrentHashMap<String, MutableMap<String, String>>()

    // Stable (relayUrl, subId) -> channelId stamp, written once at mint time in getOrCreateSubId
    // and never overwritten afterward — unlike currentSubIds above, this is immune to a later
    // reapply moving the channel's current subId pointer. That matters for a relay flagged
    // rejectsSubIdReuse: every reapply of a high-churn channel mints a brand-new subId, so the OLD
    // subId is no longer present anywhere in currentSubIds even though wire traffic (REQ/EOSE) for
    // it may still be in flight — resolveChannelId's stable stamp lets that late frame still
    // resolve to the right channel. Bounded per relay via LinkedHashMap.removeEldestEntry;
    // deliberately never cleared by forgetRelay/resetAll — kept for "Active Subscriptions" UI
    // history even after teardown.
    private val subIdHistory = ConcurrentHashMap<String, LinkedHashMap<String, String>>()
    private val subIdHistoryLock = Any()

    // relayUrl -> (channelId -> fingerprint of the filters last successfully sent), written only
    // on a successful send — a withheld send (throttled, unsupported, ...) must never be cached
    // here, or a later retry once the withholding condition clears would be wrongly skipped as a
    // no-op.
    private val lastSentFingerprint = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** The subId CURRENTLY mapped to [channelId] on [relayUrl], or null if none. */
    fun currentSubId(relayUrl: String, channelId: String): String? = currentSubIds[relayUrl]?.get(channelId)

    /** Number of distinct channels currently mapped to a subscription on [relayUrl]. */
    fun channelCount(relayUrl: String): Int = currentSubIds[relayUrl]?.size ?: 0

    // Given a (relayUrl, subId) pair observed on the wire, finds which internal channelId
    // produced it — via the stable stamp recorded once at mint time (see subIdHistory's doc
    // comment), not a live reverse-scan of currentSubIds, which a later reapply can invalidate.
    fun resolveChannelId(relayUrl: String, subId: String): String? =
        synchronized(subIdHistoryLock) { subIdHistory[relayUrl]?.get(subId) }

    /** (relayUrl, subId) pairs for every relay currently tracking [channelId] in the forward map. */
    fun subscriptionsForChannel(channelId: String): Set<Pair<String, String>> =
        currentSubIds.entries.mapNotNullTo(mutableSetOf()) { (relayUrl, subs) -> subs[channelId]?.let { relayUrl to it } }

    fun getOrCreateSubId(relayUrl: String, channelId: String, rejectsSubIdReuse: Boolean): String {
        val perRelay = currentSubIds.getOrPut(relayUrl) { ConcurrentHashMap<String, String>() }
        if (rejectsSubIdReuse) {
            // This relay CLOSEs a REQ that reuses a subscription_id it's already seen instead of
            // the NIP-01-mandated silent filter replace — reusing the mapped id here would just
            // reproduce the exact complaint that got this relay flagged. Mint a fresh one every
            // time instead of reusing/caching it, so this relay never sees the same id twice.
            // Nothing to unsubscribe first — the relay already closed the old one itself; that's
            // how rejectsSubIdReuse got set.
            val freshId = randomSubscriptionId()
            perRelay[channelId] = freshId
            recordSubIdChannel(relayUrl, freshId, channelId)
            return freshId
        }
        return perRelay.getOrPut(channelId) {
            randomSubscriptionId()
        }.also { subId -> recordSubIdChannel(relayUrl, subId, channelId) }
    }

    private fun recordSubIdChannel(relayUrl: String, subId: String, channelId: String) {
        synchronized(subIdHistoryLock) {
            val perRelay = subIdHistory.getOrPut(relayUrl) {
                object : LinkedHashMap<String, String>(16, 0.75f, false) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                        size > MAX_TRACKED_SUBSCRIPTIONS_PER_RELAY
                }
            }
            perRelay[subId] = channelId
        }
    }

    /**
     * Atomically "check fingerprint and reserve it" in one step per (relayUrl, channelId):
     * returns true (and records [filters]' fingerprint as sent) only if they differ from what was
     * last sent, so two concurrent apply paths for the same pair can never both send an identical
     * REQ. Call it only after every withholding precondition has passed; if the send then doesn't
     * go out, call [rollbackReservation] so a later retry isn't treated as a no-op.
     */
    fun tryReserve(relayUrl: String, channelId: String, filters: List<EventFilter>): Boolean {
        val fingerprint = filterFingerprint(filters)
        var won = false
        lastSentFingerprintPerChannel(relayUrl).compute(channelId) { _, existing ->
            if (existing == fingerprint) {
                // Identical filters already sent — call is a genuine no-op, not a lost race.
                existing
            } else {
                won = true
                fingerprint
            }
        }
        return won
    }

    /**
     * Undoes a [tryReserve] reservation whose send never went out: clears the fingerprint (the next
     * reserve then always sends) unless a newer reservation already replaced it, which is kept.
     */
    fun rollbackReservation(relayUrl: String, channelId: String, filters: List<EventFilter>) {
        val fingerprint = filterFingerprint(filters)
        lastSentFingerprintPerChannel(relayUrl).compute(channelId) { _, existing ->
            if (existing == fingerprint) null else existing
        }
    }

    // Per-relay channel->fingerprint accessor shared by tryReserve and rollbackReservation.
    private fun lastSentFingerprintPerChannel(relayUrl: String): MutableMap<String, String> =
        lastSentFingerprint.getOrPut(relayUrl) { ConcurrentHashMap() }

    /** Removes [channelId]'s forward-map entry on [relayUrl], returning the removed subId if any. */
    fun remove(relayUrl: String, channelId: String): String? = currentSubIds[relayUrl]?.remove(channelId)

    /**
     * Clears [relayUrl]'s forward map and fingerprint cache — NOT its [subIdHistory], which is
     * kept for "Active Subscriptions" UI history even after this relay is torn down.
     */
    fun forgetRelay(relayUrl: String) {
        currentSubIds.remove(relayUrl)
        lastSentFingerprint.remove(relayUrl)
    }

    /** Clears every relay's forward map and fingerprint cache at once — NOT [subIdHistory]. */
    fun resetAll() {
        currentSubIds.clear()
        lastSentFingerprint.clear()
    }

    /**
     * Clears [relayUrl]'s fingerprint cache only — called when a fresh socket opens for it (see
     * [UmbraNostrClient.onWebSocketOpen]): a new socket has no memory of what filters were sent to
     * the old one, so the next [tryReserve] for it must not treat identical filters as a
     * no-op.
     */
    fun clearFingerprint(relayUrl: String) {
        lastSentFingerprint.remove(relayUrl)
    }
}
