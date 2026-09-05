package com.umbra.app.data.nostr

import com.umbra.app.domain.relay.RelayIssueKind
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [UmbraNostrClient]'s own connection-lifecycle bookkeeping directly -- the
 * superseded-socket identity check in `onWebSocketOpen` (RelayMessageHandling.kt) -- with no
 * transport call and no relay dial. The client is constructed the same way
 * [RelayWebSocketListenerTest] already does (a real [OkHttpClient] and [OrBotConnectivityCheck],
 * neither ever asked to perform I/O here), and the only host literal anywhere in this file is the
 * reserved, non-resolvable `relay.invalid`.
 */
class UmbraNostrClientTest {

    private val testRelayUrl = "wss://relay.invalid"

    private fun subject() = UmbraNostrClient(OkHttpClient(), OrBotConnectivityCheck())

    private fun switchingProtocolsResponse(): Response {
        val request = Request.Builder().url("https://relay.invalid").build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
    }

    /** Records every close() call instead of performing any real transport action. */
    private class RecordingWebSocket : WebSocket {
        var closeCalls = 0
            private set
        var lastCloseCode: Int? = null
            private set

        override fun request() = throw NotImplementedError("not used by this test")
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun cancel() {}
        override fun close(code: Int, reason: String?): Boolean {
            closeCalls++
            lastCloseCode = code
            return true
        }
    }

    @Test
    fun `given a superseded socket when its open callback arrives late then it is closed and the relay is not marked active`() {
        val client = subject()
        val socketA = RecordingWebSocket()
        val socketB = RecordingWebSocket()
        client.webSockets[testRelayUrl] = socketA

        client.onWebSocketOpen(testRelayUrl, socketB, switchingProtocolsResponse())

        assertEquals(1, socketB.closeCalls)
        assertEquals(1000, socketB.lastCloseCode)
        assertFalse(client.activeRelayUrls.contains(testRelayUrl))
        assertEquals(socketA, client.webSockets[testRelayUrl])
        assertTrue(
            client.relayIssueFlow.replayCache.none {
                it.kind == RelayIssueKind.CONNECTED && it.relayUrl == testRelayUrl
            }
        )
    }

    @Test
    fun `given the current socket when its open callback arrives then the relay is marked active and its failure backoff is cleared`() {
        val client = subject()
        val socketA = RecordingWebSocket()
        client.webSockets[testRelayUrl] = socketA
        client.relayFailureCount[testRelayUrl] = 3
        client.relayCooldownUntil[testRelayUrl] = System.currentTimeMillis() + 60_000L

        client.onWebSocketOpen(testRelayUrl, socketA, switchingProtocolsResponse())

        assertTrue(client.activeRelayUrls.contains(testRelayUrl))
        assertEquals(0, socketA.closeCalls)
        assertNull(client.relayFailureCount[testRelayUrl])
        assertNull(client.relayCooldownUntil[testRelayUrl])
        assertTrue(
            client.relayIssueFlow.replayCache.any {
                it.kind == RelayIssueKind.CONNECTED && it.relayUrl == testRelayUrl
            }
        )
    }
}
