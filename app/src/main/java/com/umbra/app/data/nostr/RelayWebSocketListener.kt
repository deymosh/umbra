package com.umbra.app.data.nostr

import com.umbra.app.util.LogScrubber.scrubUrlForLogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * OkHttp [WebSocketListener] for one relay connection. Previously a `private inner class` of
 * [UmbraNostrClient] itself (`WebSocketListenerImpl`) — extracted to its own file alongside
 * [RelayMessageHandling] purely for readability, since [UmbraNostrClient] isn't `open` and has no
 * subclass, so nothing here needs inheritance; it just needs [UmbraNostrClient]'s state to be
 * `internal` rather than `private`/`protected` (see that class's own doc comments).
 *
 * [onMessage] does NOT parse/dispatch inline on the OkHttp reader thread the way the old
 * `WebSocketListenerImpl` did — it only enqueues the raw text into [incoming], a per-connection
 * `Channel.UNLIMITED` drained by a single dedicated coroutine (below). This matches Amethyst's
 * `BasicOkHttpWebSocket` exactly, including its choice of `Channel.UNLIMITED` over a bounded
 * channel: a WebSocket's frame delivery runs on that socket's own dedicated OkHttp reader thread,
 * not on the shared `@Named("tor") OkHttpClient`'s `Dispatcher` call-executor pool (the pool
 * `NetworkModule.provideTorOkHttpClient()` already tunes and whose past exhaustion incident is
 * documented there) — so a slow drain for one relay's queue never blocks or delays any other
 * relay's connection or that shared dispatcher. Umbra also already connects to more relays in
 * practice than Amethyst typically does, under today's fully synchronous (pre-this-change)
 * handling, without issue — undercutting any argument that Umbra's smaller scale needs a more
 * conservative, bounded design than Amethyst's own proven choice.
 */
internal class RelayWebSocketListener(
    private val relayUrl: String,
    private val isOnion: Boolean,
    private val client: UmbraNostrClient,
    private val scope: CoroutineScope
) : WebSocketListener() {

    // One queue per connected relay — a fresh instance is created each time this listener is
    // constructed for a new socket (see UmbraNostrClient.connect()), not a single app-wide queue.
    private val incoming = Channel<String>(capacity = Channel.UNLIMITED)

    init {
        // Dispatchers.Default (not IO): JSON parsing/dispatch is CPU-bound, matching this
        // codebase's existing EventCrypto.verifyEvent's withContext(Dispatchers.Default)
        // convention elsewhere.
        scope.launch(Dispatchers.Default) {
            for (text in incoming) {
                runCatching { client.onWebSocketMessage(relayUrl, text) }
                    .onFailure { e ->
                        client.logger.e(e) { "Error processing message from ${scrubUrlForLogs(relayUrl)}" }
                    }
            }
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        client.onWebSocketOpen(relayUrl, webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        incoming.trySend(text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        client.onWebSocketClosing(relayUrl, webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        incoming.close()
        client.onWebSocketClosed(relayUrl, webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        incoming.close()
        client.onWebSocketFailure(relayUrl, webSocket, t, response)
    }
}
