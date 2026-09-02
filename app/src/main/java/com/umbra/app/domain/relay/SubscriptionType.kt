package com.umbra.app.domain.relay

import com.umbra.app.domain.model.NostrChannels

/**
 * Coarse bucket [SubscriptionType] rolls up into for section grouping (see [groupByPurpose] in
 * RelayRequestInfo.kt) — independent of the finer per-type icon/label shown on each
 * SubscriptionCard.
 */
enum class SubscriptionFamily { OUTBOX, INBOX, FEED, OTHER }

/**
 * Umbra's internal taxonomy of *why* a subscription exists — resolved from the internal channel
 * id (see [fromChannelId]), never from the wire-level subscription id itself. Subscription ids
 * sent to relays are pure random ([randomSubscriptionId]) and carry none of this; [icon] is a
 * plain emoji (same convention as the existing filter chips in RelayConfigScreen's
 * FilterChipRow — 👤 🆔 🔍 — not an androidx ImageVector) so this stays framework-free, since
 * domain/ cannot import androidx or android packages.
 */
enum class SubscriptionType(val icon: String, val family: SubscriptionFamily) {
    OUTBOX_PROFILE("👤", SubscriptionFamily.OUTBOX),
    /** Carries both the user's own notes/deletions and reactions/reposts (two filters, one REQ). */
    OUTBOX_NOTES("📤", SubscriptionFamily.OUTBOX),
    /** Carries both notes/deletions and reactions/reposts that #p-tag the user (two filters, one REQ). */
    INBOX_NOTES("📥", SubscriptionFamily.INBOX),
    FEED_NOTES("🧵", SubscriptionFamily.FEED),
    FEED_PROFILES_ONDEMAND("🧵", SubscriptionFamily.FEED),
    /** Standing watch for future profile updates from authors already hydrated on screen. */
    FEED_PROFILES("🧵", SubscriptionFamily.FEED),
    FEED_OUTBOX_SWEEP("🧵", SubscriptionFamily.FEED),

    /**
     * NIP-50 note search — one stable channel reused across queries while the search panel stays
     * open: a new query replaces the previous one's filter on the same REQ rather than opening a
     * concurrent subscription per query. Closed only when the panel itself closes, not on
     * EOSE/timeout (see EventRepositoryImpl.searchNotes).
     */
    SEARCH_NOTES("🔍", SubscriptionFamily.OTHER),

    /** NIP-50 profile search — reserved, no channel emits this yet. */
    SEARCH_PROFILES("🔍", SubscriptionFamily.OTHER),

    /** Pooled: every fetchEventById() lookup shares one channel (see NostrChannels.EVENT_LOOKUP). */
    EVENT_LOOKUP("🎯", SubscriptionFamily.OTHER),

    /** Reserved — no channel emits this yet (fetching reactions/reposts for one specific event). */
    EVENT_INTERACTIONS("💬", SubscriptionFamily.OTHER),

    /**
     * Per-pubkey NOTES backfill for an actively-open profile screen: fired when the notes/replies
     * scroll runs out (reaches the last loaded item) and/or there are no notes loaded yet at all
     * — i.e. "fetch more of this author's *content*". Distinct from [PROFILE_LOOKUP] (that same
     * profile's metadata/relay-lists/follows, plus any other author's one-shot profile lookup),
     * which is "fetch who this author *is*", not their notes.
     */
    PROFILE_BACKFILL("🗂️", SubscriptionFamily.OTHER),

    /**
     * One-shot profile metadata lookup (kinds 0/3/10000/10002/10050) for an author we don't have
     * cached yet — the event-lookup analog for a whole author instead of a single event id. Covers
     * both the background referenced-author hydration batch (TrackReferencedAuthorUseCase, e.g. an
     * unfamiliar author spotted in the feed) and an actively-open profile screen's own
     * metadata/relay-list/follows channels (see [PROFILE_BACKFILL] for that same screen's separate
     * notes-fetching channel).
     */
    PROFILE_LOOKUP("🔎", SubscriptionFamily.OTHER),

    /** NIP-45 COUNT — reserved; requestCount() is fire-and-forget and never enters this tracking. */
    COUNT("🔢", SubscriptionFamily.OTHER),

    /**
     * NIP-77 Negentropy set-reconciliation handshake (NEG-OPEN/NEG-MSG/NEG-CLOSE) — comparing the
     * local event index against this relay's. Registered via
     * NostrClient.registerTrackedSubscription(), not a REQ (see NostrChannels.NEGENTROPY_SYNC_PREFIX).
     */
    NEGENTROPY_SYNC("🔄", SubscriptionFamily.OTHER),

    /**
     * NIP-77 follow-up REQ fetching the specific ids a [NEGENTROPY_SYNC] reconciliation determined
     * this relay has that Umbra doesn't (see NostrChannels.NEGENTROPY_FETCH_PREFIX).
     */
    NEGENTROPY_FETCH("⬇️", SubscriptionFamily.OTHER),

    /** NostrChannels.DEFAULT_EVENTS — the generic subscribeToEvents() fallback channel. */
    DEFAULT("📡", SubscriptionFamily.OTHER),

    OTHER("❔", SubscriptionFamily.OTHER);

    companion object {
        fun fromChannelId(channelId: String?): SubscriptionType {
            if (channelId == null) return OTHER
            return when {
                channelId == NostrChannels.OUTBOX_PROFILE -> OUTBOX_PROFILE
                channelId == NostrChannels.OUTBOX_NOTES -> OUTBOX_NOTES
                channelId == NostrChannels.INBOX_NOTES -> INBOX_NOTES
                channelId == NostrChannels.FEED_NOTES -> FEED_NOTES
                channelId == NostrChannels.FEED_PROFILES_ONDEMAND -> FEED_PROFILES_ONDEMAND
                channelId == NostrChannels.FEED_PROFILES -> FEED_PROFILES
                channelId == NostrChannels.FEED_OUTBOX_SWEEP -> FEED_OUTBOX_SWEEP
                channelId == NostrChannels.EVENT_LOOKUP -> EVENT_LOOKUP
                channelId == NostrChannels.SELF_PROFILE_BOOTSTRAP -> PROFILE_LOOKUP
                channelId == NostrChannels.DEFAULT_EVENTS -> DEFAULT
                channelId.startsWith(NostrChannels.NEGENTROPY_SYNC_PREFIX) -> NEGENTROPY_SYNC
                channelId.startsWith(NostrChannels.NEGENTROPY_FETCH_PREFIX) -> NEGENTROPY_FETCH
                channelId.startsWith(NostrChannels.SEARCH) -> SEARCH_NOTES
                channelId.startsWith(NostrChannels.REFERENCED_AUTHOR_HYDRATION_PREFIX) -> PROFILE_LOOKUP
                channelId.startsWith(NostrChannels.PROFILE_BACKFILL_NOTES_PREFIX) -> PROFILE_BACKFILL
                channelId.startsWith(NostrChannels.PROFILE_BACKFILL_METADATA_PREFIX) ||
                    channelId.startsWith(NostrChannels.PROFILE_FOLLOWS_META_PREFIX) -> PROFILE_LOOKUP
                else -> OTHER
            }
        }
    }
}
