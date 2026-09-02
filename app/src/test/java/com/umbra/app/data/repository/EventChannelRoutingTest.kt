package com.umbra.app.data.repository

import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.nip67.EoseSignal
import com.umbra.app.domain.nip77.NegSignal
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayPublishResult
import com.umbra.app.domain.relay.RelayRequestInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dedicated unit tests for [EventChannelRouting] — the withhold predicates, precise
 * routing (including the discovered-relay author-scope privacy property), per-relay `since`
 * overrides, already-tried exclusion, fingerprint stability, and subscription-info bookkeeping.
 * The observable-behavior contract that matters is what filters actually reach the wire, so every
 * assertion here reads [FakeNostrClient.applyChannelCalls] — the exact recorded call list — rather
 * than any internal state.
 */
class EventChannelRoutingTest {

    /**
     * Records every [applyChannel] call so assertions can inspect exactly what reached the wire.
     * Every other gate ([isConnected], [isThrottled], etc.) has a settable backing value defaulting
     * to the "healthy, eligible relay" case, so each test only needs to override what it's
     * exercising.
     */
    private class FakeNostrClient(
        var connected: Boolean = true,
        var throttled: Boolean = false,
        var reqUnsupported: Boolean = false,
        var searchFilterRequired: Boolean = false,
        var subscriptionLimited: Boolean = false,
        var currentSubIdResult: String? = null,
        var subscribedCount: Int = 0,
        var resolvedChannelId: String? = null
    ) : NostrClient {
        val applyChannelCalls = mutableListOf<Triple<String, String, List<EventFilter>>>()

        override val eventFlow: SharedFlow<Pair<String, Event>> get() = throw NotImplementedError()
        override val reqFlow: SharedFlow<RelayRequestInfo> get() = throw NotImplementedError()
        override val subscriptionEventFlow: SharedFlow<Pair<String, String>> get() = throw NotImplementedError()
        override val eoseFlow: SharedFlow<EoseSignal> get() = throw NotImplementedError()
        override val countFlow: SharedFlow<RelayCountResult> get() = throw NotImplementedError()
        override val negMessageFlow: SharedFlow<NegSignal> get() = throw NotImplementedError()
        override val relayIssueFlow: SharedFlow<RelayIssue> get() = throw NotImplementedError()
        override val connectedRelayUrlsFlow: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val relayOpenedFlow: SharedFlow<String> get() = throw NotImplementedError()
        override val publishResultFlow: SharedFlow<RelayPublishResult> get() = throw NotImplementedError()

        override fun connect(relayUrl: String) = Result.success(Unit)
        override fun subscribe(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {}
        override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {}
        override fun negOpen(relayUrl: String, subscriptionId: String, filter: EventFilter, initialMessageHex: String) {}
        override fun negMsg(relayUrl: String, subscriptionId: String, messageHex: String) {}
        override fun negClose(relayUrl: String, subscriptionId: String) {}
        override fun publishEvent(relayUrl: String, event: Event) {}
        override fun publishAuthEvent(relayUrl: String, event: Event) {}
        override suspend fun publishEvent(event: Event) {}
        override suspend fun publishEventToRelays(event: Event, relayUrls: List<String>) {}
        override fun unsubscribe(relayUrl: String, subscriptionId: String) {}
        override fun disconnect(relayUrl: String) {}
        override fun forgetRelay(relayUrl: String) {}
        override fun disconnectAll() {}
        override fun isConnected(relayUrl: String) = connected
        override fun hasActiveSocket(relayUrl: String) = connected
        override fun isThrottled(relayUrl: String) = throttled
        override fun isReqUnsupported(relayUrl: String) = reqUnsupported
        override fun requiresSearchFilter(relayUrl: String) = searchFilterRequired
        override fun isSubscriptionLimited(relayUrl: String) = subscriptionLimited
        override fun isNegentropyUnsupported(relayUrl: String) = false
        override fun rejectsSubIdReuse(relayUrl: String) = false

        override fun applyChannel(channelId: String, relayUrl: String, filters: List<EventFilter>): Boolean {
            applyChannelCalls.add(Triple(relayUrl, channelId, filters))
            return true
        }

        override fun currentSubscriptionId(relayUrl: String, channelId: String): String? = currentSubIdResult
        override fun subscribedChannelCount(relayUrl: String) = subscribedCount
        override fun resolveChannelId(relayUrl: String, subscriptionId: String): String? = resolvedChannelId
        override fun subscriptionsForChannel(channelId: String): Set<Pair<String, String>> = emptySet()
        override fun clearChannelSubscription(relayUrl: String, channelId: String): String? = null
        override fun registerTrackedSubscription(relayUrl: String, channelId: String, filters: List<EventFilter>): String = ""
        override fun unregisterTrackedSubscription(relayUrl: String, channelId: String): String? = null
        override fun resetSubscriptionBookkeeping() {}
        override fun resetFailureCount(relayUrl: String) {}
        override fun resetAllBackoff() {}
    }

    private fun relay(
        url: String,
        isDiscovered: Boolean = false,
        isReadActive: Boolean = true,
        isWriteActive: Boolean = true,
        maxSubscriptions: Int? = null,
        maxLimitEventCount: Int? = null,
        supportedNips: List<Int> = emptyList()
    ): Relay = Relay(
        id = url,
        url = url,
        isDiscovered = isDiscovered,
        isReadActive = isReadActive,
        isWriteActive = isWriteActive,
        relayInfo = RelayInfo(
            maxSubscriptions = maxSubscriptions,
            maxLimitEventCount = maxLimitEventCount,
            supportedNips = supportedNips
        )
    )

    private fun routing(
        nostrClient: NostrClient,
        connectedRelays: ConcurrentHashMap<String, Relay> = ConcurrentHashMap(),
        feedSinceByRelay: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        outboxInboxSinceByRelay: ConcurrentHashMap<Pair<String, String>, Long> = ConcurrentHashMap(),
        eventLookupTriedByRelay: ConcurrentHashMap<String, MutableSet<String>> = ConcurrentHashMap(),
        authorHydrationTriedByRelay: ConcurrentHashMap<String, MutableSet<String>> = ConcurrentHashMap(),
        discoveredRelayLastNeededAtMillis: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        relayRequests: MutableStateFlow<List<RelayRequestInfo>> = MutableStateFlow(emptyList()),
        activeSessionAuthors: Set<String> = emptySet(),
        feedAuthorsPerRelay: Map<String, Set<String>> = emptyMap(),
        authorsWithKnownOutbox: Set<String> = emptySet()
    ): EventChannelRouting = EventChannelRouting(
        nostrClient,
        connectedRelays,
        feedSinceByRelay,
        outboxInboxSinceByRelay,
        eventLookupTriedByRelay,
        authorHydrationTriedByRelay,
        discoveredRelayLastNeededAtMillis,
        relayRequests,
        { activeSessionAuthors },
        { feedAuthorsPerRelay },
        { authorsWithKnownOutbox }
    )

    // ── Withhold predicates ────────────────────────────────────────────────

    @Test
    fun `given a disconnected relay when applyChannelToRelay then no REQ is sent`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(connected = false)
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val subject = routing(nostrClient, connectedRelays)

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(EventFilter()))

        assertFalse(applied)
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    @Test
    fun `given a throttled relay when applyChannelToRelay then no REQ is sent`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(throttled = true)
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val subject = routing(nostrClient, connectedRelays)

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(EventFilter()))

        assertFalse(applied)
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    @Test
    fun `given a relay that reports REQ unsupported when applyChannelToRelay then no REQ is sent`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(reqUnsupported = true)
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val subject = routing(nostrClient, connectedRelays)

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(EventFilter()))

        assertFalse(applied)
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    @Test
    fun `given a relay requiring a search filter and a non-search filter when applyChannelToRelay then no REQ is sent`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(searchFilterRequired = true)
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val subject = routing(nostrClient, connectedRelays)

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(EventFilter()))

        assertFalse(applied)
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    @Test
    fun `given a subscription-limited relay and a non-essential channel when applyChannelToRelay then no REQ is sent`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(subscriptionLimited = true)
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val subject = routing(nostrClient, connectedRelays)

        // NostrChannels.SEARCH is not in ChannelPriority's essential set.
        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.SEARCH, listOf(EventFilter()))

        assertFalse(applied)
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    @Test
    fun `given a subscription-limited relay and an essential channel when applyChannelToRelay then the REQ is still sent`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(subscriptionLimited = true)
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val subject = routing(nostrClient, connectedRelays)

        // NostrChannels.FEED_NOTES is essential (own timeline delivery).
        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(EventFilter()))

        assertTrue(applied)
        assertEquals(1, nostrClient.applyChannelCalls.size)
    }

    @Test
    fun `given a relay at its advertised subscription budget and a non-essential channel when applyChannelToRelay then it is withheld`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(currentSubIdResult = null, subscribedCount = 3)
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl, maxSubscriptions = 3)))
        val subject = routing(nostrClient, connectedRelays)

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.SEARCH, listOf(EventFilter()))

        assertFalse(applied)
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    @Test
    fun `given a relay at its advertised subscription budget and an essential channel when applyChannelToRelay then it is still applied`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient(currentSubIdResult = null, subscribedCount = 3)
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl, maxSubscriptions = 3)))
        val subject = routing(nostrClient, connectedRelays)

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(EventFilter()))

        assertTrue(applied)
        assertEquals(1, nostrClient.applyChannelCalls.size)
    }

    // ── Precise (gossip/outbox-model) routing ─────────────────────────────

    @Test
    fun `given precise routing on a discovered relay when applyChannelToRelay then only covered authors are sent and unknown-outbox authors are dropped`() {
        val relayUrl = "wss://discovered.example"
        val authorA = "a".repeat(64)
        val authorB = "b".repeat(64)
        val authorC = "c".repeat(64)
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl, isDiscovered = true)))
        val subject = routing(
            nostrClient,
            connectedRelays,
            activeSessionAuthors = setOf(authorA, authorB, authorC),
            feedAuthorsPerRelay = mapOf(relayUrl to setOf(authorA)),
            authorsWithKnownOutbox = setOf(authorA, authorB)
        )
        val filter = EventFilter(authors = setOf(authorA, authorB, authorC), kinds = setOf(Event.KIND_TEXT_NOTE))

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(filter))

        assertTrue(applied)
        val sentAuthors = nostrClient.applyChannelCalls.single().third.single().authors
        assertEquals(setOf(authorA), sentAuthors)
    }

    @Test
    fun `given precise routing on a non-discovered relay when applyChannelToRelay then unknown-outbox authors are included unscoped`() {
        val relayUrl = "wss://general.example"
        val authorA = "a".repeat(64)
        val authorB = "b".repeat(64)
        val authorC = "c".repeat(64)
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl, isDiscovered = false)))
        val subject = routing(
            nostrClient,
            connectedRelays,
            activeSessionAuthors = setOf(authorA, authorB, authorC),
            // This relay only declares outbox coverage for authorA; authorB has a known outbox
            // elsewhere (not this relay); authorC has no known outbox at all.
            feedAuthorsPerRelay = mapOf(relayUrl to setOf(authorA)),
            authorsWithKnownOutbox = setOf(authorA, authorB)
        )
        val filter = EventFilter(authors = setOf(authorA, authorB, authorC), kinds = setOf(Event.KIND_TEXT_NOTE))

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(filter))

        assertTrue(applied)
        val sentAuthors = nostrClient.applyChannelCalls.single().third.single().authors
        // authorA: covered by this relay. authorC: unknown-outbox, kept unscoped. authorB: has a
        // known outbox but NOT this relay's, so correctly excluded (not "unknown").
        assertEquals(setOf(authorA, authorC), sentAuthors)
    }

    // ── Already-tried exclusion ────────────────────────────────────────────

    @Test
    fun `given an already-tried event-lookup id when applyChannelToRelay then it is dropped from the filter`() {
        val relayUrl = "wss://relay.example"
        val id1 = "1".repeat(64)
        val id2 = "2".repeat(64)
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val eventLookupTriedByRelay = ConcurrentHashMap(mapOf(relayUrl to mutableSetOf(id1)))
        val subject = routing(nostrClient, connectedRelays, eventLookupTriedByRelay = eventLookupTriedByRelay)
        val filter = EventFilter(ids = setOf(id1, id2), limit = 2)

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.EVENT_LOOKUP, listOf(filter))

        assertTrue(applied)
        val sent = nostrClient.applyChannelCalls.single().third.single()
        assertEquals(setOf(id2), sent.ids)
        assertEquals(1, sent.limit)
    }

    @Test
    fun `given every event-lookup id already tried when applyChannelToRelay then the channel is withheld`() {
        val relayUrl = "wss://relay.example"
        val id1 = "1".repeat(64)
        val id2 = "2".repeat(64)
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val eventLookupTriedByRelay = ConcurrentHashMap(mapOf(relayUrl to mutableSetOf(id1, id2)))
        val subject = routing(nostrClient, connectedRelays, eventLookupTriedByRelay = eventLookupTriedByRelay)
        val filter = EventFilter(ids = setOf(id1, id2), limit = 2)

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.EVENT_LOOKUP, listOf(filter))

        assertFalse(applied)
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    @Test
    fun `given an already-tried author-hydration author when applyChannelToRelay then it is dropped from the filter`() {
        val relayUrl = "wss://relay.example"
        val authorX = "x".repeat(64)
        val authorY = "y".repeat(64)
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val authorHydrationTriedByRelay = ConcurrentHashMap(mapOf(relayUrl to mutableSetOf(authorX)))
        val subject = routing(nostrClient, connectedRelays, authorHydrationTriedByRelay = authorHydrationTriedByRelay)
        val filter = EventFilter(authors = setOf(authorX, authorY), kinds = setOf(Event.KIND_METADATA))

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_PROFILES_ONDEMAND, listOf(filter))

        assertTrue(applied)
        val sentAuthors = nostrClient.applyChannelCalls.single().third.single().authors
        assertEquals(setOf(authorY), sentAuthors)
    }

    @Test
    fun `given every author-hydration author already tried when applyChannelToRelay then the channel is withheld`() {
        val relayUrl = "wss://relay.example"
        val authorX = "x".repeat(64)
        val authorY = "y".repeat(64)
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val authorHydrationTriedByRelay = ConcurrentHashMap(mapOf(relayUrl to mutableSetOf(authorX, authorY)))
        val subject = routing(nostrClient, connectedRelays, authorHydrationTriedByRelay = authorHydrationTriedByRelay)
        val filter = EventFilter(authors = setOf(authorX, authorY), kinds = setOf(Event.KIND_METADATA))

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_PROFILES_ONDEMAND, listOf(filter))

        assertFalse(applied)
        assertTrue(nostrClient.applyChannelCalls.isEmpty())
    }

    // ── Per-relay `since` overrides ────────────────────────────────────────

    @Test
    fun `given a per-relay feed since watermark when applyChannelToRelay then it overrides the filter since`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val feedSinceByRelay = ConcurrentHashMap(mapOf(relayUrl to 500L))
        val subject = routing(nostrClient, connectedRelays, feedSinceByRelay = feedSinceByRelay)
        val filter = EventFilter(since = 100L)

        subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(filter))

        assertEquals(500L, nostrClient.applyChannelCalls.single().third.single().since)
    }

    @Test
    fun `given no per-relay feed since watermark when applyChannelToRelay then the filter since is left untouched`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val subject = routing(nostrClient, connectedRelays)
        val filter = EventFilter(since = 100L)

        subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(filter))

        assertEquals(100L, nostrClient.applyChannelCalls.single().third.single().since)
    }

    @Test
    fun `given a per-relay outbox-inbox since watermark when applyChannelToRelay then only the until-null filter is overridden`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl)))
        val outboxInboxSinceByRelay = ConcurrentHashMap(
            mapOf((relayUrl to NostrChannels.OUTBOX_NOTES) to 900L)
        )
        val subject = routing(nostrClient, connectedRelays, outboxInboxSinceByRelay = outboxInboxSinceByRelay)
        val liveFilter = EventFilter(kinds = setOf(Event.KIND_TEXT_NOTE))
        val backfillWindowFilter = EventFilter(kinds = setOf(Event.KIND_REACTION), since = 200L, until = 999L)

        subject.applyChannelToRelay(relayUrl, NostrChannels.OUTBOX_NOTES, listOf(liveFilter, backfillWindowFilter))

        val sentFilters = nostrClient.applyChannelCalls.single().third
        val sentLiveFilter = sentFilters.single { it.until == null }
        val sentBackfillFilter = sentFilters.single { it.until != null }
        assertEquals(900L, sentLiveFilter.since)
        assertEquals(backfillWindowFilter, sentBackfillFilter)
    }

    // ── Discovered-relay idle clock ────────────────────────────────────────

    @Test
    fun `given a discovered relay and a precise-routed channel when applyChannelToRelay then the idle clock is reset`() {
        val relayUrl = "wss://discovered.example"
        val nostrClient = FakeNostrClient()
        val connectedRelays = ConcurrentHashMap(mapOf(relayUrl to relay(relayUrl, isDiscovered = true)))
        val discoveredRelayLastNeededAtMillis = ConcurrentHashMap<String, Long>()
        val subject = routing(
            nostrClient,
            connectedRelays,
            discoveredRelayLastNeededAtMillis = discoveredRelayLastNeededAtMillis
        )
        val before = System.currentTimeMillis()

        val applied = subject.applyChannelToRelay(relayUrl, NostrChannels.FEED_NOTES, listOf(EventFilter()))

        assertTrue(applied)
        val recorded = discoveredRelayLastNeededAtMillis[relayUrl]
        assertTrue(recorded != null && recorded >= before)
    }

    // ── fingerprint order-stability ─────────────────────────────────────────

    @Test
    fun `given filter lists differing only in element ordering when fingerprint then the same string is produced`() {
        val nostrClient = FakeNostrClient()
        val subject = routing(nostrClient)
        val filtersA = listOf(
            EventFilter(
                ids = setOf("b", "a"),
                authors = setOf("y", "x"),
                kinds = setOf(2, 1),
                tagFilters = mapOf("t" to setOf("y", "x"), "e" to setOf("q"))
            )
        )
        val filtersB = listOf(
            EventFilter(
                ids = setOf("a", "b"),
                authors = setOf("x", "y"),
                kinds = setOf(1, 2),
                tagFilters = mapOf("e" to setOf("q"), "t" to setOf("x", "y"))
            )
        )

        assertEquals(subject.fingerprint(filtersA), subject.fingerprint(filtersB))
    }

    // ── Subscription-info bookkeeping ───────────────────────────────────────

    @Test
    fun `given more than MAX_REQUESTS_PER_RELAY subscriptions on one relay when upsertSubscriptionInfo then the oldest entry for that relay is evicted`() {
        val relayUrl = "wss://relay.example"
        val nostrClient = FakeNostrClient()
        val relayRequests = MutableStateFlow<List<RelayRequestInfo>>(emptyList())
        val subject = routing(nostrClient, relayRequests = relayRequests)

        repeat(MAX_REQUESTS_PER_RELAY + 1) { i ->
            subject.upsertSubscriptionInfo(RelayRequestInfo(relayUrl = relayUrl, subscriptionId = "sub-$i"))
        }

        assertEquals(MAX_REQUESTS_PER_RELAY, relayRequests.value.size)
    }

    @Test
    fun `given an existing subscription entry when incrementSubscriptionEventCount then it updates in place without growing the list`() {
        val relayUrl = "wss://relay.example"
        val subscriptionId = "sub-1"
        val nostrClient = FakeNostrClient()
        val relayRequests = MutableStateFlow<List<RelayRequestInfo>>(emptyList())
        val subject = routing(nostrClient, relayRequests = relayRequests)
        subject.upsertSubscriptionInfo(RelayRequestInfo(relayUrl = relayUrl, subscriptionId = subscriptionId))

        subject.incrementSubscriptionEventCount(relayUrl, subscriptionId)
        subject.incrementSubscriptionEventCount(relayUrl, subscriptionId)
        subject.incrementSubscriptionEventCount(relayUrl, subscriptionId)

        assertEquals(1, relayRequests.value.size)
        assertEquals(3, relayRequests.value.single().receivedEventCount)
    }

    @Test
    fun `given a matching relay and subscription id when removeSubscriptionInfo then only that entry is deleted`() {
        val relayUrl1 = "wss://relay1.example"
        val relayUrl2 = "wss://relay2.example"
        val nostrClient = FakeNostrClient()
        val relayRequests = MutableStateFlow<List<RelayRequestInfo>>(emptyList())
        val subject = routing(nostrClient, relayRequests = relayRequests)
        subject.upsertSubscriptionInfo(RelayRequestInfo(relayUrl = relayUrl1, subscriptionId = "sub-1"))
        subject.upsertSubscriptionInfo(RelayRequestInfo(relayUrl = relayUrl2, subscriptionId = "sub-2"))

        subject.removeSubscriptionInfo(relayUrl1, "sub-1")

        val remaining = relayRequests.value
        assertEquals(1, remaining.size)
        assertEquals(relayUrl2, remaining.single().relayUrl)
        assertEquals("sub-2", remaining.single().subscriptionId)
    }
}
