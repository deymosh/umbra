package com.umbra.app.data.nostr

import com.umbra.app.domain.nip01.EventFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySubscriptionRegistryTest {

    private val relay = "wss://relay.example.com"
    private val otherRelay = "wss://other.example.com"
    private val channel = "feed-notes"
    private val filtersA = listOf(EventFilter(kinds = setOf(1), since = 100))
    private val filtersB = listOf(EventFilter(kinds = setOf(1), since = 200))

    @Test
    fun `given a reuse-friendly relay when getOrCreateSubId is called repeatedly then the same subId is returned`() {
        val registry = RelaySubscriptionRegistry()

        val first = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = false)
        val second = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = false)

        assertEquals(first, second)
    }

    @Test
    fun `given a rejectsSubIdReuse relay when getOrCreateSubId is called repeatedly then a fresh subId is minted every time`() {
        val registry = RelaySubscriptionRegistry()

        val first = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = true)
        val second = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = true)

        assertNotEquals(first, second)
        assertEquals(second, registry.currentSubId(relay, channel))
    }

    @Test
    fun `given a churned subId when resolveChannelId is called for the old id then it still resolves via the stable history stamp`() {
        val registry = RelaySubscriptionRegistry()

        val firstSubId = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = true)
        registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = true)

        assertEquals(channel, registry.resolveChannelId(relay, firstSubId))
        assertNotEquals(firstSubId, registry.currentSubId(relay, channel))
    }

    @Test
    fun `given more than 30 distinct subIds minted for one relay when resolveChannelId is called for the oldest then it is evicted`() {
        val registry = RelaySubscriptionRegistry()

        val mintedIds = (1..31).map { i ->
            registry.getOrCreateSubId(relay, "channel-$i", rejectsSubIdReuse = false)
        }

        assertNull(registry.resolveChannelId(relay, mintedIds.first()))
        assertNotNull(registry.resolveChannelId(relay, mintedIds.last()))
    }

    @Test
    fun `given 31 subIds minted on one relay when resolveChannelId is called on a second relay then its own history is unaffected`() {
        val registry = RelaySubscriptionRegistry()
        repeat(31) { i -> registry.getOrCreateSubId(relay, "channel-$i", rejectsSubIdReuse = false) }
        val otherSubId = registry.getOrCreateSubId(otherRelay, channel, rejectsSubIdReuse = false)

        assertEquals(channel, registry.resolveChannelId(otherRelay, otherSubId))
    }

    @Test
    fun `given identical filters sent twice when hasChanged is checked after recordSent then it reports no change`() {
        val registry = RelaySubscriptionRegistry()

        assertTrue(registry.hasChanged(relay, channel, filtersA))
        registry.recordSent(relay, channel, filtersA)

        assertFalse(registry.hasChanged(relay, channel, filtersA))
    }

    @Test
    fun `given different filters when hasChanged is checked after recordSent then it reports a change`() {
        val registry = RelaySubscriptionRegistry()

        registry.recordSent(relay, channel, filtersA)

        assertTrue(registry.hasChanged(relay, channel, filtersB))
    }

    @Test
    fun `given a send that was never recorded when hasChanged is checked again then it still reports a change`() {
        val registry = RelaySubscriptionRegistry()

        // Simulates a withheld send: hasChanged was true, but recordSent is never called because
        // the caller decided not to actually send (e.g. throttled) — a later retry must not be
        // treated as a no-op.
        assertTrue(registry.hasChanged(relay, channel, filtersA))
        assertTrue(registry.hasChanged(relay, channel, filtersA))
    }

    @Test
    fun `given a recorded fingerprint when clearFingerprint is called then the next check reports a change again`() {
        val registry = RelaySubscriptionRegistry()
        registry.recordSent(relay, channel, filtersA)
        assertFalse(registry.hasChanged(relay, channel, filtersA))

        registry.clearFingerprint(relay)

        assertTrue(registry.hasChanged(relay, channel, filtersA))
    }

    @Test
    fun `given a mapped channel when remove is called then the forward map entry is cleared but the history stamp survives`() {
        val registry = RelaySubscriptionRegistry()
        val subId = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = false)

        val removed = registry.remove(relay, channel)

        assertEquals(subId, removed)
        assertNull(registry.currentSubId(relay, channel))
        assertEquals(channel, registry.resolveChannelId(relay, subId))
    }

    @Test
    fun `given no mapped channel when remove is called then it returns null`() {
        val registry = RelaySubscriptionRegistry()

        assertNull(registry.remove(relay, channel))
    }

    @Test
    fun `given forward map and fingerprint state when forgetRelay is called then both are cleared but history survives`() {
        val registry = RelaySubscriptionRegistry()
        val subId = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = false)
        registry.recordSent(relay, channel, filtersA)

        registry.forgetRelay(relay)

        assertNull(registry.currentSubId(relay, channel))
        assertTrue(registry.hasChanged(relay, channel, filtersA))
        assertEquals(channel, registry.resolveChannelId(relay, subId))
    }

    @Test
    fun `given state on two relays when forgetRelay is called for one then the other relay is unaffected`() {
        val registry = RelaySubscriptionRegistry()
        registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = false)
        registry.getOrCreateSubId(otherRelay, channel, rejectsSubIdReuse = false)

        registry.forgetRelay(relay)

        assertNotNull(registry.currentSubId(otherRelay, channel))
    }

    @Test
    fun `given forward map and fingerprint state across relays when resetAll is called then both are cleared everywhere but history survives`() {
        val registry = RelaySubscriptionRegistry()
        val subId = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = false)
        registry.getOrCreateSubId(otherRelay, channel, rejectsSubIdReuse = false)
        registry.recordSent(relay, channel, filtersA)

        registry.resetAll()

        assertNull(registry.currentSubId(relay, channel))
        assertNull(registry.currentSubId(otherRelay, channel))
        assertTrue(registry.hasChanged(relay, channel, filtersA))
        assertEquals(channel, registry.resolveChannelId(relay, subId))
    }

    @Test
    fun `given mapped channels when channelCount is called then it reflects live mappings and decreases after remove`() {
        val registry = RelaySubscriptionRegistry()
        registry.getOrCreateSubId(relay, "channel-a", rejectsSubIdReuse = false)
        registry.getOrCreateSubId(relay, "channel-b", rejectsSubIdReuse = false)

        assertEquals(2, registry.channelCount(relay))

        registry.remove(relay, "channel-a")

        assertEquals(1, registry.channelCount(relay))
    }

    @Test
    fun `given a relay with no mapped channels when channelCount is called then it returns zero`() {
        val registry = RelaySubscriptionRegistry()

        assertEquals(0, registry.channelCount(relay))
    }

    @Test
    fun `given channels mapped on multiple relays when subscriptionsForChannel is called then it returns every relay tracking that channel`() {
        val registry = RelaySubscriptionRegistry()
        val subIdA = registry.getOrCreateSubId(relay, channel, rejectsSubIdReuse = false)
        val subIdB = registry.getOrCreateSubId(otherRelay, channel, rejectsSubIdReuse = false)
        registry.getOrCreateSubId(relay, "other-channel", rejectsSubIdReuse = false)

        val result = registry.subscriptionsForChannel(channel)

        assertEquals(setOf(relay to subIdA, otherRelay to subIdB), result)
    }

    @Test
    fun `given no relay tracking a channel when subscriptionsForChannel is called then it returns an empty set`() {
        val registry = RelaySubscriptionRegistry()

        assertTrue(registry.subscriptionsForChannel(channel).isEmpty())
    }
}
