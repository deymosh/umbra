package com.umbra.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserStoragePolicyTest {

    @Test
    fun `given_whitespaceAndUppercasePubkey_when_normalizing_then_returnsLowercaseHex64`() {
        val raw = "  ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789  "

        val normalized = normalizeCurrentUserPubkey(raw)

        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", normalized)
    }

    @Test
    fun `given_anonymousZeroPubkey_when_normalizing_then_returnsNull`() {
        val normalized = normalizeCurrentUserPubkey("0".repeat(64))

        assertNull(normalized)
    }

    @Test
    fun `given_invalidLengthPubkey_when_normalizing_then_returnsNull`() {
        val normalized = normalizeCurrentUserPubkey("abc123")

        assertNull(normalized)
    }
}
