package com.umbra.app.domain.usecase

import com.umbra.app.domain.broadcast.BroadcastEvent
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.repository.BroadcastRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeUmbraLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBroadcastRepository : BroadcastRepository {
    override val activeBroadcasts: StateFlow<List<BroadcastEvent>> = MutableStateFlow(emptyList())
    override fun trackPublish(event: Event, targetRelays: Set<String>) = Unit
    override fun retryFailedRelays(broadcastId: String) = Unit
    override fun dismiss(broadcastId: String) = Unit
}

/** Wraps a real fake so `publishEvent()` returns a caller-controlled failed [Result]. */
private class ThrowingPublishEventRepository(
    delegate: EventRepository,
    private val publishResult: Result<Set<String>>
) : EventRepository by delegate {
    override suspend fun publishEvent(event: Event): Result<Set<String>> = publishResult
}

private val VALID_SIGNED_EVENT_JSON =
    """{"id":"${"a".repeat(64)}","pubkey":"${"b".repeat(64)}",""" +
        """"created_at":1700000000,"kind":1,"tags":[],"content":"hi",""" +
        """"sig":"${"c".repeat(128)}"}"""

private val BLANK_ID_SIGNED_EVENT_JSON =
    """{"id":"","pubkey":"${"b".repeat(64)}",""" +
        """"created_at":1700000000,"kind":1,"tags":[],"content":"hi",""" +
        """"sig":"${"c".repeat(128)}"}"""

class PublishEventUseCasesTest {

    @Test
    fun `given_publishEventFails_when_invoked_then_loggerRecordsErrorWithSameThrowableAndResultFails`() = runBlocking {
        val thrown = IllegalStateException("publish boom")
        val eventRepository = ThrowingPublishEventRepository(
            FakeEventRepository(),
            Result.failure(thrown)
        )
        val logger = FakeUmbraLogger()
        val useCase = PublishSignedEventUseCase(eventRepository, FakeBroadcastRepository(), logger)

        val result = useCase(VALID_SIGNED_EVENT_JSON)

        assertTrue(result.isFailure)
        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_malformedSignedEventJson_when_invoked_then_loggerRecordsErrorAndResultFails`() = runBlocking {
        val logger = FakeUmbraLogger()
        val useCase = PublishSignedEventUseCase(FakeEventRepository(), FakeBroadcastRepository(), logger)

        val result = useCase(BLANK_ID_SIGNED_EVENT_JSON)

        assertTrue(result.isFailure)
        assertEquals(1, logger.errorCalls.size)
    }

    @Test
    fun `given_successfulPublish_when_invoked_then_noErrorCallsRecorded`() = runBlocking {
        val logger = FakeUmbraLogger()
        val useCase = PublishSignedEventUseCase(FakeEventRepository(), FakeBroadcastRepository(), logger)

        val result = useCase(VALID_SIGNED_EVENT_JSON)

        assertTrue(result.isSuccess)
        assertTrue(logger.errorCalls.isEmpty())
    }
}
