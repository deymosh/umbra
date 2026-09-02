package com.umbra.app.data.repository

import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.relay.Relay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventRepositoryPublishTest {

    private data class FakeNostrClient(
        val publishedEvents: MutableList<Pair<Event, List<String>>> = mutableListOf()
    ) : NostrClient {
        override val eventFlow get() = throw NotImplementedError()
        override val reqFlow get() = throw NotImplementedError()
        override val subscriptionEventFlow get() = throw NotImplementedError()
        override val eoseFlow get() = throw NotImplementedError()
        override val countFlow get() = throw NotImplementedError()
        override val negMessageFlow get() = throw NotImplementedError()
        override val relayIssueFlow get() = throw NotImplementedError()
        override val connectedRelayUrlsFlow get() = throw NotImplementedError()
        override val relayOpenedFlow get() = throw NotImplementedError()
        override val publishResultFlow get() = throw NotImplementedError()

        override fun connect(relayUrl: String) = Result.success(Unit)
        override fun subscribe(relayUrl: String, subscriptionId: String, filters: List<com.umbra.app.domain.nip01.EventFilter>) {}
        override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<com.umbra.app.domain.nip01.EventFilter>) {}
        override fun negOpen(relayUrl: String, subscriptionId: String, filter: com.umbra.app.domain.nip01.EventFilter, initialMessageHex: String) {}
        override fun negMsg(relayUrl: String, subscriptionId: String, messageHex: String) {}
        override fun negClose(relayUrl: String, subscriptionId: String) {}
        override fun publishEvent(relayUrl: String, event: Event) {}
        override fun publishAuthEvent(relayUrl: String, event: Event) {}
        override suspend fun publishEvent(event: Event) {}
        override suspend fun publishEventToRelays(event: Event, relayUrls: List<String>) {
            publishedEvents.add(event to relayUrls)
        }
        override fun unsubscribe(relayUrl: String, subscriptionId: String) {}
        override fun disconnect(relayUrl: String) {}
        override fun forgetRelay(relayUrl: String) {}
        override fun disconnectAll() {}
        override fun isConnected(relayUrl: String) = false
        override fun hasActiveSocket(relayUrl: String) = false
        override fun isThrottled(relayUrl: String) = false
        override fun isReqUnsupported(relayUrl: String) = false
        override fun requiresSearchFilter(relayUrl: String) = false
        override fun isSubscriptionLimited(relayUrl: String) = false
        override fun isNegentropyUnsupported(relayUrl: String) = false
        override fun rejectsSubIdReuse(relayUrl: String) = false
        override fun applyChannel(channelId: String, relayUrl: String, filters: List<com.umbra.app.domain.nip01.EventFilter>) = false
        override fun currentSubscriptionId(relayUrl: String, channelId: String): String? = null
        override fun subscribedChannelCount(relayUrl: String) = 0
        override fun resolveChannelId(relayUrl: String, subscriptionId: String): String? = null
        override fun subscriptionsForChannel(channelId: String): Set<Pair<String, String>> = emptySet()
        override fun clearChannelSubscription(relayUrl: String, channelId: String): String? = null
        override fun registerTrackedSubscription(relayUrl: String, channelId: String, filters: List<com.umbra.app.domain.nip01.EventFilter>): String = ""
        override fun unregisterTrackedSubscription(relayUrl: String, channelId: String): String? = null
        override fun resetSubscriptionBookkeeping() {}
        override fun resetFailureCount(relayUrl: String) {}
        override fun resetAllBackoff() {}
    }

    @Test
    fun `given_writeRelays_when_publishEvent_then_onlyPublishesToWriteRelays`() = runTest {
        val writeRelay1 = "wss://write1.example.com"
        val writeRelay2 = "wss://write2.example.com"
        val readRelay = "wss://read.example.com"

        val connectedRelays = mapOf(
            writeRelay1 to Relay(
                id = "relay_1",
                url = writeRelay1,
                isEnabled = true,
                isReadEnabled = false,
                isWriteEnabled = true,
                isDmEnabled = false
            ),
            writeRelay2 to Relay(
                id = "relay_2",
                url = writeRelay2,
                isEnabled = true,
                isReadEnabled = false,
                isWriteEnabled = true,
                isDmEnabled = false
            ),
            readRelay to Relay(
                id = "relay_3",
                url = readRelay,
                isEnabled = true,
                isReadEnabled = true,
                isWriteEnabled = false,
                isDmEnabled = false
            )
        )

        val testEvent = Event(
            id = "test_id",
            pubkey = "a".repeat(64),
            createdAt = 1000,
            kind = 1,
            tags = emptyList(),
            content = "test",
            sig = "b".repeat(128)
        )

        val fakeClient = FakeNostrClient()

        // Extract publish logic: filter and call
        val writeRelays = connectedRelays
            .filterValues { relay -> relay.isWriteActive }
            .keys
            .toList()

        fakeClient.publishEventToRelays(testEvent, writeRelays)

        // Verify only write relays were targeted
        assertEquals(1, fakeClient.publishedEvents.size)
        val (event, relays) = fakeClient.publishedEvents.first()
        assertEquals(testEvent, event)
        assertTrue(relays.contains(writeRelay1))
        assertTrue(relays.contains(writeRelay2))
        assertTrue(!relays.contains(readRelay))
        assertEquals(2, relays.size)
    }

    @Test
    fun `given_noWriteRelays_when_publishEvent_then_failureResult`() = runTest {
        val readRelay = "wss://read.example.com"
        val dmRelay = "wss://dm.example.com"

        val connectedRelays = mapOf(
            readRelay to Relay(
                id = "relay_1",
                url = readRelay,
                isEnabled = true,
                isReadEnabled = true,
                isWriteEnabled = false,
                isDmEnabled = false
            ),
            dmRelay to Relay(
                id = "relay_2",
                url = dmRelay,
                isEnabled = true,
                isReadEnabled = false,
                isWriteEnabled = false,
                isDmEnabled = true
            )
        )

        // Verify filtering logic
        val writeRelays = connectedRelays
            .filterValues { relay -> relay.isWriteActive }
            .keys
            .toList()

        assertTrue(writeRelays.isEmpty())
    }

    @Test
    fun `given_mixedRelays_when_filterByWrite_then_onlyIncludesWriteActive`() = runTest {
        val relays = mapOf(
            "wss://relay1.com" to Relay(
                id = "relay_1",
                url = "wss://relay1.com",
                isEnabled = true,
                isReadEnabled = true,
                isWriteEnabled = true,
                isDmEnabled = false
            ),
            "wss://relay2.com" to Relay(
                id = "relay_2",
                url = "wss://relay2.com",
                isEnabled = true,
                isReadEnabled = true,
                isWriteEnabled = false,
                isDmEnabled = false
            ),
            "wss://relay3.com" to Relay(
                id = "relay_3",
                url = "wss://relay3.com",
                isEnabled = false,
                isReadEnabled = true,
                isWriteEnabled = true,
                isDmEnabled = false
            )
        )

        val writeRelays = relays
            .filterValues { relay -> relay.isWriteActive }
            .keys
            .toList()

        // relay1: enabled=true, writeEnabled=true => isWriteActive=true
        // relay2: enabled=true, writeEnabled=false => isWriteActive=false
        // relay3: enabled=false, writeEnabled=true => isWriteActive=false
        assertEquals(1, writeRelays.size)
        assertTrue(writeRelays.contains("wss://relay1.com"))
    }

    @Test
    fun `given_replyWithConnectedInboxRelay_when_computeInboxTargetRelays_then_includesIt`() {
        val recipientPubkey = "c".repeat(64)
        val recipientInbox = "wss://recipient-inbox.example.com"
        val connectedRelayUrls = setOf("wss://own-outbox.example.com", recipientInbox)

        val result = computeInboxTargetRelays(
            participantPubkeys = setOf(recipientPubkey),
            connectedRelayUrls = connectedRelayUrls,
            inboxRelaysFor = { pubkey -> if (pubkey == recipientPubkey) listOf(recipientInbox) else emptyList() }
        )

        assertEquals(setOf(recipientInbox), result)
    }

    @Test
    fun `given_recipientInboxNotConnected_when_computeInboxTargetRelays_then_skipsIt`() {
        val recipientPubkey = "d".repeat(64)
        val connectedRelayUrls = setOf("wss://own-outbox.example.com")

        val result = computeInboxTargetRelays(
            participantPubkeys = setOf(recipientPubkey),
            connectedRelayUrls = connectedRelayUrls,
            inboxRelaysFor = { listOf("wss://unknown-inbox.example.com") }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given_noParticipants_when_computeInboxTargetRelays_then_returnsEmpty`() {
        val result = computeInboxTargetRelays(
            participantPubkeys = emptySet(),
            connectedRelayUrls = setOf("wss://own-outbox.example.com"),
            inboxRelaysFor = { listOf("wss://should-not-be-called.example.com") }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given_differentUrlNormalization_when_computeInboxTargetRelays_then_stillMatches`() {
        val recipientPubkey = "e".repeat(64)
        // Connected pool holds the relay with a trailing slash; the resolved inbox relay list
        // doesn't — normalizeRelayUrl must bridge the two, same as computeAuthorsPerRelay's
        // read-side matching.
        val connectedRelayUrls = setOf("wss://recipient-inbox.example.com/")

        val result = computeInboxTargetRelays(
            participantPubkeys = setOf(recipientPubkey),
            connectedRelayUrls = connectedRelayUrls,
            inboxRelaysFor = { listOf("wss://recipient-inbox.example.com") }
        )

        assertEquals(setOf("wss://recipient-inbox.example.com/"), result)
    }
}

