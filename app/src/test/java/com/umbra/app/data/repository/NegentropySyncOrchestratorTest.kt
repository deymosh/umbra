package com.umbra.app.data.repository

import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.nip67.EoseSignal
import com.umbra.app.domain.nip77.NegSignal
import com.umbra.app.domain.nip77.NegentropyItem
import com.umbra.app.domain.nip77.NegentropyReconciler
import com.umbra.app.domain.nip77.NegentropyStorageVector
import com.umbra.app.domain.nip77.SyncDirection
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayPublishResult
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.util.hexToBytes
import com.umbra.app.domain.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NegentropySyncOrchestratorTest {

    /**
     * Plays the "server" (relay) side of the exchange using a real, spec-verified
     * [NegentropyReconciler] — this test's job is to check the orchestrator (client side) drives
     * the round trip and reacts to the outcome correctly, not to re-verify the protocol itself
     * (already covered by NegentropyReconcilerConformanceTest). Also fakes just enough of the
     * applyChannel/currentSubscriptionId/clearChannelSubscription/registerTrackedSubscription
     * channel registry to exercise both the handshake's and the fetch REQ's tracking realistically.
     */
    private class FakeNostrClient(
        serverItems: List<NegentropyItem>,
        private val connected: Boolean = true,
        private val negentropyUnsupported: Boolean = false,
        // When set, every publishEvent(relayUrl, event) call synchronously emits a rejection with
        // this message on publishResultFlow — simulates a relay that's rate-limiting/blocking the
        // push, exercising NegentropySyncOrchestrator.pushHaveIds' rejection-watcher path.
        private val rejectPublishesWithMessage: String? = null
    ) : NostrClient {
        val negOpenCalls = mutableListOf<Triple<String, String, EventFilter>>()
        val negCloseCalls = mutableListOf<Pair<String, String>>()
        val applyChannelCalls = mutableListOf<Triple<String, String, List<EventFilter>>>()
        val unsubscribeCalls = mutableListOf<Pair<String, String>>()
        val publishSingleCalls = mutableListOf<Pair<String, Event>>()
        val registerTrackedSubscriptionCalls = mutableListOf<Triple<String, String, List<EventFilter>>>()
        val unregisterTrackedSubscriptionCalls = mutableListOf<Pair<String, String>>()

        private val serverReconciler = NegentropyReconciler(NegentropyStorageVector(serverItems), isInitiator = false)
        private val channelSubIds = mutableMapOf<Pair<String, String>, String>()
        private var nextFakeSubId = 0

        // replay = 1 so a reply emitted synchronously inside negOpen()/negMsg()/applyChannel()
        // (before the orchestrator's collector has started for that round) is still delivered — a
        // real relay replies over the network, always after the orchestrator starts collecting, so
        // this buffering only compensates for this fake's synchronous call stack, not a production bug.
        private val _negMessageFlow = MutableSharedFlow<NegSignal>(replay = 1, extraBufferCapacity = 16)
        override val negMessageFlow: SharedFlow<NegSignal> = _negMessageFlow.asSharedFlow()
        private val _eoseFlow = MutableSharedFlow<EoseSignal>(replay = 1, extraBufferCapacity = 16)
        override val eoseFlow: SharedFlow<EoseSignal> = _eoseFlow.asSharedFlow()
        private val _publishResultFlow = MutableSharedFlow<RelayPublishResult>(extraBufferCapacity = 64)
        override val publishResultFlow: SharedFlow<RelayPublishResult> = _publishResultFlow.asSharedFlow()

        override val eventFlow: SharedFlow<Pair<String, Event>> get() = throw NotImplementedError()
        override val reqFlow: SharedFlow<RelayRequestInfo> get() = throw NotImplementedError()
        override val subscriptionEventFlow: SharedFlow<Pair<String, String>> get() = throw NotImplementedError()
        override val countFlow: SharedFlow<RelayCountResult> get() = throw NotImplementedError()
        override val relayIssueFlow: SharedFlow<RelayIssue> get() = throw NotImplementedError()
        override val connectedRelayUrlsFlow: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val relayOpenedFlow: SharedFlow<String> get() = throw NotImplementedError()

        override fun connect(relayUrl: String) = Result.success(Unit)
        override fun subscribe(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {}
        override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {}

        override fun negOpen(relayUrl: String, subscriptionId: String, filter: EventFilter, initialMessageHex: String) {
            negOpenCalls.add(Triple(relayUrl, subscriptionId, filter))
            respondAsServer(relayUrl, subscriptionId, initialMessageHex)
        }

        override fun negMsg(relayUrl: String, subscriptionId: String, messageHex: String) {
            respondAsServer(relayUrl, subscriptionId, messageHex)
        }

        override fun negClose(relayUrl: String, subscriptionId: String) {
            negCloseCalls.add(relayUrl to subscriptionId)
        }

        private fun respondAsServer(relayUrl: String, subscriptionId: String, incomingHex: String) {
            val result = serverReconciler.reconcile(incomingHex.hexToBytes())
            val reply = result.outgoingMessage ?: return
            _negMessageFlow.tryEmit(NegSignal.Msg(relayUrl, subscriptionId, reply.toHex()))
        }

        override fun publishEvent(relayUrl: String, event: Event) {
            publishSingleCalls.add(relayUrl to event)
            val rejectMessage = rejectPublishesWithMessage ?: return
            _publishResultFlow.tryEmit(
                RelayPublishResult(relayUrl = relayUrl, eventId = event.id, accepted = false, message = rejectMessage)
            )
        }
        override fun publishAuthEvent(relayUrl: String, event: Event) {}
        override suspend fun publishEvent(event: Event) {}
        override suspend fun publishEventToRelays(event: Event, relayUrls: List<String>) {}
        override fun unsubscribe(relayUrl: String, subscriptionId: String) {}
        override fun disconnect(relayUrl: String) {}
        override fun forgetRelay(relayUrl: String) {}
        override fun disconnectAll() {}
        override fun isConnected(relayUrl: String) = connected
        override fun hasActiveSocket(relayUrl: String) = connected
        override fun isThrottled(relayUrl: String) = false
        override fun isReqUnsupported(relayUrl: String) = false
        override fun requiresSearchFilter(relayUrl: String) = false
        override fun isSubscriptionLimited(relayUrl: String) = false
        override fun isNegentropyUnsupported(relayUrl: String) = negentropyUnsupported
        override fun rejectsSubIdReuse(relayUrl: String) = false

        override fun applyChannel(channelId: String, relayUrl: String, filters: List<EventFilter>): Boolean {
            applyChannelCalls.add(Triple(relayUrl, channelId, filters))
            val subId = "fake-sub-${nextFakeSubId++}"
            channelSubIds[relayUrl to channelId] = subId
            // Simulate an immediate EOSE, same rationale as respondAsServer above.
            _eoseFlow.tryEmit(EoseSignal(relayUrl, subId))
            return true
        }

        override fun currentSubscriptionId(relayUrl: String, channelId: String): String? =
            channelSubIds[relayUrl to channelId]

        override fun subscribedChannelCount(relayUrl: String) = 0
        override fun resolveChannelId(relayUrl: String, subscriptionId: String): String? = null
        override fun subscriptionsForChannel(channelId: String): Set<Pair<String, String>> = emptySet()

        override fun clearChannelSubscription(relayUrl: String, channelId: String): String? {
            val subId = channelSubIds.remove(relayUrl to channelId) ?: return null
            unsubscribeCalls.add(relayUrl to subId)
            return subId
        }

        override fun registerTrackedSubscription(relayUrl: String, channelId: String, filters: List<EventFilter>): String {
            registerTrackedSubscriptionCalls.add(Triple(relayUrl, channelId, filters))
            val subId = "fake-sub-${nextFakeSubId++}"
            channelSubIds[relayUrl to channelId] = subId
            return subId
        }

        override fun unregisterTrackedSubscription(relayUrl: String, channelId: String): String? {
            val subId = channelSubIds.remove(relayUrl to channelId) ?: return null
            unregisterTrackedSubscriptionCalls.add(relayUrl to subId)
            return subId
        }

        override fun resetSubscriptionBookkeeping() {}
        override fun resetFailureCount(relayUrl: String) {}
        override fun resetAllBackoff() {}
    }

    private class FakeEventSource(
        private val entitiesById: Map<String, EventEntity> = emptyMap()
    ) : NegentropyEventSource {
        override suspend fun getEventsByIds(ids: List<String>) = ids.mapNotNull { entitiesById[it] }
    }

    private fun fakeId(i: Int): String = i.toString(16).padStart(4, '0').repeat(16)

    private fun relayWithNip77(url: String, supported: Boolean): Relay = Relay(
        id = url,
        url = url,
        relayInfo = RelayInfo(supportedNips = if (supported) listOf(77) else emptyList())
    )

    private val anyFilter = EventFilter(authors = setOf("aa".repeat(32)))

    /** [removedSubscriptionInfo] captures every (relayUrl, subscriptionId) the orchestrator asked
     * to be removed from "Active Subscriptions" tracking — the exact hook that was missing before
     * this fix, when a closed sync kept showing as active in the UI forever. */
    private fun newOrchestrator(
        nostrClient: FakeNostrClient,
        eventSource: NegentropyEventSource,
        scope: CoroutineScope,
        removedSubscriptionInfo: MutableList<Pair<String, String>> = mutableListOf()
    ): NegentropySyncOrchestrator = NegentropySyncOrchestrator(nostrClient, eventSource, scope) { relayUrl, subscriptionId ->
        removedSubscriptionInfo.add(relayUrl to subscriptionId)
    }

    @Test
    fun `given a relay without NIP-77 support when syncing then no sync is attempted`() = runTest {
        val nostrClient = FakeNostrClient(serverItems = emptyList())
        val orchestrator = newOrchestrator(nostrClient, FakeEventSource(), this)

        orchestrator.sync(listOf(relayWithNip77("wss://no-nip77.example", supported = false)), anyFilter) { emptyList() }
        advanceUntilIdle()

        assertTrue(nostrClient.negOpenCalls.isEmpty())
    }

    @Test
    fun `given a relay that is not connected when syncing then no sync is attempted`() = runTest {
        val nostrClient = FakeNostrClient(serverItems = emptyList(), connected = false)
        val orchestrator = newOrchestrator(nostrClient, FakeEventSource(), this)

        orchestrator.sync(listOf(relayWithNip77("wss://disconnected.example", supported = true)), anyFilter) { emptyList() }
        advanceUntilIdle()

        assertTrue(nostrClient.negOpenCalls.isEmpty())
    }

    @Test
    fun `given a relay previously told us negentropy is disabled when syncing then no sync is attempted`() = runTest {
        // NIP-11 still claims support (supported = true) — a relay's own NOTICE rejecting a prior
        // NEG-OPEN as actually-disabled must override that stale advertisement.
        val nostrClient = FakeNostrClient(serverItems = emptyList(), negentropyUnsupported = true)
        val orchestrator = newOrchestrator(nostrClient, FakeEventSource(), this)

        orchestrator.sync(listOf(relayWithNip77("wss://negentropy-disabled.example", supported = true)), anyFilter) { emptyList() }
        advanceUntilIdle()

        assertTrue(nostrClient.negOpenCalls.isEmpty())
    }

    @Test
    fun `given a filter with a default limit when syncing then the limit is forced to zero on the wire`() = runTest {
        val nostrClient = FakeNostrClient(serverItems = emptyList())
        val orchestrator = newOrchestrator(nostrClient, FakeEventSource(), this)

        // EventFilter's own default limit (100) must never reach NEG-OPEN — it would silently
        // truncate the reconciled set on a relay holding more than 100 matching events.
        val filterWithDefaultLimit = EventFilter(authors = setOf("aa".repeat(32)))
        orchestrator.sync(listOf(relayWithNip77("wss://relay.example", supported = true)), filterWithDefaultLimit) { emptyList() }
        advanceUntilIdle()

        assertEquals(0, nostrClient.negOpenCalls.single().third.limit)
    }

    @Test
    fun `given a sync when it runs then the handshake is registered and torn down via the tracked-subscription API`() = runTest {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(serverItems = emptyList())
        val removedSubscriptionInfo = mutableListOf<Pair<String, String>>()
        val orchestrator = newOrchestrator(nostrClient, FakeEventSource(), this, removedSubscriptionInfo)

        orchestrator.sync(listOf(relayWithNip77(relayUrl, supported = true)), anyFilter) { emptyList() }
        advanceUntilIdle()

        // registerTrackedSubscription is what makes the NEG-OPEN handshake resolve to a real
        // SubscriptionType (NEGENTROPY_SYNC) and appear in "Active Subscriptions" at all — before
        // this fix it was entirely invisible there.
        assertEquals(1, nostrClient.registerTrackedSubscriptionCalls.size)
        val (registeredRelayUrl, channelId, filters) = nostrClient.registerTrackedSubscriptionCalls.single()
        assertEquals(relayUrl, registeredRelayUrl)
        assertEquals(NostrChannels.negentropySync(relayUrl), channelId)
        assertEquals(anyFilter.authors, filters.single().authors)

        val subscriptionId = nostrClient.negOpenCalls.single().second
        // The wire-level NEG-CLOSE, the internal bookkeeping forget, and the UI-tracking removal
        // must all happen once the handshake ends — a subscription only "looks closed" if all
        // three do.
        assertEquals(listOf(relayUrl to subscriptionId), nostrClient.negCloseCalls)
        assertEquals(listOf(relayUrl to subscriptionId), nostrClient.unregisterTrackedSubscriptionCalls)
        assertTrue(removedSubscriptionInfo.contains(relayUrl to subscriptionId))
    }

    @Test
    fun `given the client has an event the relay is missing when syncing then it is published to the relay`() = runTest {
        val localId = fakeId(1)
        val entity = EventEntity(
            id = localId,
            pubkey = "aa".repeat(32),
            createdAt = 1000L,
            kind = 1,
            content = "hello",
            sig = "sig",
            tagsJson = "[]"
        )
        val nostrClient = FakeNostrClient(serverItems = emptyList())
        val eventSource = FakeEventSource(entitiesById = mapOf(localId to entity))
        val orchestrator = newOrchestrator(nostrClient, eventSource, this)

        orchestrator.sync(
            listOf(relayWithNip77("wss://relay.example", supported = true)),
            anyFilter,
            direction = SyncDirection.BOTH
        ) {
            listOf(NegentropyItem(localId, 1000L))
        }
        advanceUntilIdle()

        assertEquals(1, nostrClient.negOpenCalls.size)
        // The push path now goes through the single-relay publishEvent(relayUrl, event) primitive
        // (paced/chunked/backoff-aware — see pushHaveIds), not the multi-relay
        // publishEventToRelays broadcast helper.
        assertEquals(1, nostrClient.publishSingleCalls.size)
        assertEquals(localId, nostrClient.publishSingleCalls.single().second.id)
        assertEquals(1, nostrClient.negCloseCalls.size)
    }

    @Test
    fun `given the relay has an event the client is missing when syncing then a fetch REQ is issued and removed from subscription tracking`() = runTest {
        val remoteOnlyId = fakeId(2)
        val nostrClient = FakeNostrClient(serverItems = listOf(NegentropyItem(remoteOnlyId, 1000L)))
        val removedSubscriptionInfo = mutableListOf<Pair<String, String>>()
        val orchestrator = newOrchestrator(nostrClient, FakeEventSource(), this, removedSubscriptionInfo)

        val relayUrl = "wss://relay.example"
        orchestrator.sync(listOf(relayWithNip77(relayUrl, supported = true)), anyFilter) { emptyList() }
        advanceUntilIdle()

        assertEquals(1, nostrClient.applyChannelCalls.size)
        val (_, channelId, filters) = nostrClient.applyChannelCalls.single()
        assertEquals(NostrChannels.negentropyFetch(relayUrl), channelId)
        assertEquals(setOf(remoteOnlyId), filters.single().ids)
        assertEquals(0, filters.single().limit)
        assertEquals(1, nostrClient.negCloseCalls.size)
        // The one-shot fetch channel must be explicitly closed on the wire...
        assertEquals(1, nostrClient.unsubscribeCalls.size)
        // ...AND removed from "Active Subscriptions" tracking — closing the wire-level REQ alone
        // (what the pre-fix code did) left it looking permanently open in the UI.
        val fetchSubscriptionId = nostrClient.unsubscribeCalls.single().second
        assertTrue(removedSubscriptionInfo.contains(relayUrl to fetchSubscriptionId))
    }

    @Test
    fun `given direction DOWNLOAD_ONLY when the client has an event the relay is missing then it is not published`() = runTest {
        val localId = fakeId(4)
        val entity = EventEntity(
            id = localId,
            pubkey = "aa".repeat(32),
            createdAt = 1000L,
            kind = 1,
            content = "hello",
            sig = "sig",
            tagsJson = "[]"
        )
        val nostrClient = FakeNostrClient(serverItems = emptyList())
        val eventSource = FakeEventSource(entitiesById = mapOf(localId to entity))
        val orchestrator = newOrchestrator(nostrClient, eventSource, this)

        orchestrator.sync(
            listOf(relayWithNip77("wss://relay.example", supported = true)),
            anyFilter,
            direction = SyncDirection.DOWNLOAD_ONLY
        ) { listOf(NegentropyItem(localId, 1000L)) }
        advanceUntilIdle()

        assertTrue(nostrClient.publishSingleCalls.isEmpty())
    }

    @Test
    fun `given direction UPLOAD_ONLY when the relay has an event the client is missing then no fetch REQ is issued`() = runTest {
        val remoteOnlyId = fakeId(5)
        val nostrClient = FakeNostrClient(serverItems = listOf(NegentropyItem(remoteOnlyId, 1000L)))
        val orchestrator = newOrchestrator(nostrClient, FakeEventSource(), this)

        orchestrator.sync(
            listOf(relayWithNip77("wss://relay.example", supported = true)),
            anyFilter,
            direction = SyncDirection.UPLOAD_ONLY
        ) { emptyList() }
        advanceUntilIdle()

        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    @Test
    fun `given identical sets when syncing then neither publish nor fetch is triggered`() = runTest {
        val sharedId = fakeId(3)
        val nostrClient = FakeNostrClient(serverItems = listOf(NegentropyItem(sharedId, 1000L)))
        val eventSource = FakeEventSource()
        val orchestrator = newOrchestrator(nostrClient, eventSource, this)

        orchestrator.sync(listOf(relayWithNip77("wss://relay.example", supported = true)), anyFilter) {
            listOf(NegentropyItem(sharedId, 1000L))
        }
        advanceUntilIdle()

        assertTrue(nostrClient.publishSingleCalls.isEmpty())
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
        assertEquals(1, nostrClient.negCloseCalls.size)
    }

    // Mirrors NegentropySyncOrchestrator's own private PUBLISH_CHUNK_SIZE — duplicated here
    // deliberately since that constant is private to keep it out of any public API surface.
    private val pushChunkSize = 50

    private fun localOnlyEntities(count: Int): Map<String, EventEntity> =
        (1..count).associate { i ->
            val id = fakeId(100 + i)
            id to EventEntity(
                id = id,
                pubkey = "aa".repeat(32),
                createdAt = 1000L,
                kind = 1,
                content = "hello $i",
                sig = "sig",
                tagsJson = "[]"
            )
        }

    @Test
    fun `given the relay rejects a push with a rate-limit message when syncing then remaining chunks are not sent`() = runTest {
        val entitiesById = localOnlyEntities(pushChunkSize * 2)
        val nostrClient = FakeNostrClient(serverItems = emptyList(), rejectPublishesWithMessage = "rate limited, slow down")
        val eventSource = FakeEventSource(entitiesById)
        val orchestrator = newOrchestrator(nostrClient, eventSource, this)

        orchestrator.sync(
            listOf(relayWithNip77("wss://relay.example", supported = true)),
            anyFilter,
            direction = SyncDirection.BOTH
        ) {
            entitiesById.map { (id, entity) -> NegentropyItem(id, entity.createdAt) }
        }
        advanceUntilIdle()

        assertTrue(nostrClient.publishSingleCalls.size < entitiesById.size)
    }

    @Test
    fun `given a relay already in push backoff when syncing then the push phase is skipped entirely`() = runTest {
        val relayUrl = "wss://relay.example"
        val entitiesById = localOnlyEntities(1)
        val nostrClient = FakeNostrClient(serverItems = emptyList(), rejectPublishesWithMessage = "rate limited, slow down")
        val eventSource = FakeEventSource(entitiesById)
        val orchestrator = newOrchestrator(nostrClient, eventSource, this)
        val localItems = { entitiesById.map { (id, entity) -> NegentropyItem(id, entity.createdAt) } }

        orchestrator.sync(listOf(relayWithNip77(relayUrl, supported = true)), anyFilter, direction = SyncDirection.BOTH, localItems = localItems)
        advanceUntilIdle()
        assertTrue(nostrClient.publishSingleCalls.isNotEmpty())

        nostrClient.publishSingleCalls.clear()
        orchestrator.sync(listOf(relayWithNip77(relayUrl, supported = true)), anyFilter, direction = SyncDirection.BOTH, localItems = localItems)
        advanceUntilIdle()

        assertTrue(nostrClient.publishSingleCalls.isEmpty())
    }

    @Test
    fun `given more haveIds than the chunk size when syncing then every event is still published across chunks`() = runTest {
        val entitiesById = localOnlyEntities((pushChunkSize * 2.6).toInt())
        val nostrClient = FakeNostrClient(serverItems = emptyList())
        val eventSource = FakeEventSource(entitiesById)
        val orchestrator = newOrchestrator(nostrClient, eventSource, this)

        orchestrator.sync(
            listOf(relayWithNip77("wss://relay.example", supported = true)),
            anyFilter,
            direction = SyncDirection.BOTH
        ) {
            entitiesById.map { (id, entity) -> NegentropyItem(id, entity.createdAt) }
        }
        advanceUntilIdle()

        assertEquals(entitiesById.size, nostrClient.publishSingleCalls.size)
        assertEquals(entitiesById.keys, nostrClient.publishSingleCalls.map { it.second.id }.toSet())
    }
}
