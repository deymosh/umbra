package com.umbra.app.domain.nip36

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentWarningTest {

    private fun event(tags: List<List<String>>): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = Event.KIND_TEXT_NOTE,
        tags = tags,
        content = "sensitive content",
        sig = "c".repeat(128)
    )

    @Test
    fun `given tag with reason when extracting then returns reason`() {
        val target = event(tags = listOf(listOf("content-warning", "graphic imagery")))

        assertEquals("graphic imagery", extractContentWarning(target)?.reason)
    }

    @Test
    fun `given tag without reason when extracting then reason is null but warning present`() {
        val target = event(tags = listOf(listOf("content-warning")))

        val warning = extractContentWarning(target)
        assertEquals(null, warning?.reason)
        assertEquals(true, warning != null)
    }

    @Test
    fun `given no tag when extracting then returns null`() {
        assertNull(extractContentWarning(event(tags = emptyList())))
    }

    @Test
    fun `given reason when building tag then includes it`() {
        assertEquals(listOf("content-warning", "spoilers"), contentWarningTag("spoilers"))
    }

    @Test
    fun `given no reason when building tag then omits second value`() {
        assertEquals(listOf("content-warning"), contentWarningTag())
    }

    @Test
    fun `given blank reason when building tag then omits second value`() {
        assertEquals(listOf("content-warning"), contentWarningTag("   "))
    }
}
