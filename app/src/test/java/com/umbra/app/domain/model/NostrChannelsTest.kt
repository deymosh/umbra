package com.umbra.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NostrChannelsTest {

    @Test
    fun `given the event lookup channel id when read then it is fixed, not per-event id`() {
        // Deliberately fixed rather than derived from any single event id — every
        // fetchEventById() call shares this one channel so relays get one pooled REQ instead of
        // one REQ per id (see EventRepositoryImpl's pendingEventLookupIds pool).
        assertEquals("event-lookup", NostrChannels.EVENT_LOOKUP)
    }

    @Test
    fun `given different pubkeys when profileBackfillNotes then returns different channel ids`() {
        val first = NostrChannels.profileBackfillNotes("a".repeat(64))
        val second = NostrChannels.profileBackfillNotes("b".repeat(64))

        assertNotEquals(first, second)
    }
}
