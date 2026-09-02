package com.umbra.app.domain.nip51

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTagListParsingTest {

    @Test
    fun `given relay urls when encoding then produces relay tag array json`() {
        val json = encodeRelayTagUrls(setOf("wss://one.relay"))

        assertEquals("""[["relay","wss://one.relay"]]""", json)
    }

    @Test
    fun `given empty set when encoding then produces empty array`() {
        assertEquals("[]", encodeRelayTagUrls(emptySet()))
    }

    @Test
    fun `given encoded urls when decoding then round trips back to the same set`() {
        val original = setOf("wss://one.relay", "wss://two.relay", "ws://onionaddress1234567890abcdefghijklmnopqrstuvwxyz.onion")

        val decoded = decodeRelayTagUrls(encodeRelayTagUrls(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `given blank json when decoding then returns empty set`() {
        assertTrue(decodeRelayTagUrls("").isEmpty())
        assertTrue(decodeRelayTagUrls("   ").isEmpty())
    }

    @Test
    fun `given malformed json when decoding then returns empty set instead of throwing`() {
        assertTrue(decodeRelayTagUrls("not json at all").isEmpty())
        assertTrue(decodeRelayTagUrls("{\"not\":\"an array\"}").isEmpty())
    }

    @Test
    fun `given non relay tags when decoding then only relay-named tags are kept`() {
        val json = """[["relay","wss://kept.relay"],["p","somepubkey"],["relay",""]]"""

        assertEquals(setOf("wss://kept.relay"), decodeRelayTagUrls(json))
    }
}
