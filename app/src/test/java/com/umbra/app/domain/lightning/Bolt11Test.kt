package com.umbra.app.domain.lightning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parseBolt11 is exercised via self-encoded invoices (built with [encodeTestInvoice] below,
 * mirroring Bech32Encoder's own bech32 checksum algorithm) rather than a hand-copied real-world
 * invoice string — a single mistyped bech32 character in a literal test constant would silently
 * produce a checksum-invalid string and fail for the wrong reason. Round-tripping through our own
 * encoder instead directly verifies parseBolt11's bit-unpacking against known-correct input.
 */
class Bolt11Test {

    @Test
    fun `given micro-btc amount when parsing then converts to correct msat`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u")
        val decoded = parseBolt11(invoice)
        assertEquals(250_000_000L, decoded?.amountMsat)
    }

    @Test
    fun `given milli-btc amount when parsing then converts to correct msat`() {
        val invoice = encodeTestInvoice(hrp = "lnbc1m")
        val decoded = parseBolt11(invoice)
        assertEquals(100_000_000L, decoded?.amountMsat)
    }

    @Test
    fun `given pico-btc amount when parsing then converts to correct msat`() {
        val invoice = encodeTestInvoice(hrp = "lnbc10p")
        val decoded = parseBolt11(invoice)
        assertEquals(1L, decoded?.amountMsat)
    }

    @Test
    fun `given no amount segment when parsing then amount is null`() {
        val invoice = encodeTestInvoice(hrp = "lnbc")
        val decoded = parseBolt11(invoice)
        assertNull(decoded?.amountMsat)
    }

    @Test
    fun `given description tagged field when parsing then extracts it`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u", description = "coffee beans")
        val decoded = parseBolt11(invoice)
        assertEquals("coffee beans", decoded?.description)
    }

    @Test
    fun `given no description field when parsing then description is null`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u", description = null)
        val decoded = parseBolt11(invoice)
        assertEquals(null, decoded?.description)
    }

    @Test
    fun `given lightning-prefixed invoice when parsing then prefix is stripped`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u")
        val decoded = parseBolt11("lightning:$invoice")
        assertEquals(invoice, decoded?.raw)
    }

    @Test
    fun `given mixed-case lightning-prefixed invoice when parsing then prefix is still stripped`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u")
        val decoded = parseBolt11("Lightning:$invoice")
        assertEquals(invoice, decoded?.raw)
    }

    @Test
    fun `given nostr-prefixed invoice when parsing then prefix is stripped`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u")
        val decoded = parseBolt11("nostr:$invoice")
        assertEquals(invoice, decoded?.raw)
    }

    @Test
    fun `given nostr-scheme-prefixed invoice when parsing then prefix is stripped`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u")
        val decoded = parseBolt11("nostr://$invoice")
        assertEquals(invoice, decoded?.raw)
    }

    @Test
    fun `given non-invoice text when parsing then returns null`() {
        assertNull(parseBolt11("not an invoice"))
        assertNull(parseBolt11("npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"))
    }

    @Test
    fun `given a corrupted checksum when parsing then returns null`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u")
        val corrupted = invoice.dropLast(1) + (if (invoice.last() == 'q') 'p' else 'q')
        assertTrue(parseBolt11(corrupted) == null)
    }

    @Test
    fun `given no expiry field when parsing then defaults to 3600s`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u", timestampSeconds = 1_000L)
        val decoded = parseBolt11(invoice)
        assertEquals(3600L, decoded?.expirySeconds)
        assertEquals(4_600L, decoded?.expiresAtEpochSeconds())
    }

    @Test
    fun `given explicit expiry field when parsing then decodes correctly`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u", timestampSeconds = 1_000L, expirySeconds = 7_200L)
        val decoded = parseBolt11(invoice)
        assertEquals(7_200L, decoded?.expirySeconds)
        assertEquals(8_200L, decoded?.expiresAtEpochSeconds())
    }

    @Test
    fun `given expired invoice when checking isExpired then returns true`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u", timestampSeconds = 1_000L, expirySeconds = 3_600L)
        val decoded = parseBolt11(invoice)
        assertTrue(decoded!!.isExpired(nowEpochSeconds = 5_000L))
    }

    @Test
    fun `given non-expired invoice when checking isExpired then returns false`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u", timestampSeconds = 1_000L, expirySeconds = 3_600L)
        val decoded = parseBolt11(invoice)
        assertFalse(decoded!!.isExpired(nowEpochSeconds = 2_000L))
    }

    @Test
    fun `given invoice expiring exactly now when checking isExpired then returns true`() {
        val invoice = encodeTestInvoice(hrp = "lnbc2500u", timestampSeconds = 1_000L, expirySeconds = 3_600L)
        val decoded = parseBolt11(invoice)
        assertTrue(decoded!!.isExpired(nowEpochSeconds = 4_600L))
    }
}

private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

private fun hrpExpand(hrp: String): List<Int> {
    val expanded = mutableListOf<Int>()
    hrp.forEach { expanded += (it.code shr 5) }
    expanded += 0
    hrp.forEach { expanded += (it.code and 31) }
    return expanded
}

private fun polymod(values: List<Int>): Int {
    var checksum = 1
    values.forEach { value ->
        val top = checksum ushr 25
        checksum = (checksum and 0x1ffffff) shl 5 xor value
        for (i in GENERATOR.indices) {
            if (((top ushr i) and 1) != 0) checksum = checksum xor GENERATOR[i]
        }
    }
    return checksum
}

private fun checksum(hrp: String, data: List<Int>): List<Int> {
    val values = hrpExpand(hrp) + data + listOf(0, 0, 0, 0, 0, 0)
    val poly = polymod(values) xor 1
    return List(6) { index -> (poly ushr (5 * (5 - index))) and 31 }
}

/** Inverse of production's fiveBitWordsToBytes: packs bytes into 5-bit words, zero-padded. */
private fun bytesToFiveBitWords(bytes: ByteArray): List<Int> {
    val words = mutableListOf<Int>()
    var accumulator = 0
    var bits = 0
    for (byte in bytes) {
        accumulator = (accumulator shl 8) or (byte.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            words += (accumulator ushr bits) and 0x1F
        }
    }
    if (bits > 0) {
        words += (accumulator shl (5 - bits)) and 0x1F
    }
    return words
}

/**
 * Builds a syntactically valid, checksum-correct BOLT11-shaped invoice for a given [hrp] with an
 * optional description ('d', type 13) and/or expiry ('x', type 6) tagged field — enough structure
 * for [parseBolt11] to exercise its amount/description/timestamp/expiry decoding, without needing
 * a full realistic invoice (payment hash, signature, etc.), since parseBolt11's tagged-field walk
 * simply stops once it can no longer form a complete field header.
 */
private fun encodeTestInvoice(
    hrp: String,
    description: String? = null,
    timestampSeconds: Long = 0L,
    expirySeconds: Long? = null
): String {
    // 35-bit timestamp, MSB-first — mirrors production's `words.take(7).fold { (acc shl 5) or w }`.
    val timestampWords = List(7) { i -> ((timestampSeconds ushr (5 * (6 - i))) and 0x1F).toInt() }
    val descriptionFieldWords = if (description != null) {
        val dataWords = bytesToFiveBitWords(description.toByteArray(Charsets.UTF_8))
        listOf(13, (dataWords.size shr 5) and 0x1F, dataWords.size and 0x1F) + dataWords
    } else {
        emptyList()
    }
    val expiryFieldWords = if (expirySeconds != null) {
        val dataWords = longToFiveBitWords(expirySeconds)
        listOf(6, (dataWords.size shr 5) and 0x1F, dataWords.size and 0x1F) + dataWords
    } else {
        emptyList()
    }
    val payload = timestampWords + descriptionFieldWords + expiryFieldWords
    val checksumWords = checksum(hrp, payload)
    val dataPart = (payload + checksumWords).joinToString("") { CHARSET[it].toString() }
    return "$hrp" + "1" + dataPart
}

/** Minimal-length big-endian 5-bit-word encoding of a non-negative integer (at least one word). */
private fun longToFiveBitWords(value: Long): List<Int> {
    if (value == 0L) return listOf(0)
    val words = mutableListOf<Int>()
    var remaining = value
    while (remaining > 0) {
        words.add(0, (remaining and 0x1F).toInt())
        remaining = remaining ushr 5
    }
    return words
}
