package com.umbra.app.domain.nip77

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NegentropyVarintTest {

    @Test
    fun `given zero when encoding then single zero byte`() {
        assertArrayEquals(byteArrayOf(0x00), NegentropyVarint.encode(0L))
    }

    @Test
    fun `given the largest one-byte value when encoding then no continuation bit`() {
        assertArrayEquals(byteArrayOf(0x7F), NegentropyVarint.encode(127L))
    }

    @Test
    fun `given the smallest two-byte value when encoding then high byte carries continuation bit`() {
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x00), NegentropyVarint.encode(128L))
    }

    @Test
    fun `given a three-byte-boundary value when encoding then three bytes with continuation bits set`() {
        // 16384 = 128^2 -> [0x81, 0x80, 0x00] per base-128, MSB first
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x00), NegentropyVarint.encode(16384L))
    }

    @Test
    fun `given various values when round-tripping through encode then decode then original value is recovered`() {
        val values = listOf(0L, 1L, 42L, 127L, 128L, 300L, 16383L, 16384L, 1_000_000L, Int.MAX_VALUE.toLong())
        for (value in values) {
            val encoded = NegentropyVarint.encode(value)
            val (decoded, consumed) = NegentropyVarint.decode(encoded, 0)
            assertEquals("round-trip failed for $value", value, decoded)
            assertEquals("consumed byte count should match encoded length for $value", encoded.size, consumed)
        }
    }

    @Test
    fun `given a varint followed by trailing bytes when decoding then only the varint's own bytes are consumed`() {
        val encoded = NegentropyVarint.encode(300L) + byteArrayOf(0x11, 0x22)
        val (decoded, consumed) = NegentropyVarint.decode(encoded, 0)
        assertEquals(300L, decoded)
        assertEquals(2, consumed)
    }

    @Test
    fun `given a negative value when encoding then throws`() {
        try {
            NegentropyVarint.encode(-1L)
            throw AssertionError("expected an exception for a negative varint value")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
