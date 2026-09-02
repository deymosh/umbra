package com.umbra.app.domain.validation

import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.crypto.normalizePubkey
import com.umbra.app.domain.nip01.NostrValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrValidationAndPubkeyUtilsTest {

    @Test
    fun `given_hexUppercaseOrInvalidValues_when_validating_then_normalizesOrRejectsNone`() {
        val upperHex = "A".repeat(64)
        val valid = NostrValidation.validate64HexOrNull(upperHex)
        val invalidChars = NostrValidation.validate64HexOrNull("g".repeat(64))
        val invalidLen = NostrValidation.validate64HexOrNull("a".repeat(63))

        assertEquals("a".repeat(64), valid)
        assertNull(invalidChars)
        assertNull(invalidLen)
    }

    @Test
    fun `given_hexMixedCaseOrInvalid_when_validating_then_filtersAndDeduplicates`() {
        val values = listOf("A".repeat(64), "a".repeat(64), "b".repeat(64), "invalid")

        val validated = NostrValidation.validate64HexSet(values)

        assertEquals(setOf("a".repeat(64), "b".repeat(64)), validated)
    }

    @Test
    fun `given_validOrInvalidHex_when_checking_then_returnsExpectedBoolean`() {
        assertTrue(NostrValidation.is64HexValid("f".repeat(64)))
        assertFalse(NostrValidation.is64HexValid("f".repeat(63)))
        assertFalse(NostrValidation.is64HexValid(null))
    }

    @Test
    fun `given_npubOrNormalizedKey_when_normalizing_then_decodesOrNormalizes`() {
        val hex = "1".repeat(64)
        val npub = Bech32Encoder.encodeNpub(hex)

        assertEquals(hex, normalizePubkey(npub))
        assertEquals("abc", normalizePubkey(" AbC "))
    }

    @Test
    fun `given_knownAndUnknownPrefixes_when_detecting_then_returnsEntityTypeOrNull`() {
        assertEquals("Profile", Bech32Encoder.detectEntityType("npub1qqqq"))
        assertEquals("NIP-19 nsec", Bech32Encoder.detectEntityType("nsec1qqqq"))
        assertEquals("Event", Bech32Encoder.detectEntityType("note1qqqq"))
        assertEquals("Addressable Event", Bech32Encoder.detectEntityType("naddr1qqqq"))
        assertEquals("Event with Relays", Bech32Encoder.detectEntityType("nevent1qqqq"))
        assertEquals("Profile with Relays", Bech32Encoder.detectEntityType("nprofile1qqqq"))
        assertNull(Bech32Encoder.detectEntityType("unknown1qqqq"))
    }
}

