package com.umbra.app.domain.relay

/**
 * A relay's ["OK", eventId, accepted, message] response to a published event — emitted for
 * both acceptance and rejection, unlike [RelayIssue] which only ever represents a problem.
 * Consumed by the broadcast tracker (see domain/broadcast/) to show per-relay publish status.
 */
data class RelayPublishResult(
    val relayUrl: String,
    val eventId: String,
    val accepted: Boolean,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)
