package com.umbra.app.data.repository.policy

import com.umbra.app.domain.model.NostrChannels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredRelayIdlePolicyTest {

    @Test
    fun `given a non-discovered relay when checking eligibility then never eligible regardless of idle time`() {
        assertFalse(
            DiscoveredRelayIdlePolicy.isEligibleForIdleDisconnect(
                isDiscovered = false,
                lastNeededAtMillis = 0L,
                nowMillis = Long.MAX_VALUE,
                graceMs = 1L
            )
        )
    }

    @Test
    fun `given a discovered relay idle for less than the grace period when checking eligibility then not eligible`() {
        val lastNeeded = 100_000L
        val grace = 45 * 60_000L
        assertFalse(
            DiscoveredRelayIdlePolicy.isEligibleForIdleDisconnect(
                isDiscovered = true,
                lastNeededAtMillis = lastNeeded,
                nowMillis = lastNeeded + grace - 1,
                graceMs = grace
            )
        )
    }

    @Test
    fun `given a discovered relay idle for at least the grace period when checking eligibility then eligible`() {
        val lastNeeded = 100_000L
        val grace = 45 * 60_000L
        assertTrue(
            DiscoveredRelayIdlePolicy.isEligibleForIdleDisconnect(
                isDiscovered = true,
                lastNeededAtMillis = lastNeeded,
                nowMillis = lastNeeded + grace,
                graceMs = grace
            )
        )
        assertTrue(
            DiscoveredRelayIdlePolicy.isEligibleForIdleDisconnect(
                isDiscovered = true,
                lastNeededAtMillis = lastNeeded,
                nowMillis = lastNeeded + grace + 1_000L,
                graceMs = grace
            )
        )
    }

    @Test
    fun `given a standing broadcast channel when checking specific need then does not reflect specific need`() {
        // INBOX_NOTES/OUTBOX_NOTES/OUTBOX_PROFILE/DEFAULT_EVENTS apply to every isDiscovered relay
        // unconditionally (see canApplyChannelToRelay) — reaching a relay via one of these says
        // nothing about whether that relay actually covers anyone relevant.
        assertFalse(
            DiscoveredRelayIdlePolicy.reflectsSpecificNeed(
                channelId = NostrChannels.INBOX_NOTES,
                preciselyRoutedChannelIds = setOf(NostrChannels.FEED_NOTES, NostrChannels.FEED_PROFILES_ONDEMAND)
            )
        )
    }

    @Test
    fun `given a precisely-routed channel when checking specific need then reflects specific need`() {
        // FEED_NOTES is both "essential" and precisely-routed — routeFiltersPrecisely already
        // scoped it down to authors this exact relay covers, so it must still count.
        assertTrue(
            DiscoveredRelayIdlePolicy.reflectsSpecificNeed(
                channelId = NostrChannels.FEED_NOTES,
                preciselyRoutedChannelIds = setOf(NostrChannels.FEED_NOTES, NostrChannels.FEED_PROFILES_ONDEMAND)
            )
        )
    }

    @Test
    fun `given a bounded on-demand channel when checking specific need then reflects specific need`() {
        assertTrue(
            DiscoveredRelayIdlePolicy.reflectsSpecificNeed(
                channelId = NostrChannels.EVENT_LOOKUP,
                preciselyRoutedChannelIds = setOf(NostrChannels.FEED_NOTES, NostrChannels.FEED_PROFILES_ONDEMAND)
            )
        )
    }
}
