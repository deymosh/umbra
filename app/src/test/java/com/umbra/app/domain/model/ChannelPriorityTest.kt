package com.umbra.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPriorityTest {

    @Test
    fun `given essential channels when checking then all are essential`() {
        assertTrue(ChannelPriority.isEssential(NostrChannels.FEED_NOTES))
        assertTrue(ChannelPriority.isEssential(NostrChannels.INBOX_NOTES))
        assertTrue(ChannelPriority.isEssential(NostrChannels.OUTBOX_NOTES))
        assertTrue(ChannelPriority.isEssential(NostrChannels.OUTBOX_PROFILE))
        assertTrue(ChannelPriority.isEssential(NostrChannels.DEFAULT_EVENTS))
    }

    @Test
    fun `given background channels when checking then none are essential`() {
        assertFalse(ChannelPriority.isEssential(NostrChannels.FEED_PROFILES_ONDEMAND))
        assertFalse(ChannelPriority.isEssential(NostrChannels.FEED_OUTBOX_SWEEP))
        assertFalse(ChannelPriority.isEssential(NostrChannels.SEARCH))
        assertFalse(ChannelPriority.isEssential(NostrChannels.profileBackfillNotes("a".repeat(64))))
        assertFalse(ChannelPriority.isEssential(NostrChannels.EVENT_LOOKUP))
    }
}
