package com.umbra.app.data.repository

import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.domain.broadcast.BroadcastStatus
import com.umbra.app.domain.broadcast.RelayBroadcastStatus
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.relay.RelayPublishResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastRepositoryImplTest {

    private class FakeNostrClient : NostrClient {
        val publishedTo = mutableListOf<Pair<String, String>>()

        override val eventFlow get() = throw NotImplementedError()
        override val reqFlow get() = throw NotImplementedError()
        override val subscriptionEventFlow get() = throw NotImplementedError()
        override val eoseFlow get() = throw NotImplementedError()
        override val countFlow get() = throw NotImplementedError()
        override val negMessageFlow get() = throw NotImplementedError()
        override val relayIssueFlow get() = throw NotImplementedError()
        override val connectedRelayUrlsFlow: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val relayOpenedFlow get() = throw NotImplementedError()

        private val _publishResultFlow = MutableSharedFlow<RelayPublishResult>(extraBufferCapacity = 16)
        override val publishResultFlow: SharedFlow<RelayPublishResult> = _publishResultFlow.asSharedFlow()

        override fun connect(relayUrl: String) = Result.success(Unit)
        override fun subscribe(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {}
        override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {}
        override fun negOpen(relayUrl: String, subscriptionId: String, filter: EventFilter, initialMessageHex: String) {}
        override fun negMsg(relayUrl: String, subscriptionId: String, messageHex: String) {}
        override fun negClose(relayUrl: String, subscriptionId: String) {}
        override fun publishEvent(relayUrl: String, event: Event) {
            publishedTo.add(relayUrl to event.id)
        }
        override fun publishAuthEvent(relayUrl: String, event: Event) {}
        override suspend fun publishEvent(event: Event) {}
        override suspend fun publishEventToRelays(event: Event, relayUrls: List<String>) {}
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
        override fun applyChannel(channelId: String, relayUrl: String, filters: List<EventFilter>) = false
        override fun currentSubscriptionId(relayUrl: String, channelId: String): String? = null
        override fun subscribedChannelCount(relayUrl: String) = 0
        override fun resolveChannelId(relayUrl: String, subscriptionId: String): String? = null
        override fun subscriptionsForChannel(channelId: String): Set<Pair<String, String>> = emptySet()
        override fun clearChannelSubscription(relayUrl: String, channelId: String): String? = null
        override fun registerTrackedSubscription(relayUrl: String, channelId: String, filters: List<EventFilter>): String = ""
        override fun unregisterTrackedSubscription(relayUrl: String, channelId: String): String? = null
        override fun resetSubscriptionBookkeeping() {}
        override fun resetFailureCount(relayUrl: String) {}
        override fun resetAllBackoff() {}

        suspend fun emitResult(result: RelayPublishResult) = _publishResultFlow.emit(result)
    }

    private fun testEvent(id: String = "e".repeat(64)) = Event(
        id = id,
        pubkey = "p".repeat(64),
        createdAt = 1000,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = "hi",
        sig = "s".repeat(128)
    )

    @Test
    fun `given_relayAlreadyPublishedByCaller_when_trackPublish_then_doesNotResendOnFirstAttempt`() = runBlocking {
        val client = FakeNostrClient()
        val repo = BroadcastRepositoryImpl(client)
        val event = testEvent()

        repo.trackPublish(event, setOf("wss://a.example.com"))
        delay(150)

        assertTrue(
            "first attempt must not resend — EventRepositoryImpl.publishEvent() already sent it",
            client.publishedTo.isEmpty()
        )
    }

    @Test
    fun `given_relayAcceptsQuickly_when_trackPublish_then_marksSuccessAndComplete`() = runBlocking {
        val client = FakeNostrClient()
        val repo = BroadcastRepositoryImpl(client)
        val event = testEvent()

        repo.trackPublish(event, setOf("wss://a.example.com"))
        client.emitResult(RelayPublishResult("wss://a.example.com", event.id, accepted = true, message = ""))

        // trackPublish() launches its listener coroutine UNDISPATCHED specifically so it's
        // guaranteed attached to publishResultFlow before trackPublish() returns (see its own doc
        // comment) — so emitResult() right above is never racing an as-yet-unscheduled collector.
        // This timeout is just a safety net against a genuine regression, not a race window.
        val broadcast = withTimeout(8000) {
            var latest = repo.activeBroadcasts.value.firstOrNull()
            while (latest == null || !latest.isComplete) {
                delay(20)
                latest = repo.activeBroadcasts.value.firstOrNull()
            }
            latest
        }

        assertEquals(BroadcastStatus.SUCCESS, broadcast.overallStatus)
        assertEquals(RelayBroadcastStatus.SUCCESS, broadcast.results["wss://a.example.com"]?.status)
    }
}
