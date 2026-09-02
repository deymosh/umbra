package com.umbra.app.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewportPrefetchFlowPolicyTest {

    @Test
    fun `given no visible items when awaiting quiet window then returns false without delay`() = runTest {
        val initialTime = testScheduler.currentTime

        val result = awaitViewportPrefetchQuietWindow(
            visibleCount = 0,
            quietWindowMs = 500L
        )

        assertFalse(result)
        assertEquals(initialTime, testScheduler.currentTime)
    }

    @Test
    fun `given visible items when awaiting quiet window then delays and returns true`() = runTest {
        val quietWindow = 500L
        val initialTime = testScheduler.currentTime

        val result = awaitViewportPrefetchQuietWindow(
            visibleCount = 3,
            quietWindowMs = quietWindow
        )

        assertTrue(result)
        assertEquals(initialTime + quietWindow, testScheduler.currentTime)
    }
}
