package com.umbra.app.domain.broadcast

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastModelsTest {

    private fun testEvent() = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1000,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = "hello",
        sig = "c".repeat(128)
    )

    @Test
    fun `given_allPending_when_created_then_statusIsInProgress`() {
        val broadcast = BroadcastEvent(
            id = "b1",
            event = testEvent(),
            targetRelays = listOf("wss://a.example.com", "wss://b.example.com"),
            startedAtMs = 0L
        )

        assertFalse(broadcast.isComplete)
        assertEquals(BroadcastStatus.IN_PROGRESS, broadcast.overallStatus)
        assertEquals(0, broadcast.successCount)
        assertEquals(0, broadcast.failureCount)
    }

    @Test
    fun `given_allSucceeded_when_complete_then_statusIsSuccess`() {
        val relays = listOf("wss://a.example.com", "wss://b.example.com")
        val broadcast = BroadcastEvent(
            id = "b1",
            event = testEvent(),
            targetRelays = relays,
            startedAtMs = 0L,
            results = relays.associateWith { RelayBroadcastResult(RelayBroadcastStatus.SUCCESS) }
        )

        assertTrue(broadcast.isComplete)
        assertEquals(BroadcastStatus.SUCCESS, broadcast.overallStatus)
        assertEquals(2, broadcast.successCount)
        assertEquals(0, broadcast.failureCount)
        assertEquals(1f, broadcast.progress)
    }

    @Test
    fun `given_mixedOutcome_when_complete_then_statusIsPartialAndFailedRelaysListed`() {
        val broadcast = BroadcastEvent(
            id = "b1",
            event = testEvent(),
            targetRelays = listOf("wss://ok.example.com", "wss://timeout.example.com", "wss://rejected.example.com"),
            startedAtMs = 0L,
            results = mapOf(
                "wss://ok.example.com" to RelayBroadcastResult(RelayBroadcastStatus.SUCCESS),
                "wss://timeout.example.com" to RelayBroadcastResult(RelayBroadcastStatus.TIMEOUT),
                "wss://rejected.example.com" to RelayBroadcastResult(RelayBroadcastStatus.FAILED, message = "blocked")
            )
        )

        assertTrue(broadcast.isComplete)
        assertEquals(BroadcastStatus.PARTIAL, broadcast.overallStatus)
        assertEquals(1, broadcast.successCount)
        assertEquals(2, broadcast.failureCount)
        assertEquals(setOf("wss://timeout.example.com", "wss://rejected.example.com"), broadcast.failedRelayUrls.toSet())
    }

    @Test
    fun `given_allFailed_when_complete_then_statusIsFailed`() {
        val relays = listOf("wss://a.example.com", "wss://b.example.com")
        val broadcast = BroadcastEvent(
            id = "b1",
            event = testEvent(),
            targetRelays = relays,
            startedAtMs = 0L,
            results = relays.associateWith { RelayBroadcastResult(RelayBroadcastStatus.FAILED) }
        )

        assertEquals(BroadcastStatus.FAILED, broadcast.overallStatus)
    }

    @Test
    fun `given_oneRelayStillRetrying_when_computed_then_isCompleteFalse`() {
        val broadcast = BroadcastEvent(
            id = "b1",
            event = testEvent(),
            targetRelays = listOf("wss://a.example.com", "wss://b.example.com"),
            startedAtMs = 0L,
            results = mapOf(
                "wss://a.example.com" to RelayBroadcastResult(RelayBroadcastStatus.SUCCESS),
                "wss://b.example.com" to RelayBroadcastResult(RelayBroadcastStatus.RETRYING, attempts = 2)
            )
        )

        assertFalse(broadcast.isComplete)
        assertEquals(BroadcastStatus.IN_PROGRESS, broadcast.overallStatus)
    }
}
