package com.umbra.app.data.network

import com.umbra.app.domain.model.RelayTraffic
import com.umbra.app.domain.relay.normalizeRelayUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-only byte counters for everything that goes through the Tor client: relay WebSocket
 * frames (recorded by the relay client) and HTTP bodies (recorded by [TrafficCountingInterceptor]).
 * Lock-free and in memory — nothing here is persisted.
 */
@Singleton
class TrafficMeter @Inject constructor() {

    private class RelayCounters {
        val sent = AtomicLong()
        val received = AtomicLong()
        val messagesSent = AtomicLong()
        val messagesReceived = AtomicLong()
    }

    private val relays = ConcurrentHashMap<String, RelayCounters>()
    private val media = AtomicLong()
    private val otherHttp = AtomicLong()
    private val httpSent = AtomicLong()

    @Volatile
    var sessionStartedAtMillis: Long = System.currentTimeMillis()
        private set

    fun recordRelaySent(relayUrl: String, bytes: Int) {
        val counters = relays.getOrPut(normalizeRelayUrl(relayUrl)) { RelayCounters() }
        counters.sent.addAndGet(bytes.toLong())
        counters.messagesSent.incrementAndGet()
    }

    fun recordRelayReceived(relayUrl: String, bytes: Int) {
        val counters = relays.getOrPut(normalizeRelayUrl(relayUrl)) { RelayCounters() }
        counters.received.addAndGet(bytes.toLong())
        counters.messagesReceived.incrementAndGet()
    }

    fun recordHttpReceived(isMedia: Boolean, bytes: Long) {
        if (bytes <= 0) return
        (if (isMedia) media else otherHttp).addAndGet(bytes)
    }

    fun recordHttpSent(bytes: Long) {
        if (bytes > 0) httpSent.addAndGet(bytes)
    }

    fun relaySnapshot(): List<RelayTraffic> = relays.map { (url, c) ->
        RelayTraffic(url, c.sent.get(), c.received.get(), c.messagesSent.get(), c.messagesReceived.get())
    }.sortedByDescending { it.totalBytes }

    fun mediaBytes(): Long = media.get()
    fun otherHttpBytes(): Long = otherHttp.get()
    fun httpSentBytes(): Long = httpSent.get()

    /** Sum of every counter, for the persisted lifetime total. */
    fun totalBytes(): Long =
        relays.values.sumOf { it.sent.get() + it.received.get() } + media.get() + otherHttp.get() + httpSent.get()

    fun reset() {
        relays.clear()
        media.set(0)
        otherHttp.set(0)
        httpSent.set(0)
        sessionStartedAtMillis = System.currentTimeMillis()
    }
}
