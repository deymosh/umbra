package com.umbra.app.data.repository.policy

import com.umbra.app.domain.model.ChannelPriority

/**
 * Demand-driven disconnect, scoped deliberately narrow: only `isDiscovered` relays (added solely
 * to reach specific tracked authors' outbox) are ever eligible — the user's
 * own outbox/inbox/DM relays are the feed's backbone and always stay connected, full stop. A
 * discovered relay whose covered author(s) haven't been worth a REQ in a while frees its
 * connection; `EventRepositoryImpl.connectToEnabledRelays()`'s normal logic reconnects it
 * on-demand the next time that author's content is actually requested again.
 */
internal object DiscoveredRelayIdlePolicy {
    fun isEligibleForIdleDisconnect(
        isDiscovered: Boolean,
        lastNeededAtMillis: Long,
        nowMillis: Long,
        graceMs: Long
    ): Boolean {
        if (!isDiscovered) return false
        return nowMillis - lastNeededAtMillis >= graceMs
    }

    /**
     * Whether a REQ sent to a discovered relay for [channelId] reflects *specific* need for that
     * relay, and should therefore reset its idle clock (see [isEligibleForIdleDisconnect]) — as
     * opposed to the essential *standing* broadcast channels (INBOX_NOTES, OUTBOX_NOTES/PROFILE,
     * DEFAULT_EVENTS) that `canApplyChannelToRelay` sends to every `isDiscovered` relay
     * unconditionally, regardless of whether it covers anyone relevant. Letting those reset the
     * clock made a discovered relay look permanently "needed" and defeated the idle sweep in
     * practice. [preciselyRoutedChannelIds] are channels already scoped down to authors this exact
     * relay covers (see `EventRepositoryImpl.routeFiltersPrecisely`) — those always count even
     * though e.g. FEED_NOTES is also "essential".
     */
    fun reflectsSpecificNeed(channelId: String, preciselyRoutedChannelIds: Set<String>): Boolean =
        channelId in preciselyRoutedChannelIds || !ChannelPriority.isEssential(channelId)
}
