package com.umbra.app.domain.usecase

import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.testutil.fakes.FakeContactListRepository
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeMuteListRepository
import com.umbra.app.testutil.fakes.FakePinListRepository
import com.umbra.app.testutil.fakes.FakeUmbraLogger
import com.umbra.app.testutil.fakes.FakeUserRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wraps a real fake with a call counter (and optional throw) for a method the shared
 * `testutil/fakes` doubles don't otherwise track — the interfaces declare `trimMemory`/
 * `pruneStaleData` with a no-op default body, so no existing fake overrides or counts them.
 */
private class RecordingEventRepository(
    delegate: EventRepository,
    private val trimMemoryThrows: Throwable? = null
) : EventRepository by delegate {
    var trimMemoryCalls = 0
        private set

    override suspend fun trimMemory(aggressive: Boolean) {
        trimMemoryCalls++
        trimMemoryThrows?.let { throw it }
    }
}

private class RecordingUserRepository(
    delegate: UserRepository
) : UserRepository by delegate {
    var pruneStaleDataCalls = 0
        private set

    override suspend fun pruneStaleData() {
        pruneStaleDataCalls++
    }
}

private class RecordingContactListRepository(
    delegate: ContactListRepository
) : ContactListRepository by delegate {
    var trimMemoryCalls = 0
        private set

    override fun trimMemory() {
        trimMemoryCalls++
    }
}

private class RecordingMuteListRepository(
    delegate: MuteListRepository
) : MuteListRepository by delegate {
    var trimMemoryCalls = 0
        private set

    override fun trimMemory() {
        trimMemoryCalls++
    }
}

private class RecordingPinListRepository(
    delegate: PinListRepository
) : PinListRepository by delegate {
    var trimMemoryCalls = 0
        private set

    override fun trimMemory() {
        trimMemoryCalls++
    }
}

class TrimMemoryCachesUseCaseTest {

    @Test
    fun `given_eventRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("trim boom")
        val eventRepository = RecordingEventRepository(FakeEventRepository(), trimMemoryThrows = thrown)
        val logger = FakeUmbraLogger()
        val useCase = TrimMemoryCachesUseCase(
            eventRepository,
            FakeUserRepository(),
            FakeContactListRepository(),
            FakeMuteListRepository(),
            FakePinListRepository(),
            logger
        )

        useCase(aggressive = false)

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_firstStepThrows_when_invoked_then_laterStepsStillRun`() = runBlocking {
        val eventRepository = RecordingEventRepository(
            FakeEventRepository(),
            trimMemoryThrows = IllegalStateException("boom")
        )
        val userRepository = RecordingUserRepository(FakeUserRepository())
        val contactListRepository = RecordingContactListRepository(FakeContactListRepository())
        val muteListRepository = RecordingMuteListRepository(FakeMuteListRepository())
        val pinListRepository = RecordingPinListRepository(FakePinListRepository())
        val useCase = TrimMemoryCachesUseCase(
            eventRepository,
            userRepository,
            contactListRepository,
            muteListRepository,
            pinListRepository,
            FakeUmbraLogger()
        )

        useCase(aggressive = true)

        assertEquals(1, eventRepository.trimMemoryCalls)
        assertEquals(1, userRepository.pruneStaleDataCalls)
        assertEquals(1, contactListRepository.trimMemoryCalls)
        assertEquals(1, muteListRepository.trimMemoryCalls)
        assertEquals(1, pinListRepository.trimMemoryCalls)
    }

    @Test
    fun `given_noStepThrows_when_invoked_then_noErrorCallsRecorded`() = runBlocking {
        val logger = FakeUmbraLogger()
        val useCase = TrimMemoryCachesUseCase(
            FakeEventRepository(),
            FakeUserRepository(),
            FakeContactListRepository(),
            FakeMuteListRepository(),
            FakePinListRepository(),
            logger
        )

        useCase(aggressive = false)

        assertTrue(logger.errorCalls.isEmpty())
    }
}
