package com.umbra.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class PrioritizeOutboxSweepOrderTest {

    @Test
    fun `given some authors recently active when prioritizing then they sort first`() {
        val remaining = linkedSetOf("a", "b", "c", "d")
        val recentlyActive = setOf("c")

        val result = prioritizeOutboxSweepOrder(remaining, recentlyActive)

        assertEquals(listOf("c", "a", "b", "d"), result)
    }

    @Test
    fun `given no recently active authors when prioritizing then falls back to original order`() {
        val remaining = linkedSetOf("a", "b", "c")

        val result = prioritizeOutboxSweepOrder(remaining, emptySet())

        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `given recently active authors are a superset of remaining when prioritizing then order is unchanged`() {
        val remaining = linkedSetOf("a", "b")
        val recentlyActive = setOf("a", "b", "z")

        val result = prioritizeOutboxSweepOrder(remaining, recentlyActive)

        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `given multiple recently active authors when prioritizing then all sort before inactive ones`() {
        val remaining = linkedSetOf("a", "b", "c", "d", "e")
        val recentlyActive = setOf("b", "d")

        val result = prioritizeOutboxSweepOrder(remaining, recentlyActive)

        assertEquals(listOf("b", "d", "a", "c", "e"), result)
    }
}
