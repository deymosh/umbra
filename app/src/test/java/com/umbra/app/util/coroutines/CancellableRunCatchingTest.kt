package com.umbra.app.util.coroutines

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [runCatchingCancellable]'s one behavioral difference from the stdlib
 * `runCatching` it replaces: a thrown [CancellationException] must propagate instead of being
 * captured into the returned [Result], since it is a subtype of [Throwable]/[Exception] that the
 * stdlib version would otherwise silently swallow.
 */
class CancellableRunCatchingTest {

    @Test(expected = CancellationException::class)
    fun `given a block that throws CancellationException when run then it propagates instead of being captured`() {
        runCatchingCancellable<Unit> { throw CancellationException("cancelled") }
    }

    @Test
    fun `given a block that throws a plain exception when run then Result-failure is returned`() {
        val thrown = IllegalStateException("boom")

        val result = runCatchingCancellable<Unit> { throw thrown }

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
        assertEquals(thrown, result.exceptionOrNull())
    }

    @Test
    fun `given a block that completes normally when run then Result-success wraps its value`() {
        val result = runCatchingCancellable { "ok" }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
    }
}
