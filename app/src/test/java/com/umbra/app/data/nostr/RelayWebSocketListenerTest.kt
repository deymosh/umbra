package com.umbra.app.data.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RelayWebSocketListener] deliberately drains its per-connection channel on `Dispatchers.Default`
 * regardless of the [CoroutineScope] it's given (see its own doc comment) — so this test uses
 * [runBlocking] plus a bounded real-time poll rather than `runTest`'s virtual-time scheduler,
 * which only controls coroutines running on the test dispatcher, not ones explicitly launched on
 * a different one.
 */
class RelayWebSocketListenerTest {

    private val fakeWebSocket = object : WebSocket {
        override fun request() = throw NotImplementedError("not used by RelayWebSocketListener")
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private fun eventFrame(id: String) =
        """["EVENT","sub1",{"id":"$id","pubkey":"pk","created_at":1,"kind":1,"tags":[],"content":"","sig":"sig"}]"""

    @Test
    fun `given a burst of EVENT frames when delivered then all arrive on eventFlow in send order`() = runBlocking {
        val client = UmbraNostrClient(OkHttpClient(), OrBotConnectivityCheck())
        val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listener = RelayWebSocketListener(
            relayUrl = "wss://relay.example",
            isOnion = false,
            client = client,
            scope = listenerScope
        )

        val received = mutableListOf<String>()
        val collectJob = launch(Dispatchers.Default) {
            client.eventFlow.collect { (_, event) -> received.add(event.id) }
        }

        val ids = (1..20).map { it.toString(16).padStart(64, '0') }
        ids.forEach { id -> listener.onMessage(fakeWebSocket, eventFrame(id)) }

        withTimeout(5_000) {
            while (received.size < ids.size) delay(10)
        }

        collectJob.cancel()
        listenerScope.cancel()

        assertEquals(ids, received)
    }
}
