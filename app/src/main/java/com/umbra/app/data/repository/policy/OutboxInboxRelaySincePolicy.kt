package com.umbra.app.data.repository.policy

import com.umbra.app.domain.nip01.EventFilter

/**
 * Per-relay `since` watermark for OUTBOX_NOTES/INBOX_NOTES's own live filters: these channels
 * alternate their stored subscription between static base filters and a backward-paginating
 * backfill overlay (see EventRepositoryImpl.applyBackfillOverlay) on every backfill cadence tick,
 * so a relay that has already reported EOSE for this channel once gets asked the exact same
 * since-less/static-since base filters again on every subsequent alternation, redelivering events
 * it already sent. Once a relay has EOSE'd for this channel, its own confirmed-caught-up time is a
 * strictly tighter, still-correct floor — same rationale as [FeedRelaySincePolicy].
 *
 * Unlike [FeedRelaySincePolicy], this DOES add a `since` to a filter that had none — that's exactly
 * OUTBOX_NOTES/INBOX_NOTES's "latest N, no since" live filter once a per-relay watermark exists.
 * Callers must only pass filters with `until == null` — a filter carrying an explicit `until` is
 * the backfill overlay's own backward-window filter, which already has its own correct `since`
 * paired with that `until` and must never be touched by this policy (see
 * EventRepositoryImpl.applyPerRelayOutboxInboxSince's doc comment).
 *
 * Also excludes filters carrying `tagFilters`, defensively mirroring [FeedRelaySincePolicy] — these
 * channels never layer a tag-scoped overlay onto their own/own-interaction filters today, but if
 * one ever does, this guard prevents reproducing the exact bug that motivated the same exclusion
 * there (a fixed, event-id-scoped lookback window silently narrowed by an unrelated sync watermark).
 */
internal object OutboxInboxRelaySincePolicy {
    fun overrideSince(filters: List<EventFilter>, perRelaySince: Long?): List<EventFilter> {
        if (perRelaySince == null) return filters
        return filters.map { filter -> if (filter.tagFilters.isEmpty()) filter.copy(since = perRelaySince) else filter }
    }
}
