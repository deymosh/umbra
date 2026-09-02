package com.umbra.app.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileNip45CountAggregationTest {

    @Test
    fun `given empty relay counts when aggregating then returns zero`() {
        assertEquals(0, bestRemoteCount(emptyList()))
    }

    @Test
    fun `given multiple relay counts when aggregating then uses max relay value`() {
        val aggregated = bestRemoteCount(listOf(42L, 11L, 73L, 9L))

        assertEquals(73, aggregated)
    }

    @Test
    fun `given very large relay count when aggregating then clamps to int max`() {
        val aggregated = bestRemoteCount(listOf(Int.MAX_VALUE.toLong() + 100L))

        assertEquals(Int.MAX_VALUE, aggregated)
    }
}
