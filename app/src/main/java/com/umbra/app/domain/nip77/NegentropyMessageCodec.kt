package com.umbra.app.domain.nip77

import com.umbra.app.domain.util.hexToBytes
import com.umbra.app.domain.util.toHex
import java.io.ByteArrayOutputStream

/** Range payload types — see Negentropy Protocol V1's `Range`/`Mode`. */
internal object NegentropyMode {
    const val SKIP = 0L
    const val FINGERPRINT = 1L
    const val ID_LIST = 2L
}

internal const val NEGENTROPY_PROTOCOL_VERSION: Int = 0x61 // Version 1
internal const val NEGENTROPY_FINGERPRINT_SIZE: Int = 16
internal const val NEGENTROPY_ID_SIZE: Int = 32

internal class NegentropyProtocolException(message: String) : Exception(message)

/**
 * Builds one outgoing Negentropy message. [lastTimestampOut] tracks the running delta-encoding
 * state and — like the reference implementation — is scoped to a single message: a fresh writer
 * is used per message, never reused across rounds.
 */
internal class NegentropyMessageWriter {
    private val buffer = ByteArrayOutputStream()
    private var lastTimestampOut: Long = 0L

    init {
        buffer.write(NEGENTROPY_PROTOCOL_VERSION)
    }

    fun writeVarint(value: Long) {
        buffer.write(NegentropyVarint.encode(value))
    }

    fun writeBytes(bytes: ByteArray) {
        buffer.write(bytes)
    }

    fun writeId(idHex: String) {
        writeBytes(idHex.hexToBytes())
    }

    fun writeBound(bound: NegentropyBound) {
        writeVarint(encodeTimestamp(bound.timestamp))
        val idBytes = bound.idPrefixHex.hexToBytes()
        writeVarint(idBytes.size.toLong())
        writeBytes(idBytes)
    }

    fun render(): ByteArray = buffer.toByteArray()

    private fun encodeTimestamp(timestamp: Long): Long {
        if (timestamp == NEGENTROPY_INFINITY_TIMESTAMP) {
            lastTimestampOut = NEGENTROPY_INFINITY_TIMESTAMP
            return 0L
        }
        val delta = timestamp - lastTimestampOut
        lastTimestampOut = timestamp
        return delta + 1
    }
}

/** Parses one incoming Negentropy message. Scoped to a single message, same as [NegentropyMessageWriter]. */
internal class NegentropyMessageReader(private val source: ByteArray) {
    private var pos: Int = 0
    private var lastTimestampIn: Long = 0L

    val isAtEnd: Boolean get() = pos >= source.size

    fun readByte(): Int {
        require(pos < source.size) { "Negentropy message ends prematurely" }
        val value = source[pos].toInt() and 0xFF
        pos++
        return value
    }

    fun readVarint(): Long {
        val (value, consumed) = NegentropyVarint.decode(source, pos)
        pos += consumed
        return value
    }

    fun readBytes(n: Int): ByteArray {
        require(pos + n <= source.size) { "Negentropy message ends prematurely" }
        val result = source.copyOfRange(pos, pos + n)
        pos += n
        return result
    }

    fun readId(): String = readBytes(NEGENTROPY_ID_SIZE).toHex()

    fun readBound(): NegentropyBound {
        val timestamp = decodeTimestamp(readVarint())
        val len = readVarint()
        if (len > NEGENTROPY_ID_SIZE) throw NegentropyProtocolException("bound id prefix too long: $len")
        val idPrefixHex = readBytes(len.toInt()).toHex()
        return NegentropyBound(timestamp, idPrefixHex)
    }

    private fun decodeTimestamp(raw: Long): Long {
        val timestamp = if (raw == 0L) NEGENTROPY_INFINITY_TIMESTAMP else raw - 1
        if (lastTimestampIn == NEGENTROPY_INFINITY_TIMESTAMP || timestamp == NEGENTROPY_INFINITY_TIMESTAMP) {
            lastTimestampIn = NEGENTROPY_INFINITY_TIMESTAMP
            return NEGENTROPY_INFINITY_TIMESTAMP
        }
        val result = timestamp + lastTimestampIn
        lastTimestampIn = result
        return result
    }
}
