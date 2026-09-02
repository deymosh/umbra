package com.umbra.app.domain.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionIdTest {

    @Test
    fun `given the default length when generating an id then it is 6 lowercase alphanumeric chars`() {
        val id = randomSubscriptionId()

        assertEquals(6, id.length)
        assertTrue(id.all { it in "abcdefghijklmnopqrstuvwxyz0123456789" })
    }

    @Test
    fun `given no purpose info when generating an id then it never embeds a descriptive prefix`() {
        val id = randomSubscriptionId()

        assertFalse(id.contains("-"))
    }

    @Test
    fun `given many calls when generating ids then they are not all identical`() {
        val ids = (1..20).map { randomSubscriptionId() }.toSet()

        assertTrue(ids.size > 1)
    }
}
