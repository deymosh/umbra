package com.umbra.app.domain.nip19

import com.umbra.app.domain.logging.NoOpUmbraLogger
import com.umbra.app.domain.logging.UmbraLogger
import com.umbra.app.domain.util.hexToBytes
import com.umbra.app.domain.util.toHex
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs

/**
 * NIP-19: Bech32 Encoding for Nostr Entities
 *
 * Provides standardized encoding/decoding for:
 * - npub: public key (shareable profile)
 * - nsec: sensitive key material (detection only)
 *   // nsec type recognized for NIP-19 format detection only — never stored or logged
 * - note: event ID
 * - naddr: addressable event
 * - nevent: event with relay hints
 * - nprofile: profile with relay hints
 *
 * Format: bech32(entityType + data)
 */
object Bech32Encoder {
    private var logger: UmbraLogger = NoOpUmbraLogger

    /**
     * Called exactly once, synchronously, from [com.umbra.app.UmbraApp.onCreate]. The
     * no-op default is what keeps plain JUnit tests working without wiring a real logger.
     */
    fun setLogger(logger: UmbraLogger) {
        this.logger = logger
    }

    // Entity type prefixes (HRP = Human Readable Part)
    private const val PREFIX_NPUB = "npub"
    private const val PREFIX_NSEC = "nsec"
    private const val PREFIX_NOTE = "note"
    private const val PREFIX_NADDR = "naddr"
    private const val PREFIX_NEVENT = "nevent"
    private const val PREFIX_NPROFILE = "nprofile"

    // Bech32 character set
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = CHARSET.withIndex().associate { it.value to it.index }
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    data class NaddrData(
        val identifier: String,
        val authorPubkey: String,
        val kind: Int,
        val relays: List<String>
    )

    data class NprofileData(
        val pubkey: String,
        val relays: List<String>
    )

    data class NeventData(
        val eventId: String,
        val relays: List<String>,
        val authorPubkey: String?,
        val kind: Int?
    )

    /**
     * Encode hex public key to npub1...
     * @param hexPubkey 64-character hex string
     * @return npub1xxx... format
     */
    fun encodeNpub(hexPubkey: String): String {
        return try {
            val bytes = hexToByteArray(hexPubkey)
            encodeBech32(PREFIX_NPUB, bytes)
        } catch (e: Exception) {
            logger.d { "Error encoding npub: ${scrubThrowableMessageForLogs(e)}" }
            hexPubkey  // Fallback to hex
        }
    }

    /**
     * Decode npub1... to hex public key
     * @param npub bech32-encoded public key
     * @return 64-character hex string
     */
    fun decodeNpub(npub: String): String? {
        return try {
            val (hrp, bytes) = decodeBech32(npub) ?: return null
            if (hrp != PREFIX_NPUB) {
                logger.d { "Invalid HRP: expected $PREFIX_NPUB, got $hrp" }
                return null
            }
            byteArrayToHex(bytes)
        } catch (e: Exception) {
            logger.d { "Error decoding npub: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Encode hex event ID to note1...
     * @param hexEventId 64-character hex string
     * @return note1xxx... format
     */
    fun encodeNote(hexEventId: String): String {
        return try {
            val bytes = hexToByteArray(hexEventId)
            encodeBech32(PREFIX_NOTE, bytes)
        } catch (e: Exception) {
            logger.d { "Error encoding note: ${scrubThrowableMessageForLogs(e)}" }
            hexEventId  // Fallback to hex
        }
    }

    /**
     * Decode note1... to hex event ID
     * @param note bech32-encoded event ID
     * @return 64-character hex string
     */
    fun decodeNote(note: String): String? {
        return try {
            val (hrp, bytes) = decodeBech32(note) ?: return null
            if (hrp != PREFIX_NOTE) {
                logger.d { "Invalid HRP: expected $PREFIX_NOTE, got $hrp" }
                return null
            }
            byteArrayToHex(bytes)
        } catch (e: Exception) {
            logger.d { "Error decoding note: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Decode nprofile1... into its pubkey plus any relay hints (TLV type 1) — a nprofile exists
     * specifically to carry those hints (unlike a bare npub), so dropping them meant a client
     * had no way to know "the sender who shared this link is telling you to check these relays
     * for this profile."
     */
    fun decodeNprofile(nprofile: String): NprofileData? {
        return try {
            val (hrp, bytes) = decodeBech32(nprofile) ?: return null
            if (hrp != PREFIX_NPROFILE) {
                logger.d { "Invalid HRP: expected $PREFIX_NPROFILE, got $hrp" }
                return null
            }
            val tlv = parseTlv(bytes)
            val pubkeyBytes = tlv[0]?.firstOrNull() ?: return null
            val relays = tlv[1].orEmpty().map { it.toString(Charsets.UTF_8) }
            NprofileData(pubkey = byteArrayToHex(pubkeyBytes), relays = relays)
        } catch (e: Exception) {
            logger.d { "Error decoding nprofile: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Decode nevent1... into its event id plus any relay hints (TLV type 1), author (type 2), and
     * kind (type 3) — same reasoning as [decodeNprofile]: a nevent's whole point over a bare
     * note1 is those hints, telling a client where to actually find an event it doesn't have yet.
     */
    fun decodeNevent(nevent: String): NeventData? {
        return try {
            val (hrp, bytes) = decodeBech32(nevent) ?: return null
            if (hrp != PREFIX_NEVENT) {
                logger.d { "Invalid HRP: expected $PREFIX_NEVENT, got $hrp" }
                return null
            }
            val tlv = parseTlv(bytes)
            val eventBytes = tlv[0]?.firstOrNull() ?: return null
            val relays = tlv[1].orEmpty().map { it.toString(Charsets.UTF_8) }
            val authorPubkey = tlv[2]?.firstOrNull()?.let(::byteArrayToHex)
            val kind = tlv[3]?.firstOrNull()?.let(::decodeUint32BigEndian)
            NeventData(
                eventId = byteArrayToHex(eventBytes),
                relays = relays,
                authorPubkey = authorPubkey,
                kind = kind
            )
        } catch (e: Exception) {
            logger.d { "Error decoding nevent: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Decode naddr1... into its addressable-event coordinate.
     * type 0 = d-tag identifier, type 1 = relay, type 2 = author pubkey, type 3 = kind (uint32 big-endian)
     */
    fun decodeNaddr(naddr: String): NaddrData? {
        return try {
            val (hrp, bytes) = decodeBech32(naddr) ?: return null
            if (hrp != PREFIX_NADDR) {
                logger.d { "Invalid HRP: expected $PREFIX_NADDR, got $hrp" }
                return null
            }
            val tlv = parseTlv(bytes)
            val identifier = tlv[0]?.firstOrNull()?.toString(Charsets.UTF_8).orEmpty()
            val authorPubkey = tlv[2]?.firstOrNull()?.let(::byteArrayToHex)
            val kindBytes = tlv[3]?.firstOrNull()
            val kind = kindBytes?.let(::decodeUint32BigEndian)

            if (authorPubkey.isNullOrBlank() || kind == null) {
                return null
            }

            val relays = tlv[1].orEmpty().map { it.toString(Charsets.UTF_8) }
            NaddrData(
                identifier = identifier,
                authorPubkey = authorPubkey,
                kind = kind,
                relays = relays
            )
        } catch (e: Exception) {
            logger.d { "Error decoding naddr: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Encode profile with relays: nprofile1...
     * TLV format: 0=pubkey(32), 1=relay(variable), 2=name(variable)
     *
     * @param hexPubkey 64-char hex public key
     * @param relayUrls Optional relay hints
     * @param name Optional display name
     * @return nprofile1xxx... format
     */
    fun encodeNprofile(
        hexPubkey: String,
        relayUrls: List<String> = emptyList()
    ): String {
        return try {
            val tlvBytes = mutableListOf<Byte>()

            // TLV 0: pubkey (32 bytes)
            val pubkeyBytes = hexToByteArray(hexPubkey)
            tlvBytes.add(0.toByte())  // Type 0
            tlvBytes.add(32.toByte())  // Length 32
            tlvBytes.addAll(pubkeyBytes.toList())

            // TLV 1: relay (variable per relay)
            relayUrls.forEach { relay ->
                val relayBytes = relay.toByteArray(Charsets.UTF_8)
                require(relayBytes.size <= 255) { "Relay hint exceeds 255 bytes for NIP-19 TLV encoding" }
                tlvBytes.add(1.toByte())  // Type 1
                tlvBytes.add(relayBytes.size.toByte())  // Length (byte count, not char count)
                tlvBytes.addAll(relayBytes.toList())
            }

            encodeBech32(PREFIX_NPROFILE, tlvBytes.toByteArray())
        } catch (e: Exception) {
            logger.d { "Error encoding nprofile: ${scrubThrowableMessageForLogs(e)}" }
            ""
        }
    }

    /**
     * Encode event with relays: nevent1...
     * TLV format: 0=eventId(32), 1=relay(variable), 2=author(32), 3=kind(4 bytes big-endian)
     */
    fun encodeNevent(
        hexEventId: String,
        relayUrls: List<String> = emptyList(),
        hexAuthorPubkey: String? = null,
        kind: Int? = null
    ): String {
        return try {
            val tlvBytes = mutableListOf<Byte>()

            // TLV 0: event ID (32 bytes)
            val eventBytes = hexToByteArray(hexEventId)
            tlvBytes.add(0.toByte())  // Type 0
            tlvBytes.add(32.toByte())  // Length
            tlvBytes.addAll(eventBytes.toList())

            // TLV 1: relay (optional, variable length)
            relayUrls.forEach { relay ->
                val relayBytes = relay.toByteArray(Charsets.UTF_8)
                require(relayBytes.size <= 255) { "Relay hint exceeds 255 bytes for NIP-19 TLV encoding" }
                tlvBytes.add(1.toByte())  // Type 1
                tlvBytes.add(relayBytes.size.toByte())  // Length (byte count, not char count)
                tlvBytes.addAll(relayBytes.toList())
            }

            // TLV 2: author pubkey (optional, 32 bytes)
            hexAuthorPubkey?.let {
                val authorBytes = hexToByteArray(it)
                tlvBytes.add(2.toByte())  // Type 2
                tlvBytes.add(32.toByte())  // Length
                tlvBytes.addAll(authorBytes.toList())
            }

            // TLV 3: kind (optional, 4 bytes big-endian uint32)
            kind?.let {
                tlvBytes.add(3.toByte())  // Type 3
                tlvBytes.add(4.toByte())  // Length: 4 bytes
                tlvBytes.add(((it ushr 24) and 0xFF).toByte())
                tlvBytes.add(((it ushr 16) and 0xFF).toByte())
                tlvBytes.add(((it ushr 8) and 0xFF).toByte())
                tlvBytes.add((it and 0xFF).toByte())
            }

            encodeBech32(PREFIX_NEVENT, tlvBytes.toByteArray())
        } catch (e: Exception) {
            logger.d { "Error encoding nevent: ${scrubThrowableMessageForLogs(e)}" }
            ""
        }
    }

    /**
     * Generic bech32 encoding (base32 + checksum)
     */
    private fun encodeBech32(hrp: String, data: ByteArray): String {
        return try {
            val fiveBitData = convertBits(data, 8, 5, true)
                ?: throw IllegalArgumentException("Unable to convert data to bech32 words")
            val checksum = createChecksum(hrp.lowercase(), fiveBitData)
            val payload = (fiveBitData + checksum)
                .joinToString(separator = "") { value -> CHARSET[value].toString() }
            hrp.lowercase() + "1" + payload
        } catch (e: Exception) {
            logger.d { "Error in bech32 encoding: ${scrubThrowableMessageForLogs(e)}" }
            ""
        }
    }

    /**
     * Generic bech32 decoding
     * Returns Pair<HRP, data>
     */
    private fun decodeBech32(bech32: String): Pair<String, ByteArray>? {
        return try {
            val separatorIndex = bech32.lastIndexOf('1')
            if (separatorIndex <= 0 || separatorIndex == bech32.lastIndex) {
                logger.d { "Invalid bech32 format: no '1' separator" }
                return null
            }

            val hrp = bech32.substring(0, separatorIndex).lowercase()
            val dataPart = bech32.substring(separatorIndex + 1).lowercase()
            val values = dataPart.map { char ->
                CHARSET_REV[char] ?: throw IllegalArgumentException("Invalid bech32 character: $char")
            }

            if (!verifyChecksum(hrp, values)) {
                logger.d { "Invalid bech32 checksum" }
                return null
            }

            val payload = values.dropLast(6)
            val decoded = convertBits(payload, 5, 8, false)
                ?: throw IllegalArgumentException("Unable to convert bech32 words to bytes")

            Pair(hrp, decoded.map { it.toByte() }.toByteArray())
        } catch (e: Exception) {
            logger.d { "Error decoding bech32: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Generic bech32 decoding, returning the raw 5-bit words (checksum stripped, NOT repacked
     * into 8-bit bytes) alongside the HRP — unlike [decodeBech32], whose byte repacking assumes
     * byte-aligned TLV data (true for every NIP-19 entity above) and would corrupt anything that
     * addresses its data part in native 5-bit-word units instead, e.g. BOLT11 Lightning invoices
     * (`domain.lightning.Bolt11`): a 35-bit timestamp (7 words) followed by tagged fields whose
     * length is itself measured in 5-bit words, not bytes.
     */
    internal fun decodeBech32Words(bech32: String): Pair<String, List<Int>>? {
        return try {
            val separatorIndex = bech32.lastIndexOf('1')
            if (separatorIndex <= 0 || separatorIndex == bech32.lastIndex) return null

            val hrp = bech32.substring(0, separatorIndex).lowercase()
            val dataPart = bech32.substring(separatorIndex + 1).lowercase()
            val values = dataPart.map { char ->
                CHARSET_REV[char] ?: throw IllegalArgumentException("Invalid bech32 character: $char")
            }

            if (!verifyChecksum(hrp, values)) return null

            Pair(hrp, values.dropLast(6))
        } catch (e: Exception) {
            logger.d { "Error decoding bech32 words: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Detect entity type from bech32 string
     */
    fun detectEntityType(bech32Str: String): String? {
        val hrp = bech32Str.split("1").getOrNull(0)?.lowercase()
        return when (hrp) {
            PREFIX_NPUB -> "Profile"
            PREFIX_NSEC -> "NIP-19 nsec"
            PREFIX_NOTE -> "Event"
            PREFIX_NADDR -> "Addressable Event"
            PREFIX_NEVENT -> "Event with Relays"
            PREFIX_NPROFILE -> "Profile with Relays"
            else -> null
        }
    }

    // ==================== Utilities ====================

    private fun hexToByteArray(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex string length" }
        return hex.hexToBytes()
    }

    private fun byteArrayToHex(bytes: ByteArray): String = bytes.toHex()

    private fun decodeUint32BigEndian(bytes: ByteArray): Int? {
        if (bytes.size != 4) return null
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    /**
     * Parse TLV bytes used by NIP-19 compound entities (nprofile/nevent/naddr).
     */
    private fun parseTlv(bytes: ByteArray): Map<Int, List<ByteArray>> {
        val result = mutableMapOf<Int, MutableList<ByteArray>>()
        var index = 0

        while (index + 1 < bytes.size) {
            val type = bytes[index].toInt() and 0xff
            val len = bytes[index + 1].toInt() and 0xff
            val valueStart = index + 2
            val valueEnd = valueStart + len
            if (valueEnd > bytes.size) break

            val value = bytes.copyOfRange(valueStart, valueEnd)
            result.getOrPut(type) { mutableListOf() }.add(value)
            index = valueEnd
        }

        return result
    }

    private fun hrpExpand(hrp: String): List<Int> {
        val expanded = mutableListOf<Int>()
        hrp.forEach { char -> expanded += (char.code shr 5) }
        expanded += 0
        hrp.forEach { char -> expanded += (char.code and 31) }
        return expanded
    }

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        values.forEach { value ->
            val top = checksum ushr 25
            checksum = (checksum and 0x1ffffff) shl 5 xor value
            for (i in GENERATOR.indices) {
                if (((top ushr i) and 1) != 0) {
                    checksum = checksum xor GENERATOR[i]
                }
            }
        }
        return checksum
    }

    private fun createChecksum(hrp: String, data: List<Int>): List<Int> {
        val values = hrpExpand(hrp) + data + listOf(0, 0, 0, 0, 0, 0)
        val polymod = polymod(values) xor 1
        return List(6) { index ->
            (polymod ushr (5 * (5 - index))) and 31
        }
    }

    private fun verifyChecksum(hrp: String, values: List<Int>): Boolean {
        return polymod(hrpExpand(hrp) + values) == 1
    }

    private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): List<Int>? {
        var accumulator = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxValue = (1 shl toBits) - 1
        val maxAccumulator = (1 shl (fromBits + toBits - 1)) - 1

        for (value in data) {
            if (value < 0 || (value ushr fromBits) != 0) {
                return null
            }
            accumulator = ((accumulator shl fromBits) or value) and maxAccumulator
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result += ((accumulator ushr bits) and maxValue)
            }
        }

        if (pad) {
            if (bits > 0) {
                result += ((accumulator shl (toBits - bits)) and maxValue)
            }
        } else if (bits >= fromBits || ((accumulator shl (toBits - bits)) and maxValue) != 0) {
            return null
        }

        return result
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): List<Int>? {
        return convertBits(data.map { it.toInt() and 0xff }, fromBits, toBits, pad)
    }
}


