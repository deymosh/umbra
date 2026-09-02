package com.umbra.app.domain.relay

import com.umbra.app.domain.model.NostrChannels
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionTypeTest {

    @Test
    fun `given a null channel id when resolving type then it falls back to OTHER`() {
        assertEquals(SubscriptionType.OTHER, SubscriptionType.fromChannelId(null))
    }

    @Test
    fun `given the fixed named channel ids when resolving type then each maps to its own exact type`() {
        assertEquals(SubscriptionType.OUTBOX_PROFILE, SubscriptionType.fromChannelId(NostrChannels.OUTBOX_PROFILE))
        assertEquals(SubscriptionType.OUTBOX_NOTES, SubscriptionType.fromChannelId(NostrChannels.OUTBOX_NOTES))
        assertEquals(SubscriptionType.INBOX_NOTES, SubscriptionType.fromChannelId(NostrChannels.INBOX_NOTES))
        assertEquals(SubscriptionType.FEED_NOTES, SubscriptionType.fromChannelId(NostrChannels.FEED_NOTES))
        assertEquals(SubscriptionType.FEED_PROFILES_ONDEMAND, SubscriptionType.fromChannelId(NostrChannels.FEED_PROFILES_ONDEMAND))
        assertEquals(SubscriptionType.FEED_OUTBOX_SWEEP, SubscriptionType.fromChannelId(NostrChannels.FEED_OUTBOX_SWEEP))
        assertEquals(SubscriptionType.EVENT_LOOKUP, SubscriptionType.fromChannelId(NostrChannels.EVENT_LOOKUP))
        // The signed-in user's own cold-start bootstrap hydration (before any relay list is known)
        // is conceptually a PROFILE_LOOKUP too — just always for our own pubkey.
        assertEquals(SubscriptionType.PROFILE_LOOKUP, SubscriptionType.fromChannelId(NostrChannels.SELF_PROFILE_BOOTSTRAP))
        assertEquals(SubscriptionType.DEFAULT, SubscriptionType.fromChannelId(NostrChannels.DEFAULT_EVENTS))
        // SEARCH is a single stable channel id reused across queries (see EventRepositoryImpl.searchNotes) —
        // not a per-query dynamic id, so it resolves by exact match same as the others above.
        assertEquals(SubscriptionType.SEARCH_NOTES, SubscriptionType.fromChannelId(NostrChannels.SEARCH))
    }

    @Test
    fun `given a referenced-author-hydration batch channel id when resolving type then it maps to PROFILE_LOOKUP`() {
        val channelId = NostrChannels.referencedAuthorHydrationBatch("1700000000000")
        assertEquals(SubscriptionType.PROFILE_LOOKUP, SubscriptionType.fromChannelId(channelId))
    }

    @Test
    fun `given an open profile screen's notes channel id when resolving type then it maps to PROFILE_BACKFILL`() {
        val pubkey = "a".repeat(64)
        assertEquals(SubscriptionType.PROFILE_BACKFILL, SubscriptionType.fromChannelId(NostrChannels.profileBackfillNotes(pubkey)))
    }

    @Test
    fun `given an open profile screen's metadata_follows channel ids when resolving type then they map to PROFILE_LOOKUP`() {
        val pubkey = "a".repeat(64)
        assertEquals(SubscriptionType.PROFILE_LOOKUP, SubscriptionType.fromChannelId(NostrChannels.profileBackfillMetadata(pubkey)))
        assertEquals(SubscriptionType.PROFILE_LOOKUP, SubscriptionType.fromChannelId(NostrChannels.profileFollowsMeta(pubkey)))
    }

    @Test
    fun `given an unrecognized channel id when resolving type then it falls back to OTHER instead of vanishing`() {
        assertEquals(SubscriptionType.OTHER, SubscriptionType.fromChannelId("some-future-channel-kind"))
    }
}
