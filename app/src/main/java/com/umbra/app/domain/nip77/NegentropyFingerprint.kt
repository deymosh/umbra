package com.umbra.app.domain.nip77

import com.umbra.app.domain.util.hexToBytes
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Negentropy Protocol V1's fingerprint algorithm for a Range:
 * 1. Sum the element IDs (interpreted as 32-byte little-endian unsigned integers), mod 2^256.
 * 2. Concatenate with the element count, encoded as a varint.
 * 3. Hash with SHA-256.
 * 4. Take the first 16 bytes.
 * See https://github.com/hoytech/negentropy/blob/master/docs/negentropy-protocol-v1.md#fingerprint-algorithm
 */
internal object NegentropyFingerprint {
    private val MODULUS_2_256 = BigInteger.ONE.shiftLeft(256)

    fun compute(ids: List<String>): ByteArray {
        var sum = BigInteger.ZERO
        for (idHex in ids) {
            // The id's raw bytes are the same ones used everywhere else (Bound prefixes, IdList
            // wire encoding) — only *this* accumulator step interprets them as little-endian.
            val littleEndianMagnitude = idHex.hexToBytes().reversedArray()
            sum = sum.add(BigInteger(1, littleEndianMagnitude)).mod(MODULUS_2_256)
        }
        val sumBytes = sum.toFixedLittleEndianBytes(NEGENTROPY_ID_SIZE)
        val countVarint = NegentropyVarint.encode(ids.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256").digest(sumBytes + countVarint)
        return digest.copyOfRange(0, NEGENTROPY_FINGERPRINT_SIZE)
    }

    private fun BigInteger.toFixedLittleEndianBytes(size: Int): ByteArray {
        val bigEndian = this.toByteArray()
        val unsigned = if (bigEndian.size > 1 && bigEndian[0] == 0.toByte()) {
            bigEndian.copyOfRange(1, bigEndian.size)
        } else {
            bigEndian
        }
        val fixed = when {
            unsigned.size == size -> unsigned
            unsigned.size > size -> unsigned.copyOfRange(unsigned.size - size, unsigned.size)
            else -> ByteArray(size - unsigned.size) + unsigned
        }
        return fixed.reversedArray()
    }
}
