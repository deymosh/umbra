package com.umbra.app.domain.broadcast

import com.umbra.app.domain.nip01.Event

/** Per-relay outcome of one publish attempt within a [BroadcastEvent]. */
enum class RelayBroadcastStatus {
    PENDING,
    RETRYING,
    SUCCESS,
    FAILED,
    TIMEOUT
}

/** Coarse status across every relay a [BroadcastEvent] targets. */
enum class BroadcastStatus {
    IN_PROGRESS,
    SUCCESS,
    PARTIAL,
    FAILED
}

data class RelayBroadcastResult(
    val status: RelayBroadcastStatus,
    val message: String? = null,
    val attempts: Int = 1
)

/**
 * Tracks the outcome, per target relay, of publishing [event]. One instance per publish call —
 * a manual re-publish of the same event creates a new [id], not a mutation of the old one, so a
 * user can see (and dismiss) the earlier attempt's history independently.
 */
data class BroadcastEvent(
    val id: String,
    val event: Event,
    val targetRelays: List<String>,
    val startedAtMs: Long,
    val results: Map<String, RelayBroadcastResult> =
        targetRelays.associateWith { RelayBroadcastResult(RelayBroadcastStatus.PENDING) }
) {
    val totalRelays: Int get() = targetRelays.size

    val successCount: Int get() = results.values.count { it.status == RelayBroadcastStatus.SUCCESS }

    val failedRelayUrls: List<String> get() = results
        .filterValues { it.status == RelayBroadcastStatus.FAILED || it.status == RelayBroadcastStatus.TIMEOUT }
        .keys
        .toList()

    val failureCount: Int get() = failedRelayUrls.size

    /** True once every target relay has a terminal (non-pending, non-retrying) result. */
    val isComplete: Boolean get() = results.size >= targetRelays.size &&
        results.values.none { it.status == RelayBroadcastStatus.PENDING || it.status == RelayBroadcastStatus.RETRYING }

    val progress: Float get() = if (totalRelays == 0) 1f else (totalRelays - pendingOrRetryingCount).toFloat() / totalRelays

    private val pendingOrRetryingCount: Int get() = results.values
        .count { it.status == RelayBroadcastStatus.PENDING || it.status == RelayBroadcastStatus.RETRYING }

    val overallStatus: BroadcastStatus get() = when {
        !isComplete -> BroadcastStatus.IN_PROGRESS
        failureCount == 0 -> BroadcastStatus.SUCCESS
        successCount == 0 -> BroadcastStatus.FAILED
        else -> BroadcastStatus.PARTIAL
    }
}
