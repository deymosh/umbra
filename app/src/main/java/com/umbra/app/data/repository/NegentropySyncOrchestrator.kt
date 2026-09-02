package com.umbra.app.data.repository

import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.data.db.mapper.toDomain
import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.data.nostr.classifyRelayNotice
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip77.NegSignal
import com.umbra.app.domain.nip77.NegentropyItem
import com.umbra.app.domain.nip77.NegentropyReconciler
import com.umbra.app.domain.nip77.NegentropyStorageVector
import com.umbra.app.domain.nip77.SyncDirection
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.util.hexToBytes
import com.umbra.app.domain.util.toHex
import com.umbra.app.util.logging.UmbraLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [com.umbra.app.data.db.dao.EventDao]'s slice [NegentropySyncOrchestrator] needs beyond the
 * caller-supplied local item snapshot ([NegentropySyncOrchestrator.sync]'s `localItems` param) —
 * narrowed so tests can supply a small fake instead of implementing every unrelated DAO method.
 * [com.umbra.app.data.db.dao.EventDao] itself extends this.
 */
interface NegentropyEventSource {
    suspend fun getEventsByIds(ids: List<String>): List<EventEntity>
}

/**
 * NIP-77 (Negentropy) set-reconciliation sync. Generic over [EventFilter]/local item snapshot —
 * this class has no built-in notion of "the signed-in user's own events"; that scoping lives
 * entirely in the caller (see EventRepositoryImpl.scheduleNegentropySync(), which is the only
 * current caller and is what narrows this to own-events-only — a product/architecture decision,
 * not a limitation of this engine). Reconciliation itself ([NegentropyReconciler]) is pure domain
 * logic; this class is the data-layer glue driving the NEG-OPEN/NEG-MSG/NEG-CLOSE round trip per
 * relay and turning the result into ordinary REQ/EVENT traffic.
 */
internal class NegentropySyncOrchestrator(
    private val nostrClient: NostrClient,
    private val eventSource: NegentropyEventSource,
    private val repoScope: CoroutineScope,
    // Removes a (relayUrl, subscriptionId) entry from EventRepositoryImpl's own "Active
    // Subscriptions" UI tracking (_relayRequestsFlow) — a plain callback rather than depending on
    // the full EventRepository interface, since that's the one piece of EventRepositoryImpl-private
    // bookkeeping this class needs beyond what NostrClient itself exposes. Must be called for every
    // subscription this class registers (both the NEG-OPEN handshake and the fetch REQ below),
    // otherwise a closed subscription keeps showing as active in the UI forever — closing it on the
    // wire alone isn't enough, since NostrClient's own channel-clearing calls don't know about this
    // UI-facing list at all.
    private val removeSubscriptionInfo: (relayUrl: String, subscriptionId: String) -> Unit
) {
    private val logger = UmbraLog.tag(TAG)
    private val activeSyncs = ConcurrentHashMap<String, Boolean>()
    private val pushBackoff = NegentropyPushBackoff()

    /**
     * Kicks off one sync per relay in [relays] that has advertised NIP-77 support, is currently
     * connected, and isn't already syncing. [relays] can be any relay or list of relays a caller
     * wants to reconcile against — this method has no opinion on their role (write, read,
     * discovered, or otherwise); that choice belongs entirely to the caller (e.g.
     * EventRepositoryImpl.scheduleNegentropySync() passes the signed-in user's own write relays,
     * but nothing here assumes that). [filter] is sent verbatim as NEG-OPEN's reconciliation scope
     * — any REQ-expressible filter works (arbitrary authors/kinds/time window), this method has no
     * opinion on what it should contain either. Its `limit` field is always forced to `0` (this
     * codebase's "no limit" sentinel — see NostrRequestBuilder.filterToJson) regardless of what
     * [filter] specifies: [EventFilter]'s own default (100) would silently truncate the
     * reconciled set on any relay holding more matching events than that, which a
     * completeness-focused sync must never do. [localItems] must describe exactly the same
     * universe [filter] does — a mismatch between what the relay is asked to reconcile and what
     * the local snapshot actually contains makes every sync round perpetually report the mismatch
     * as have/need. Fire-and-forget: each relay's sync runs independently on [repoScope] and never
     * blocks the caller. [direction] defaults to [SyncDirection.DOWNLOAD_ONLY] for any caller that
     * doesn't care, so a caller opting into sync doesn't silently start publishing (uploading) to
     * a relay without asking — the reconciliation handshake itself always computes both sides
     * regardless of [direction]; only whether the post-handshake fetch/publish actions actually
     * run is gated by it (see [runSync]).
     */
    fun sync(
        relays: List<Relay>,
        filter: EventFilter,
        direction: SyncDirection = SyncDirection.DOWNLOAD_ONLY,
        localItems: suspend () -> List<NegentropyItem>
    ) {
        // Keyed by normalized URL, not the raw Relay.url — matching this codebase's established
        // defensive-normalization convention for relay-keyed maps (see e.g.
        // EventRepositoryImpl.reconnectRelevantDiscoveredRelays()), so two differently-cased or
        // trailing-slash-inconsistent representations of the same relay are correctly deduped
        // instead of allowing two concurrent syncs against what's actually one relay.
        val eligible = relays.filter { relay ->
            relaySupportsNip(relay, nip = 77) &&
                // NIP-11's supported_nips can be wrong: a relay's own NOTICE rejecting a previous
                // NEG-OPEN as actually-disabled overrides what it advertised — see
                // NostrClient.isNegentropyUnsupported's doc comment.
                !nostrClient.isNegentropyUnsupported(relay.url) &&
                nostrClient.isConnected(relay.url) &&
                activeSyncs.putIfAbsent(normalizeRelayUrl(relay.url), true) == null
        }
        if (eligible.isEmpty()) return
        val scopedFilter = filter.copy(limit = 0)

        eligible.forEach { relay ->
            repoScope.launch {
                try {
                    runSync(relay.url, scopedFilter, direction, localItems())
                } catch (e: Exception) {
                    logger.d { "NIP-77 sync with relay failed: ${e.message}" }
                } finally {
                    activeSyncs.remove(normalizeRelayUrl(relay.url))
                }
            }
        }
    }

    private suspend fun runSync(relayUrl: String, filter: EventFilter, direction: SyncDirection, items: List<NegentropyItem>) {
        val reconciler = NegentropyReconciler(NegentropyStorageVector(items), isInitiator = true)
        // The wire-level subscription id NEG-OPEN sends is minted internally by NostrClient and
        // stays random/content-free (a relay should never be handed a readable label for what a
        // subscription is for) — registerTrackedSubscription only additionally stamps it under
        // NEGENTROPY_SYNC_PREFIX so it resolves to a real SubscriptionType and shows up in "Active
        // Subscriptions" instead of being invisible. It's intentionally NOT registered via
        // applyChannel (see NostrChannels.NEGENTROPY_SYNC_PREFIX's doc comment for why this
        // handshake's lifecycle doesn't fit that registry's reapply-on-reconnect model).
        val channelId = NostrChannels.negentropySync(relayUrl)
        val subscriptionId = nostrClient.registerTrackedSubscription(relayUrl, channelId, listOf(filter))

        val haveIds = mutableSetOf<String>()
        val needIds = mutableSetOf<String>()
        val incoming = nostrClient.negMessageFlow.filter {
            it.relayUrl == relayUrl && it.subscriptionId == subscriptionId
        }

        logger.d { "NIP-77 sync starting: ${items.size} local items" }

        nostrClient.negOpen(relayUrl, subscriptionId, filter, reconciler.initiate().toHex())
        try {
            var rounds = 0
            while (rounds < MAX_ROUNDS) {
                rounds++
                val signal = withTimeoutOrNull(ROUND_TIMEOUT_MS) { incoming.first() } ?: break
                when (signal) {
                    is NegSignal.Err -> {
                        logger.d { "NIP-77 sync aborted by relay: ${signal.reason}" }
                        return
                    }
                    is NegSignal.Msg -> {
                        val result = reconciler.reconcile(signal.messageHex.hexToBytes())
                        haveIds += result.haveIds
                        needIds += result.needIds
                        val outgoing = result.outgoingMessage ?: break
                        nostrClient.negMsg(relayUrl, subscriptionId, outgoing.toHex())
                    }
                }
            }
        } finally {
            nostrClient.negClose(relayUrl, subscriptionId)
            nostrClient.unregisterTrackedSubscription(relayUrl, channelId)
            removeSubscriptionInfo(relayUrl, subscriptionId)
        }

        logger.d { "NIP-77 sync with relay complete: have=${haveIds.size} need=${needIds.size}" }

        if (direction.pullsFromRelay && needIds.isNotEmpty()) fetchMissingEvents(relayUrl, needIds)
        if (direction.pushesToRelay && haveIds.isNotEmpty()) pushHaveIds(relayUrl, haveIds)
    }

    /**
     * Publishes [haveIds] back to [relayUrl] in small, paced chunks instead of one unbounded
     * sequential `forEach` — a relay can hold hundreds of events the client has that it doesn't,
     * and blasting all of them at once is exactly the kind of traffic a relay's own rate limiter
     * exists to catch. Reuses [NostrClient.isThrottled] (populated from ANY NOTICE/CLOSED/OK this
     * relay has sent, including rejections from this very push, since publishing goes through the
     * same wire path as any other publish) as a pre-check, plus a push-specific escalating
     * [pushBackoff] on top — kept separate from the general throttle so a rate-limited background
     * sync doesn't also throttle this relay's live feed/DM traffic, and vice versa.
     */
    private suspend fun pushHaveIds(relayUrl: String, haveIds: Set<String>): Unit = coroutineScope {
        if (pushBackoff.isThrottled(relayUrl) || nostrClient.isThrottled(relayUrl)) {
            logger.d { "Skipping NIP-77 push — relay currently throttled" }
            return@coroutineScope
        }
        val entities = eventSource.getEventsByIds(haveIds.toList())
        if (entities.isEmpty()) return@coroutineScope

        val rejectedByRelay = AtomicBoolean(false)
        // CoroutineStart.UNDISPATCHED so this collector is guaranteed to already be subscribed to
        // publishResultFlow before publishing starts below — a relay's OK rejection for an early
        // event in a (deliberately paced, multi-second) chunk can arrive well before the rest of
        // that chunk finishes publishing, and a SharedFlow has no replay for a late subscriber.
        val rejectionWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            nostrClient.publishResultFlow
                .filter { it.relayUrl == relayUrl && !it.accepted }
                .collect { result ->
                    val kind = classifyRelayNotice(result.message.ifBlank { "event rejected" })
                    if (kind == RelayIssueKind.RATE_LIMIT || kind == RelayIssueKind.SUBSCRIPTION_LIMIT || kind == RelayIssueKind.BLOCKED) {
                        if (rejectedByRelay.compareAndSet(false, true)) {
                            pushBackoff.recordRejection(relayUrl)
                            logger.d { "NIP-77 push throttled by relay — stopping remaining chunks for this sync" }
                        }
                    }
                }
        }

        try {
            for (chunk in entities.chunked(PUBLISH_CHUNK_SIZE)) {
                if (rejectedByRelay.get() || nostrClient.isThrottled(relayUrl)) break
                for (entity in chunk) {
                    if (rejectedByRelay.get()) break
                    nostrClient.publishEvent(relayUrl, entity.toDomain())
                    delay(PUBLISH_INTER_EVENT_DELAY_MS)
                }
                if (!rejectedByRelay.get()) pushBackoff.recordCleanChunk(relayUrl)
                delay(PUBLISH_INTER_CHUNK_DELAY_MS)
            }
        } finally {
            rejectionWatcher.cancel()
        }
    }

    /**
     * A plain one-shot REQ by id, routed through the normal channel registry
     * ([NostrChannels.negentropyFetch]) instead of a raw [NostrClient.subscribe] call — this gets
     * the same connected/throttled/req-unsupported guards and content-dedup every other channel in
     * this codebase gets, and shows up correctly in "Active Subscriptions" bookkeeping. The
     * ingestion pipeline already running off nostrClient.eventFlow verifies and persists whatever
     * comes back, same as any other channel's events. Waits for EOSE (or a timeout) then explicitly
     * clears the channel — a one-shot fetch has no business staying open on the relay forever, and
     * [removeSubscriptionInfo] is what actually makes it disappear from "Active Subscriptions"
     * (clearChannelSubscription alone only closes the wire-level REQ; it doesn't touch
     * EventRepositoryImpl's separate UI-tracking list).
     */
    private suspend fun fetchMissingEvents(relayUrl: String, needIds: Set<String>) {
        val channelId = NostrChannels.negentropyFetch(relayUrl)
        nostrClient.applyChannel(channelId, relayUrl, listOf(EventFilter(ids = needIds, limit = 0)))
        val subscriptionId = nostrClient.currentSubscriptionId(relayUrl, channelId)
        if (subscriptionId != null) {
            val eose = nostrClient.eoseFlow.filter { it.relayUrl == relayUrl && it.subscriptionId == subscriptionId }
            withTimeoutOrNull(ROUND_TIMEOUT_MS) { eose.first() }
        }
        val closedSubId = nostrClient.clearChannelSubscription(relayUrl, channelId)
        if (closedSubId != null) removeSubscriptionInfo(relayUrl, closedSubId)
    }

    companion object {
        private const val TAG = "NegentropySync"
        private const val ROUND_TIMEOUT_MS = 15_000L
        // Generous ceiling on message round-trips per relay — the reconciliation algorithm
        // converges in O(log N) rounds for realistic dataset sizes; this only guards against a
        // misbehaving relay that never actually converges (e.g. bouncing Fingerprint ranges back
        // and forth), not a normal sync.
        private const val MAX_ROUNDS = 64
        // Sized for this class's actual scope — one signed-in user's own event backlog against
        // their own write relays (dozens to low hundreds of events per relay per sync), not
        // Amethyst's general multi-thousand-event relay-pool sync, which is why these are much
        // smaller than that client's own 250/500-item batches.
        private const val PUBLISH_CHUNK_SIZE = 50
        private const val PUBLISH_INTER_EVENT_DELAY_MS = 150L
        private const val PUBLISH_INTER_CHUNK_DELAY_MS = 2_000L
    }
}

/**
 * Escalating per-relay backoff specifically for [NegentropySyncOrchestrator.pushHaveIds] — kept
 * separate from [NostrClient.isThrottled]'s flat, all-traffic throttle so a rate-limited
 * background sync doesn't also throttle that relay's live feed/DM traffic, and vice versa. Tiers
 * do NOT reset on relay reconnect — deliberate, same "no automatic clean slate" precedent as
 * UmbraNostrClient.relayNegentropyUnsupported elsewhere in this codebase: a relay that rate-limited
 * a push shouldn't get a clean slate just because the socket bounced. They do relax one tier per
 * subsequent clean chunk via [recordCleanChunk], and are only ever held in memory for the process
 * lifetime (same as every other sticky per-relay signal here), not persisted.
 */
private class NegentropyPushBackoff {
    private val tierByRelay = ConcurrentHashMap<String, Int>()
    private val throttledUntilByRelay = ConcurrentHashMap<String, Long>()

    fun isThrottled(relayUrl: String): Boolean =
        (throttledUntilByRelay[relayUrl] ?: 0L) > System.currentTimeMillis()

    fun recordRejection(relayUrl: String) {
        val tier = ((tierByRelay[relayUrl] ?: -1) + 1).coerceAtMost(TIERS_MS.lastIndex)
        tierByRelay[relayUrl] = tier
        throttledUntilByRelay[relayUrl] = System.currentTimeMillis() + TIERS_MS[tier]
    }

    fun recordCleanChunk(relayUrl: String) {
        val tier = (tierByRelay[relayUrl] ?: 0) - 1
        if (tier < 0) tierByRelay.remove(relayUrl) else tierByRelay[relayUrl] = tier
    }

    private companion object {
        // 2s -> 10s -> 30s -> 2m -> 10m. Escalates on each further rejection within the
        // throttled window; relaxes one tier per clean chunk (see recordCleanChunk).
        val TIERS_MS = longArrayOf(2_000L, 10_000L, 30_000L, 120_000L, 600_000L)
    }
}
