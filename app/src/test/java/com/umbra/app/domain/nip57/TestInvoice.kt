package com.umbra.app.domain.nip57

/**
 * Test-only, checksum-correct BOLT11 fixture builder (same bech32 approach as Bolt11Test's
 * private encoder, lifted here so multiple test classes can share it without leaking it into
 * production code). Builds an invoice with an optional amount and an optional 'h'
 * (description-hash, type 23) tagged field, so parser behavior around that field is exercisable.
 */
object TestInvoice {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** Invoice encoding [msat] as u-btc units; caller must keep it a whole number of units. */
    fun invoiceForMsat(msat: Long): String {
        require(msat % 100_000L == 0L) { "fixture only supports whole u-btc units" }
        return encode(hrp = "lnbc${msat / 100_000L}u")
    }

    fun encode(hrp: String, descriptionHashHex: String? = null): String {
        val timestampWords = List(7) { 0 } // timestamp 0, MSB-first
        val hashFieldWords = descriptionHashHex?.let { hex ->
            val data = hex.chunked(2).map { it.toInt(16) }.map { it.toByte() }
            val dataWords = bytesToFiveBitWords(data.toByteArray())
            // The two-word length header counts 5-bit words, not bytes — same convention
            // Bolt11Test's own encoder follows for the 'd' field.
            listOf(23, (dataWords.size shr 5) and 0x1F, dataWords.size and 0x1F) + dataWords
        } ?: emptyList()
        val payload = timestampWords + hashFieldWords
        val checksumWords = checksum(hrp, payload)
        val dataPart = (payload + checksumWords).joinToString("") { CHARSET[it].toString() }
        return "$hrp" + "1" + dataPart
    }

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
}
