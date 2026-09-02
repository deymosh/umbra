package com.umbra.app.domain.nip44

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Nip44PayloadTest {

    @Test
    fun `given valid envelope when decoding then returns payload object`() {
        val decoded = Nip44PayloadEnvelope.decode("2:abc123")

        requireNotNull(decoded)
        assertEquals(2, decoded.version)
        assertEquals("abc123", decoded.payload)
    }

    @Test
    fun `given invalid envelope when decoding then returns null`() {
        assertNull(Nip44PayloadEnvelope.decode("no-colon"))
        assertNull(Nip44PayloadEnvelope.decode("0:abc"))
        assertNull(Nip44PayloadEnvelope.decode("2:   "))
    }

    @Test
    fun `given payload when encoding then renders version prefix format`() {
        val encoded = Nip44PayloadEnvelope.encode(Nip44Payload(version = 2, payload = "ciphertext"))

        assertEquals("2:ciphertext", encoded)
    }
}
