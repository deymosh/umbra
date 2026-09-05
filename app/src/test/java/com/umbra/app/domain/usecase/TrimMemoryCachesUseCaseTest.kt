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
    delegate: UserRepository,
    private val pruneStaleDataThrows: Throwable? = null
) : UserRepository by delegate {
    var pruneStaleDataCalls = 0
        private set

    override suspend fun pruneStaleData() {
        pruneStaleDataCalls++
        pruneStaleDataThrows?.let { throw it }
    }
}

private class RecordingContactListRepository(
    delegate: ContactListRepository,
    private val trimMemoryThrows: Throwable? = null
) : ContactListRepository by delegate {
    var trimMemoryCalls = 0
        private set

    override fun trimMemory() {
        trimMemoryCalls++
        trimMemoryThrows?.let { throw it }
    }
}

private class RecordingMuteListRepository(
    delegate: MuteListRepository,
    private val trimMemoryThrows: Throwable? = null
) : MuteListRepository by delegate {
    var trimMemoryCalls = 0
        private set

    override fun trimMemory() {
        trimMemoryCalls++
        trimMemoryThrows?.let { throw it }
    }
}

private class RecordingPinListRepository(
    delegate: PinListRepository,
    private val trimMemoryThrows: Throwable? = null
) : PinListRepository by delegate {
    var trimMemoryCalls = 0
        private set

    override fun trimMemory() {
        trimMemoryCalls++
        trimMemoryThrows?.let { throw it }
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

    @Test
    fun `given_userRepositoryPruneStaleDataThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("prune stale data boom")
        val userRepository = RecordingUserRepository(FakeUserRepository(), pruneStaleDataThrows = thrown)
        val logger = FakeUmbraLogger()
        val useCase = TrimMemoryCachesUseCase(
            FakeEventRepository(),
            userRepository,
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
    fun `given_contactListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("contact list trim memory boom")
        val contactListRepository = RecordingContactListRepository(FakeContactListRepository(), trimMemoryThrows = thrown)
        val logger = FakeUmbraLogger()
        val useCase = TrimMemoryCachesUseCase(
            FakeEventRepository(),
            FakeUserRepository(),
            contactListRepository,
            FakeMuteListRepository(),
            FakePinListRepository(),
            logger
        )

        useCase(aggressive = false)

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_muteListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("mute list trim memory boom")
        val muteListRepository = RecordingMuteListRepository(FakeMuteListRepository(), trimMemoryThrows = thrown)
        val logger = FakeUmbraLogger()
        val useCase = TrimMemoryCachesUseCase(
            FakeEventRepository(),
            FakeUserRepository(),
            FakeContactListRepository(),
            muteListRepository,
            FakePinListRepository(),
            logger
        )

        useCase(aggressive = false)

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_pinListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("pin list trim memory boom")
        val pinListRepository = RecordingPinListRepository(FakePinListRepository(), trimMemoryThrows = thrown)
        val logger = FakeUmbraLogger()
        val useCase = TrimMemoryCachesUseCase(
            FakeEventRepository(),
            FakeUserRepository(),
            FakeContactListRepository(),
            FakeMuteListRepository(),
            pinListRepository,
            logger
        )

        useCase(aggressive = false)

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }
}
