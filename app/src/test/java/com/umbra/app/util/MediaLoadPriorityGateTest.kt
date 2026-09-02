package com.umbra.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLoadPriorityGateTest {

    @Test
    fun `given first interactive load when begun then listener is notified once`() {
        val gate = MediaLoadPriorityGate()
        var notifications = 0
        gate.addInteractiveLoadStartedListener { notifications += 1 }

        val lease = gate.beginInteractiveLoad()

        assertTrue(gate.isInteractiveLoadActive)
        assertEquals(1, notifications)
        lease.close()
    }

    @Test
    fun `given overlapping interactive loads when one closes then priority remains active`() {
        val gate = MediaLoadPriorityGate()
        val first = gate.beginInteractiveLoad()
        val second = gate.beginInteractiveLoad()

        first.close()

        assertTrue(gate.isInteractiveLoadActive)
        second.close()
        assertFalse(gate.isInteractiveLoadActive)
    }

    @Test
    fun `given active load when listener registers then it is notified immediately`() {
        val gate = MediaLoadPriorityGate()
        val lease = gate.beginInteractiveLoad()
        var notifications = 0

        val registration = gate.addInteractiveLoadStartedListener { notifications += 1 }

        assertEquals(1, notifications)
        registration.close()
        lease.close()
    }

    @Test
    fun `given lease closes twice when another load remains then priority stays active`() {
        val gate = MediaLoadPriorityGate()
        val first = gate.beginInteractiveLoad()
        val second = gate.beginInteractiveLoad()

        first.close()
        first.close()

        assertTrue(gate.isInteractiveLoadActive)
        second.close()
        assertFalse(gate.isInteractiveLoadActive)
    }
}