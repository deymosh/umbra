package com.umbra.app.data.nostr

import com.umbra.app.TorProxyConfig
import com.umbra.app.domain.relay.RelayIssueKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * superseded-socket identity check in `onWebSocketOpen` (RelayMessageHandling.kt) and the
 * per-relay in-flight dial guard in [UmbraNostrClient.connect] -- with no transport call and no
 * relay dial. The client is constructed the same way [RelayWebSocketListenerTest] already does (a
 * real [OkHttpClient] and [OrBotConnectivityCheck], neither ever asked to perform I/O here), and
 * the only host literal anywhere in this file is the reserved, non-resolvable `relay.invalid`.
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

    /**
     * Holds `close()` open on a real background thread until the test releases it, so
     * [UmbraNostrClient.connect]'s dial-in-flight guard can be raced by a genuine second thread
     * rather than approximated with two coroutines on a single-threaded test dispatcher -- the
     * dial method is synchronous with no suspension point, so virtual time could never interleave
     * it. `close()` counts down [entered] the instant it is invoked (proving the first dial has
     * reached the guarded region's socket-close step), blocks on [release] until the test lets it
     * go, then throws a marker exception so the dial aborts before reaching the connectivity probe
     * or any real transport call.
     */
    private class LatchBlockingWebSocket(
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : WebSocket {
        override fun request() = throw NotImplementedError("not used by this test")
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun cancel() {}
        override fun close(code: Int, reason: String?): Boolean {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw MarkerAbortException()
        }
    }

    /** Marks the held dial as intentionally aborted -- never a real transport/network failure. */
    private class MarkerAbortException : RuntimeException("aborting before any real transport call")

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

    @Test
    fun `given a dial already in flight for a relay when a second concurrent connect for the same relay runs then it no-ops instead of starting its own dial`() {
        val client = subject()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.webSockets[testRelayUrl] = LatchBlockingWebSocket(entered, release)

        // TorProxyConfig is a process-wide singleton read by connect()'s readiness check, shared
        // by every other test class in this JVM -- it must be restored no matter how this test
        // exits, hence the try/finally.
        try {
            TorProxyConfig.update(TorProxyConfig.DEFAULT_HOST, TorProxyConfig.DEFAULT_PORT)

            val dialThread = Thread { client.connect(testRelayUrl) }
            dialThread.start()

            val reachedGuardedRegion = entered.await(5, TimeUnit.SECONDS)
            assertTrue("first dial never reached the guarded socket-close step", reachedGuardedRegion)

            // The first dial is now provably inside the guarded region (dialingRelays holds the
            // url, and it is blocked inside closeRelaySocket's webSocket.close() call). A second
            // concurrent connect() for the same url must no-op instead of continuing past the
            // guard -- this is the assertion that fails if dialingRelays' guard is removed, since
            // an unguarded second dial would otherwise reach closeRelaySocket/orBotCheck and emit
            // its own CONNECTING/NETWORK issue for this url.
            client.connect(testRelayUrl)

            assertTrue(
                client.relayIssueFlow.replayCache.none { it.relayUrl == testRelayUrl }
            )

            release.countDown()
            dialThread.join(5_000)
            assertFalse("dial thread did not finish in time", dialThread.isAlive)

            // The held dial ended in failure (the marker exception thrown from close()) -- proving
            // the guard is released via the finally block even on the failure path, not just on
            // success.
            assertFalse(client.dialingRelays.contains(testRelayUrl))
        } finally {
            TorProxyConfig.reset()
        }
    }
}
