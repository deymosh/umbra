package com.umbra.app.domain.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayRequestInfoGroupingTest {

    private fun request(subId: String, type: SubscriptionType) = RelayRequestInfo(
        relayUrl = "wss://a.relay",
        subscriptionId = subId,
        type = type
    )

    @Test
    fun `given subscriptions of every family when grouping by purpose then each lands in its own bucket`() {
        val requests = listOf(
            request("id1", SubscriptionType.OUTBOX_NOTES),
            request("id2", SubscriptionType.INBOX_NOTES),
            request("id3", SubscriptionType.FEED_NOTES),
            request("id4", SubscriptionType.EVENT_LOOKUP)
        )

        val grouped = requests.groupByPurpose()

        assertEquals(listOf(requests[0]), grouped.outbox)
        assertEquals(listOf(requests[1]), grouped.inbox)
        assertEquals(listOf(requests[2]), grouped.feed)
        assertEquals(listOf(requests[3]), grouped.other)
    }

    @Test
    fun `given a random wire-level subscription id when grouping then classification comes from type not from the id string`() {
        // subscriptionId is now pure random and carries no "outbox-"/"inbox-"/"feed-" prefix to
        // parse — grouping must rely entirely on the resolved type, not the id's text.
        val request = request(subId = "q7x2mp", type = SubscriptionType.OUTBOX_NOTES)

        val grouped = listOf(request).groupByPurpose()

        assertEquals(listOf(request), grouped.outbox)
    }
}
