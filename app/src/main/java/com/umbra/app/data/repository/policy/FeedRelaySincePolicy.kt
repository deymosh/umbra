package com.umbra.app.data.repository.policy

import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip67.EoseCompleteness

/**
 * Per-relay `since` watermark for the live feed subscription: once a relay has reported EOSE for
 * FEED_NOTES, its own confirmed-caught-up time is a more accurate resume point for that specific
 * relay than one global cursor applied uniformly. Tor's connection-timing variance means a relay
 * that took longer to (re)connect may not have delivered anything past an earlier point than what
 * a "newest cached event across all relays" cursor assumes — using its own last-confirmed time
 * instead avoids silently missing whatever it hasn't actually sent yet.
 */
internal object FeedRelaySincePolicy {
    /**
     * Overrides each filter's `since` with [perRelaySince] when known; leaves filters unchanged
     * (falls back to whatever global cursor they already carry) when this relay has no watermark
     * yet, and never adds a `since` to a filter that didn't already have one.
     *
     * Filters carrying `tagFilters` (e.g. the `#e`-tagged engagement/interactions overlay layered
     * onto this same channel) are excluded from the override — their `since` is an intentional
     * fixed lookback window scoped to specific target event ids, unrelated to when this relay last
     * confirmed a feed sync. Substituting the feed watermark there would silently shrink that
     * window (often to almost nothing) and drop real interactions this relay was never actually
     * asked for.
     */
    fun overrideSince(filters: List<EventFilter>, perRelaySince: Long?): List<EventFilter> {
        if (perRelaySince == null) return filters
        return filters.map { filter ->
            if (filter.since != null && filter.tagFilters.isEmpty()) filter.copy(since = perRelaySince) else filter
        }
    }

    /**
     * NIP-67: whether a FEED_NOTES EOSE should advance this relay's watermark to "now". False
     * when the relay reported [EoseCompleteness.MORE] (it truncated the result set) — advancing
     * the watermark anyway would make the next REQ resume from "now", silently skipping over
     * whatever this relay didn't actually send. True for [EoseCompleteness.FINISH] and
     * [EoseCompleteness.UNSPECIFIED] (today's pre-NIP-67 behavior, unchanged for the overwhelming
     * majority of relays that don't send this hint at all).
     */
    fun shouldAdvanceWatermark(completeness: EoseCompleteness): Boolean =
        completeness != EoseCompleteness.MORE
}
