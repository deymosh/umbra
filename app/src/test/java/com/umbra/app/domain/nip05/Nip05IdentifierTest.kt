package com.umbra.app.domain.nip05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Nip05IdentifierTest {

    @Test
    fun `given valid nip05 when parsing then returns normalized identifier`() {
        val parsed = parseNip05Identifier(" Alice@Example.com ")

        requireNotNull(parsed)
        assertEquals("alice", parsed.name)
        assertEquals("example.com", parsed.domain)
        assertEquals("alice@example.com", parsed.normalized)
    }

    @Test
    fun `given blank name when parsing then normalizes to underscore`() {
        val parsed = parseNip05Identifier("@example.com")

        requireNotNull(parsed)
        assertEquals("_", parsed.name)
        assertEquals("example.com", parsed.domain)
    }

    @Test
    fun `given invalid format when parsing then returns null`() {
        assertNull(parseNip05Identifier("invalid"))
        assertNull(parseNip05Identifier("a@b@c"))
        assertNull(parseNip05Identifier("alice@"))
    }
}
