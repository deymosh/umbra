package com.umbra.app.domain.nip01

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaceableEventKeyTest {

    private fun event(
        id: String = "id",
        pubkey: String = "pubkey",
        kind: Int,
        createdAt: Long = 0L,
        tags: List<List<String>> = emptyList()
    ) = Event(id = id, pubkey = pubkey, createdAt = createdAt, kind = kind, tags = tags)

    @Test
    fun `given kind 0 when replaceableKey then keys by pubkey and kind`() {
        val key = event(pubkey = "abc", kind = Event.KIND_METADATA).replaceableKey()

        assertEquals(ReplaceableEventKey("abc", Event.KIND_METADATA), key)
    }

    @Test
    fun `given kind 3 when replaceableKey then keys by pubkey and kind`() {
        val key = event(pubkey = "abc", kind = Event.KIND_CONTACT_LIST).replaceableKey()

        assertEquals(ReplaceableEventKey("abc", Event.KIND_CONTACT_LIST), key)
    }

    @Test
    fun `given regular-replaceable range kind when replaceableKey then keys by pubkey and kind`() {
        val key = event(pubkey = "abc", kind = Event.KIND_RELAY_LIST_METADATA).replaceableKey()

        assertEquals(ReplaceableEventKey("abc", Event.KIND_RELAY_LIST_METADATA), key)
    }

    @Test
    fun `given parameterized-replaceable kind when replaceableKey then keys by pubkey kind and dTag`() {
        val key = event(
            pubkey = "abc",
            kind = Event.KIND_LONG_FORM,
            tags = listOf(listOf("d", "my-article"))
        ).replaceableKey()

        assertEquals(ReplaceableEventKey("abc", Event.KIND_LONG_FORM, "my-article"), key)
    }

    @Test
    fun `given parameterized-replaceable kind with different dTags when replaceableKey then keys differ`() {
        val first = event(pubkey = "abc", kind = Event.KIND_LONG_FORM, tags = listOf(listOf("d", "a"))).replaceableKey()
        val second = event(pubkey = "abc", kind = Event.KIND_LONG_FORM, tags = listOf(listOf("d", "b"))).replaceableKey()

        assertTrue(first != second)
    }

    @Test
    fun `given parameterized-replaceable kind with same dTag when replaceableKey then keys match`() {
        val first = event(pubkey = "abc", kind = Event.KIND_LONG_FORM, tags = listOf(listOf("d", "a"))).replaceableKey()
        val second = event(pubkey = "abc", kind = Event.KIND_LONG_FORM, tags = listOf(listOf("d", "a"))).replaceableKey()

        assertEquals(first, second)
    }

    @Test
    fun `given non-replaceable kind when replaceableKey then returns null`() {
        assertNull(event(kind = Event.KIND_TEXT_NOTE).replaceableKey())
        assertNull(event(kind = Event.KIND_REACTION).replaceableKey())
    }

    @Test
    fun `given higher createdAt when winsReplaceableRace then newer wins regardless of id`() {
        val older = event(id = "aaaa", kind = Event.KIND_METADATA, createdAt = 100L)
        val newer = event(id = "zzzz", kind = Event.KIND_METADATA, createdAt = 200L)

        assertTrue(newer.winsReplaceableRace(older))
        assertTrue(!older.winsReplaceableRace(newer))
    }

    @Test
    fun `given exact createdAt tie when winsReplaceableRace then lowest id wins`() {
        val lowerId = event(id = "aaaa", kind = Event.KIND_METADATA, createdAt = 100L)
        val higherId = event(id = "zzzz", kind = Event.KIND_METADATA, createdAt = 100L)

        assertTrue(lowerId.winsReplaceableRace(higherId))
        assertTrue(!higherId.winsReplaceableRace(lowerId))
    }
}
