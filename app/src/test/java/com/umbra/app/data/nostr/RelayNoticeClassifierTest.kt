package com.umbra.app.data.nostr

import com.umbra.app.domain.relay.RelayIssueKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayNoticeClassifierTest {

    @Test
    fun `given too many concurrent subscriptions message when classifying then subscription limit`() {
        assertEquals(RelayIssueKind.SUBSCRIPTION_LIMIT, classifyRelayNotice("too many concurrent subscriptions"))
        assertEquals(RelayIssueKind.SUBSCRIPTION_LIMIT, classifyRelayNotice("subscription limit reached"))
        assertEquals(RelayIssueKind.SUBSCRIPTION_LIMIT, classifyRelayNotice("max subscriptions exceeded"))
        assertEquals(RelayIssueKind.SUBSCRIPTION_LIMIT, classifyRelayNotice("error: too many concurrent REQs"))
    }

    @Test
    fun `given request rate complaint when classifying then rate limit not subscription limit`() {
        assertEquals(RelayIssueKind.RATE_LIMIT, classifyRelayNotice("too many requests, please slow down"))
        assertEquals(RelayIssueKind.RATE_LIMIT, classifyRelayNotice("rate limited"))
        assertEquals(RelayIssueKind.RATE_LIMIT, classifyRelayNotice("429: throttled"))
    }

    @Test
    fun `given blocked or auth messages when classifying then correct kind`() {
        assertEquals(RelayIssueKind.BLOCKED, classifyRelayNotice("you are banned from this relay"))
        assertEquals(RelayIssueKind.AUTH, classifyRelayNotice("auth-required: please authenticate"))
    }

    @Test
    fun `given unrecognized message when classifying then generic notice`() {
        assertEquals(RelayIssueKind.NOTICE, classifyRelayNotice("hello from the relay operator"))
    }

    @Test
    fun `given negentropy disabled message when classifying then negentropy unsupported`() {
        // Exact real-world example this was reported against.
        assertEquals(RelayIssueKind.NEGENTROPY_UNSUPPORTED, classifyRelayNotice("ERROR: bad msg: negentropy disabled."))
        assertEquals(RelayIssueKind.NEGENTROPY_UNSUPPORTED, classifyRelayNotice("negentropy not supported"))
        assertEquals(RelayIssueKind.NEGENTROPY_UNSUPPORTED, classifyRelayNotice("NEG-OPEN: negentropy is unsupported here"))
    }

    @Test
    fun `given a message mentioning negentropy without a disabled clause when checking directly then false`() {
        org.junit.Assert.assertFalse(isNegentropyUnsupportedMessage("negentropy sync starting"))
    }

    @Test
    fun `given subscription limit phrasing when checking directly then true`() {
        assertTrueFor("too many concurrent reqs")
        assertTrueFor("subscription limit reached")
        assertTrueFor("max concurrent subscriptions")
    }

    @Test
    fun `given non subscription phrasing when checking directly then false`() {
        assertFalseFor("too many requests")
        assertFalseFor("rate limited")
        assertFalseFor("you are banned")
    }

    private fun assertTrueFor(text: String) = org.junit.Assert.assertTrue(isSubscriptionLimitMessage(text))
    private fun assertFalseFor(text: String) = org.junit.Assert.assertFalse(isSubscriptionLimitMessage(text))
}
