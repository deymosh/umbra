package com.umbra.app.data.nostr

import com.umbra.app.TorProxyConfig
import com.umbra.app.util.logging.LogScrubber.scrubEndpointForLogs
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies Orbot (SOCKS5) availability before attempting relay connections.
 * Prevents connection failures due to Orbot not running or TOR network initialization.
 */
@Singleton
class OrBotConnectivityCheck @Inject constructor() {
    companion object {
        private const val TAG = "UmbraOrBot"
        private const val PING_TIMEOUT_MS = 3000  // 3 second timeout for ping
        // connectToEnabledRelays() calls this once per relay, sequentially, on every reconnect
        // cycle — but it always probes the same fixed 127.0.0.1:9050 endpoint, so a large relay
        // pool (own relays + auto-discovered outbox relays) turned into N serialized blocking
        // Socket probes for one answer that doesn't vary per relay. Cache the result briefly so
        // only the first check in a connect burst pays the real probe; kept well under
        // waitForOrBot's 500ms poll interval so that loop still re-probes on every iteration.
        private const val CACHE_TTL_MS = 250L
    }

    private val logger = UmbraLog.tag(TAG)

    @Volatile
    private var cachedResult: Boolean? = null
    @Volatile
    private var cachedAtMillis: Long = 0L

    /**
     * Checks if Orbot SOCKS5 server is responding.
     * This is a lightweight connectivity test to avoid long-timeout WebSocket failures.
     *
     * @return true if SOCKS5 server at 127.0.0.1:9050 responds, false otherwise
     */
    fun isOrBotAvailable(): Boolean {
        val now = System.currentTimeMillis()
        cachedResult?.let { cached ->
            if (now - cachedAtMillis < CACHE_TTL_MS) return cached
        }

        val host = TorProxyConfig.host
        val port = TorProxyConfig.port
        val result = try {
            Socket().use { socket ->
                socket.soTimeout = PING_TIMEOUT_MS
                socket.connect(
                    InetSocketAddress(host, port),
                    PING_TIMEOUT_MS
                )
                logger.d { "Orbot SOCKS5 ping successful: ${scrubEndpointForLogs(host, port)}" }
                true
            }
        } catch (e: Exception) {
            logger.d { "Orbot SOCKS5 ping failed for ${scrubEndpointForLogs(host, port)} (${e.javaClass.simpleName}: ${scrubThrowableMessageForLogs(e)})" }
            false
        }
        cachedResult = result
        cachedAtMillis = now
        return result
    }

    /**
     * Waits (with polling) for Orbot to become available.
     * Used during app startup to ensure TOR network is initialized.
     *
     * @param maxWaitMs Maximum time to wait (default 10 seconds)
     * @param pollIntervalMs Interval between polls (default 500ms)
     * @return true if Orbot became available, false if timeout exceeded
     */
    suspend fun waitForOrBot(
        maxWaitMs: Long = 10_000,
        pollIntervalMs: Long = 500
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (isOrBotAvailable()) {
                logger.d { "Orbot is now available after ${System.currentTimeMillis() - startTime}ms" }
                return true
            }
            kotlinx.coroutines.delay(pollIntervalMs)
        }
        logger.d { "Timeout waiting for Orbot (${maxWaitMs}ms)" }
        return false
    }
}
