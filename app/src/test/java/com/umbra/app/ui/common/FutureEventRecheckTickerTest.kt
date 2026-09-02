package com.umbra.app.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FutureEventRecheckTickerTest {

    @Test
    fun `given ticker when collecting first tick then it emits immediately with no delay`() = runTest {
        val ticks = futureEventRecheckTicker(30_000L).take(1).toList()

        assertEquals(1, ticks.size)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `given interval when collecting N ticks then virtual time advances by N minus one times interval`() = runTest {
        val intervalMs = 30_000L
        val tickCount = 3

        val ticks = futureEventRecheckTicker(intervalMs).take(tickCount).toList()

        assertEquals(tickCount, ticks.size)
        assertEquals(intervalMs * (tickCount - 1), testScheduler.currentTime)
    }
}
