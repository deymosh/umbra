package com.umbra.app.data.nostr

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.nip67.EoseSignal
import com.umbra.app.domain.nip77.NegSignal
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayPublishResult
import com.umbra.app.domain.relay.RelayRequestInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Protocol client abstraction for Nostr transport.
 *
 * Keeping this interface separate from repositories makes the transport layer
 * swappable (mock/fake client for tests, alternate socket implementation, etc.).
 */
interface NostrClient {
    /** Emits (relayUrl, event) for every delivered EVENT — relayUrl carries which relay this
     * particular delivery came from, so callers can track per-event relay provenance. */
    val eventFlow: SharedFlow<Pair<String, Event>>
    val reqFlow: SharedFlow<RelayRequestInfo>
    val subscriptionEventFlow: SharedFlow<Pair<String, String>>
    /** Emits an [EoseSignal] each time a relay sends EOSE (NIP-01: end of stored events for that
     * REQ — everything after is live/streaming, not backfill). [EoseSignal.completeness] carries
     * the optional NIP-67 completeness hint, or [com.umbra.app.domain.nip67.EoseCompleteness.UNSPECIFIED]
     * for the (overwhelmingly common) relay that doesn't send one. */
    val eoseFlow: SharedFlow<EoseSignal>
    val countFlow: SharedFlow<RelayCountResult>
    /** Emits a [NegSignal] each time a relay sends NEG-MSG or NEG-ERR for a NIP-77 sync — see
     * [negOpen]. */
    val negMessageFlow: SharedFlow<NegSignal>
    val relayIssueFlow: SharedFlow<RelayIssue>
    val connectedRelayUrlsFlow: StateFlow<Set<String>>
    /** Emits the relay URL each time a WebSocket handshake completes (onOpen). */
    val relayOpenedFlow: SharedFlow<String>
    /**
     * Emits every relay's ["OK", eventId, accepted, message] response to a published event —
     * both acceptances and rejections, unlike [relayIssueFlow] which only ever carries problems.
     * Consumed by the broadcast tracker to show per-relay publish status.
     */
    val publishResultFlow: SharedFlow<RelayPublishResult>

    fun connect(relayUrl: String): Result<Unit>
    fun subscribe(relayUrl: String, subscriptionId: String = "default", filters: List<EventFilter>)
    fun requestCount(relayUrl: String, subscriptionId: String = "default", filters: List<EventFilter>)
    /** NIP-77: opens a Negentropy sync — [initialMessageHex] is the initiator's first reconciliation
     * message. Replies arrive on [negMessageFlow]. */
    fun negOpen(relayUrl: String, subscriptionId: String, filter: EventFilter, initialMessageHex: String)
    /** NIP-77: sends the next round of an already-open Negentropy sync. */
    fun negMsg(relayUrl: String, subscriptionId: String, messageHex: String)
    /** NIP-77: signals the client is done with a sync, letting the relay reclaim resources. */
    fun negClose(relayUrl: String, subscriptionId: String)
    fun publishEvent(relayUrl: String, event: Event)
    fun publishAuthEvent(relayUrl: String, event: Event)
    suspend fun publishEvent(event: Event)
    suspend fun publishEventToRelays(event: Event, relayUrls: List<String>)
    fun unsubscribe(relayUrl: String, subscriptionId: String)
    fun disconnect(relayUrl: String)
    /**
     * Disconnects [relayUrl] and clears every bit of per-relay bookkeeping for it — the
     * "this relay is leaving the pool for good" counterpart to [disconnect]. [disconnect] alone
     * is still what the idle-sweep uses for a discovered relay that's merely gone quiet, since it
     * reconnects on demand and its learned capabilities ([isReqUnsupported], [isSubscriptionLimited],
     * etc.) are still valid then. This is for a relay that's been disabled, deleted, or lost its
     * last active role — reconnecting it again would need everything relearned anyway, so there's
     * no reason to keep it around.
     */
    fun forgetRelay(relayUrl: String)
    fun disconnectAll()
    fun isConnected(relayUrl: String): Boolean
    fun hasActiveSocket(relayUrl: String): Boolean
    /**
     * True if this relay recently told us (via NOTICE/CLOSED/OK) that it's rate-limiting or
     * rejecting our client — new REQs should be withheld until the throttle window passes
     * instead of repeating the same request volume that triggered the rejection.
     */
    fun isThrottled(relayUrl: String): Boolean
    /**
     * True if this relay has told us (via a CLOSED response to a REQ) that it does not accept
     * subscriptions at all — e.g. broadcast-only aggregator relays like nosflare's "sendit"
     * variant. Once learned, REQs should stop being sent to this relay for the process lifetime.
     */
    fun isReqUnsupported(relayUrl: String): Boolean
    /**
     * True if this relay has told us (via a CLOSED response to a REQ) that every filter must
     * include a NIP-50 `search` field — e.g. relays running searchnos. Non-search REQs should be
     * withheld from this relay; search REQs should still be sent.
     */
    fun requiresSearchFilter(relayUrl: String): Boolean
    /**
     * True if this relay has told us (via NOTICE/CLOSED) it's over its concurrent-subscription
     * count — distinct from [isThrottled] (a time-boxed rate/block cooldown). Non-essential
     * channels (see `ChannelPriority`) should be withheld from this relay for the process
     * lifetime; essential ones (the user's own notes/DMs/interactions) keep being sent.
     */
    fun isSubscriptionLimited(relayUrl: String): Boolean
    /**
     * True if this relay's NIP-11 document claims NIP-77 support but it rejected a NEG-OPEN with a
     * generic NOTICE saying negentropy is actually disabled at runtime (e.g. strfry with the
     * feature compiled in but turned off in config) — see [negOpen]. Once learned, callers should
     * stop attempting NIP-77 sync against this relay for the rest of the session.
     */
    fun isNegentropyUnsupported(relayUrl: String): Boolean
    /**
     * True if this relay has told us (via a CLOSED response to a REQ) that it rejects a REQ
     * reusing an already-open subscription_id as a "duplicate" — NIP-01 requires treating that as
     * a silent filter replace, but some relay implementations close it as an error instead. Once
     * learned, callers should stop reusing a mapped subId for this relay's channels and mint a
     * fresh one on the next reapply.
     */
    fun rejectsSubIdReuse(relayUrl: String): Boolean
    /**
     * Applies [filters] for [channelId] to [relayUrl] if appropriate — the channel-aware
     * counterpart to a raw [subscribe] call. Internally: withholds the REQ for transport-state
     * reasons this client already owns ([isConnected] false, [isThrottled], [isReqUnsupported],
     * [requiresSearchFilter] with no search term present), skips silently if [filters] are
     * unchanged from the last filters successfully sent for this exact (relayUrl, channelId) pair
     * (that memory is cleared automatically on this relay's next successful reconnect — a fresh
     * socket has no memory of the old one), then mints or reuses a subscription id (fresh every
     * time if [rejectsSubIdReuse] is true for this relay) and sends the REQ via [subscribe].
     * Deliberately has zero knowledge of NIP-65/outbox routing, mute lists, per-channel `since`
     * windows, essential-channel overrides, or already-tried-id/author exclusion — callers must
     * finish all of that filter/eligibility decision-making before calling this; this only takes
     * final filters and applies transport-level judgment on top.
     * @return true if a REQ was actually sent, false if withheld (including the no-op case).
     */
    fun applyChannel(channelId: String, relayUrl: String, filters: List<EventFilter>): Boolean
    /**
     * The subscription id currently mapped to [channelId] on [relayUrl], or null if [applyChannel]
     * has never sent a REQ for that pair (or it's since been cleared, e.g. by
     * [clearChannelSubscription]). Distinct from [resolveChannelId], which answers "what channel
     * produced this subId, ever" via a stamp that never moves once recorded — this answers "what
     * subId is CURRENT for this channel right now."
     */
    fun currentSubscriptionId(relayUrl: String, channelId: String): String?
    /** Number of distinct channels currently holding a subscription on [relayUrl]. */
    fun subscribedChannelCount(relayUrl: String): Int
    /**
     * Given a (relayUrl, subscriptionId) observed on the wire ([reqFlow]/[eoseFlow] carry no
     * channel concept of their own), returns which internal channelId produced it — resolved via a
     * stable stamp recorded once when the subId was minted, never overwritten by a later reapply
     * that moves the channel to a fresh subId. Deliberately NOT cleared by [forgetRelay]/
     * [disconnectAll] (kept for "Active Subscriptions" UI history even after teardown).
     */
    fun resolveChannelId(relayUrl: String, subscriptionId: String): String?
    /**
     * (relayUrl, subscriptionId) pairs for every relay currently holding a subscription for
     * [channelId] — used to know which relays/subIds to wait on for EOSE without assuming
     * [channelId] was only ever applied to a caller-tracked relay set (e.g. a NIP-19 relay hint
     * dialed outside the normal relay pool).
     */
    fun subscriptionsForChannel(channelId: String): Set<Pair<String, String>>
    /**
     * Removes [channelId]'s mapped subscription id on [relayUrl] (if any) from bookkeeping and
     * sends the CLOSE frame for it via [unsubscribe]. Does not touch [resolveChannelId]'s history
     * stamp — same "keep it for UI history" reasoning as [forgetRelay]/[disconnectAll].
     * @return the subscription id that was cleared, or null if [channelId] had none on [relayUrl].
     */
    fun clearChannelSubscription(relayUrl: String, channelId: String): String?
    /**
     * Mints a fresh subscription id and registers it under [channelId] purely for "Active
     * Subscriptions" bookkeeping/UI visibility ([resolveChannelId]/[reqFlow]) — unlike
     * [applyChannel], no REQ is sent and this id is never reapplied on reconnect. For protocol
     * exchanges that drive their own wire messages outside the REQ/CLOSE model (e.g. NIP-77's
     * NEG-OPEN/NEG-MSG/NEG-CLOSE — see [negOpen]) but should still surface in the same
     * subscription-tracking UI as every REQ-based channel. Pair with
     * [unregisterTrackedSubscription] once the exchange ends.
     */
    fun registerTrackedSubscription(relayUrl: String, channelId: String, filters: List<EventFilter> = emptyList()): String
    /**
     * Forgets the (relayUrl, channelId) mapping created by [registerTrackedSubscription]. Unlike
     * [clearChannelSubscription], this sends no wire message — the caller is responsible for
     * whatever protocol-specific close message applies (e.g. NEG-CLOSE); this only clears Umbra's
     * own bookkeeping so a later [registerTrackedSubscription] call for the same channel mints a
     * genuinely fresh id instead of reusing one the relay has already forgotten.
     * @return the subscription id that was forgotten, or null if [channelId] had none on [relayUrl].
     */
    fun unregisterTrackedSubscription(relayUrl: String, channelId: String): String?
    /**
     * Clears every relay's channel->subId mapping and last-sent-filters memory, without
     * disconnecting any socket — for a subscription-namespace switch (e.g. account change), where
     * every previously-sent subId now belongs to a different logical session and must never be
     * treated as "already applied" again, but existing sockets should stay open. The
     * [resolveChannelId] history stamp is untouched, same rationale as every other cleanup path
     * here.
     */
    fun resetSubscriptionBookkeeping()
    /**
     * Clears [relayUrl]'s consecutive connect-failure count (see
     * UmbraNostrClient.MAX_CONSECUTIVE_FAILURES_BEFORE_AUTO_DISABLE) — called when the user
     * manually re-enables a relay that was auto-disabled for failing too many times in a row, so
     * it gets a fresh run at that threshold instead of immediately re-tripping on its next
     * failure (the count would otherwise still be sitting at the threshold from before).
     */
    fun resetFailureCount(relayUrl: String)

    /**
     * Clears every relay's accumulated failure count, cooldown, and SOCKS/Orbot-wait retry state
     * — the pool-wide counterpart to [resetFailureCount]. Called when circuit health recovers
     * after a confirmed "Tor Active but circuits dead" episode (see
     * com.umbra.app.domain.relay.TorCircuitHealthTracker): that recovery is direct proof the
     * shared Tor transport works again, so every relay still serving out its own accumulated
     * backoff from failures measured against the dead transport is given a clean slate instead of
     * each independently rediscovering the same thing on its own schedule. Does not itself
     * initiate any reconnects — callers are expected to trigger a fresh connect pass afterward.
     */
    fun resetAllBackoff()
}

