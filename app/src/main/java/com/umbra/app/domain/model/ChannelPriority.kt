package com.umbra.app.domain.model

/**
 * Ranks Umbra's named channels (see [NostrChannels]) by how essential each one is to keep
 * sending once a relay has told us it's over its concurrent-subscription limit (see
 * `EventRepositoryImpl.applyChannelToRelay()` and `UmbraNostrClient.isSubscriptionLimited()`).
 * A relay this strict is signaling it can only hold a handful of our channels open at once — core
 * note/interaction delivery keeps going, background hydration/sweep/search channels back off
 * instead of fighting for the same limited slots and getting closed repeatedly.
 */
object ChannelPriority {
    private val ESSENTIAL_CHANNEL_IDS = setOf(
        NostrChannels.FEED_NOTES,
        NostrChannels.INBOX_NOTES,
        NostrChannels.OUTBOX_NOTES,
        NostrChannels.OUTBOX_PROFILE,
        NostrChannels.DEFAULT_EVENTS
    )

    /**
     * True for channels that must keep being sent even to a subscription-limited relay (the
     * user's own notes/DMs/interactions); false for background/best-effort channels (on-demand
     * profile hydration, the outbox sweep, search, per-pubkey backfill/lookup) that can be
     * withheld from a relay that's already telling us it's full.
     */
    fun isEssential(channelId: String): Boolean =
        channelId in ESSENTIAL_CHANNEL_IDS
}
