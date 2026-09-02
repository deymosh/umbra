package com.umbra.app.domain.nip77

/**
 * Negentropy Protocol V1's varint: base-128 digits, most significant digit first, as few digits
 * as possible. Bit eight (the high bit) is set on every byte except the last.
 * See https://github.com/hoytech/negentropy/blob/master/docs/negentropy-protocol-v1.md#varint
 */
internal object NegentropyVarint {
    fun encode(valueIn: Long): ByteArray {
        require(valueIn >= 0) { "Negentropy varints are unsigned, got $valueIn" }
        if (valueIn == 0L) return byteArrayOf(0)

        var value = valueIn
        val digits = ArrayList<Int>()
        while (value != 0L) {
            digits.add((value and 0x7F).toInt())
            value = value ushr 7
        }
        digits.reverse()
        for (i in 0 until digits.size - 1) digits[i] = digits[i] or 0x80
        return ByteArray(digits.size) { digits[it].toByte() }
    }

    /** Decodes a varint starting at [offset]. Returns the value and the number of bytes consumed. */
    fun decode(source: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var pos = offset
        while (true) {
            require(pos < source.size) { "Negentropy message ends prematurely while parsing varint" }
            val byte = source[pos].toInt() and 0xFF
            result = (result shl 7) or (byte and 0x7F).toLong()
            pos++
            if (byte and 0x80 == 0) break
        }
        return result to (pos - offset)
    }
}
