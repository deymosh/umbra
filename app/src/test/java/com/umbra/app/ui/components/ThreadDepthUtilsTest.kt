package com.umbra.app.ui.components

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadDepthUtilsTest {

    @Test
    fun `given linear chain when building depth map then caps at max depth`() {
        val e1 = event(id = "1".repeat(64), tags = emptyList())
        val e2 = event(id = "2".repeat(64), tags = listOf(listOf("e", e1.id)))
        val e3 = event(id = "3".repeat(64), tags = listOf(listOf("e", e2.id)))
        val e4 = event(id = "4".repeat(64), tags = listOf(listOf("e", e3.id)))
        val e5 = event(id = "5".repeat(64), tags = listOf(listOf("e", e4.id)))
        val e6 = event(id = "6".repeat(64), tags = listOf(listOf("e", e5.id)))

        val events = listOf(e1, e2, e3, e4, e5, e6)
        val byId = events.associateBy { it.id }

        val result = buildThreadDepthByEventId(events, byId, maxDepth = 4)

        assertEquals(0, result[e1.id])
        assertEquals(1, result[e2.id])
        assertEquals(2, result[e3.id])
        assertEquals(3, result[e4.id])
        assertEquals(4, result[e5.id])
        assertEquals(4, result[e6.id])
    }

    @Test
    fun `given cyclic parents when calculating depth then stops without infinite loop`() {
        val a = event(id = "a".repeat(64), tags = listOf(listOf("e", "b".repeat(64))))
        val b = event(id = "b".repeat(64), tags = listOf(listOf("e", a.id)))
        val byId = listOf(a, b).associateBy { it.id }

        val depthA = calculateThreadDepth(a, byId, maxDepth = 8)
        val depthB = calculateThreadDepth(b, byId, maxDepth = 8)

        assertEquals(2, depthA)
        assertEquals(2, depthB)
    }

    private fun event(id: String, tags: List<List<String>>): Event {
        return Event(
            id = id,
            pubkey = "f".repeat(64),
            createdAt = 1_700_000_000L,
            kind = Event.KIND_TEXT_NOTE,
            tags = tags,
            content = "x",
            sig = "e".repeat(128)
        )
    }
}

