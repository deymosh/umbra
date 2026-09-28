package com.umbra.app.domain.model

/** One relay's WebSocket traffic this session. */
data class RelayTraffic(
    val relayUrl: String,
    val bytesSent: Long,
    val bytesReceived: Long,
    val messagesSent: Long,
    val messagesReceived: Long
) {
    val totalBytes: Long get() = bytesSent + bytesReceived
}

/**
 * Everything Umbra has sent and received through Tor. Per-relay detail covers the current
 * session only and never touches storage; [lifetimeBytes] is a single persisted counter.
 */
data class NetworkUsageSnapshot(
    val sessionStartedAtMillis: Long,
    val relays: List<RelayTraffic>,
    val mediaBytesReceived: Long,
    val otherHttpBytesReceived: Long,
    val httpBytesSent: Long,
    val lifetimeBytes: Long
) {
    val relayBytes: Long get() = relays.sumOf { it.totalBytes }
    val sessionBytes: Long get() = relayBytes + mediaBytesReceived + otherHttpBytesReceived + httpBytesSent
}
