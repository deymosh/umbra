package com.umbra.app.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventFrameScannerTest {

    private val id64 = "a".repeat(64)

    @Test
    fun `given a well-formed EVENT frame when scanning then extracts subId and eventId`() {
        val text = """["EVENT","sub1",{"id":"$id64","pubkey":"pk","content":"hello"}]"""

        val scanned = scanEventFrame(text)

        assertEquals("sub1", scanned?.subId)
        assertEquals(id64, scanned?.eventId)
    }

    @Test
    fun `given a non-EVENT frame when scanning then returns null`() {
        assertNull(scanEventFrame("""["EOSE","sub1"]"""))
    }

    @Test
    fun `given a whitespace variant of the EVENT prefix when scanning then bails to full parse`() {
        // A space after the comma is valid JSON but not the exact fast-path prefix — the safety
        // rule is to bail rather than guess.
        assertNull(scanEventFrame("""["EVENT", "sub1",{"id":"$id64"}]"""))
    }

    @Test
    fun `given an escaped character in the subscription id when scanning then bails to full parse`() {
        assertNull(scanEventFrame("""["EVENT","sub\"1",{"id":"$id64"}]"""))
    }

    @Test
    fun `given no id key when scanning then returns null`() {
        assertNull(scanEventFrame("""["EVENT","sub1",{"pubkey":"pk"}]"""))
    }

    @Test
    fun `given an id shorter than 64 hex chars when scanning then returns null`() {
        assertNull(scanEventFrame("""["EVENT","sub1",{"id":"abc123"}]"""))
    }

    @Test
    fun `given an id with non-hex characters when scanning then returns null`() {
        val badId = "g".repeat(64)
        assertNull(scanEventFrame("""["EVENT","sub1",{"id":"$badId"}]"""))
    }

    @Test
    fun `given an uppercase hex id when scanning then returns null`() {
        // Umbra always emits lowercase ids (see Event normalization); an uppercase id from a
        // non-conformant relay simply misses the fast path rather than being wrongly matched.
        val upperId = id64.uppercase()
        assertNull(scanEventFrame("""["EVENT","sub1",{"id":"$upperId"}]"""))
    }

    @Test
    fun `given the id key text embedded inside an escaped content string when scanning then still matches the real top-level id`() {
        // Valid JSON guarantees an embedded quote inside a string value is escaped as \" — so a
        // literal, unescaped "id":" substring can only be the real top-level key, even when the
        // content field (e.g. a kind-6 repost embedding a full event) contains what looks like
        // one in escaped form.
        val text = """["EVENT","sub1",{"id":"$id64","content":"{\"id\":\"deadbeef\"}"}]"""

        val scanned = scanEventFrame(text)

        assertEquals(id64, scanned?.eventId)
    }
}
