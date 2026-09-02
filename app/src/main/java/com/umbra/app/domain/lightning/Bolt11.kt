package com.umbra.app.domain.lightning

import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip21.stripNostrUriPrefix

/**
 * A BOLT11 Lightning invoice detected in note content, decoded best-effort. [amountMsat] and
 * [description] are both null when absent from the invoice (a donation-style "any amount"
 * invoice has no amount; not every invoice sets a description) or when decoding them failed —
 * detection of the invoice itself never depends on either decoding successfully, since the raw
 * [raw] string is always enough to render a copy/pay card even with nothing else known about it.
 *
 * [timestampSeconds] is the invoice's creation time (Unix seconds) and [expirySeconds] is its
 * expiry delta in seconds from that timestamp — defaulted to BOLT11's own 3600s default when the
 * invoice doesn't carry an explicit expiry ('x') tagged field, so [expirySeconds] is only null
 * when [timestampSeconds] itself couldn't be decoded. See [expiresAtEpochSeconds]/[isExpired] for
 * computing expiry against a specific "now" — kept as extension functions rather than stored
 * fields so "now" is never baked into this otherwise-static decoded value.
 */
data class Bolt11Invoice(
    val raw: String,
    val amountMsat: Long?,
    val description: String?,
    val timestampSeconds: Long? = null,
    val expirySeconds: Long? = null
)

/** Absolute expiry time (Unix seconds), or null if the invoice's creation timestamp itself
 * couldn't be decoded. */
fun Bolt11Invoice.expiresAtEpochSeconds(): Long? = timestampSeconds?.let { it + (expirySeconds ?: DEFAULT_EXPIRY_SECONDS) }

/** True if [nowEpochSeconds] is at or past this invoice's expiry; false when expiry can't be
 * determined (a malformed/undecoded timestamp shouldn't block paying an otherwise-valid invoice). */
fun Bolt11Invoice.isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
    expiresAtEpochSeconds()?.let { it <= nowEpochSeconds } ?: false

private val HRP_REGEX = Regex("""^ln(bc|tb|bcrt)(\d+)?([munp]?)$""", RegexOption.IGNORE_CASE)

// BOLT11 tagged-field types.
private const val FIELD_TYPE_DESCRIPTION = 13
private const val FIELD_TYPE_EXPIRY = 6

// BOLT11's own default expiry when an invoice carries no explicit 'x' tagged field.
private const val DEFAULT_EXPIRY_SECONDS = 3600L

private data class DecodedFields(val timestampSeconds: Long?, val description: String?, val expirySeconds: Long?)

// Amount multipliers: how many msat one whole unit of the HRP's amount digits represents.
// 1 BTC = 10^11 msat; m/u/n divide that by 10^3/10^6/10^9 respectively. 'p' (10^-12) is handled
// separately below since it divides rather than multiplies (its unit is a tenth of a msat).
private const val MSAT_PER_BTC = 100_000_000_000L
private const val MSAT_PER_MILLI_BTC = 100_000_000L
private const val MSAT_PER_MICRO_BTC = 100_000L
private const val MSAT_PER_NANO_BTC = 100L

/**
 * Parses a BOLT11 Lightning invoice string (`lnbc...`/`lntb...`/`lnbcrt...`, optionally
 * `lightning:`/`nostr:`/`nostr://`-prefixed) into a [Bolt11Invoice], or null if it isn't a
 * validly-encoded invoice at all (wrong prefix or a failed bech32 checksum). See [Bolt11Invoice]'s
 * doc comment: a decode failure for the amount/description alone doesn't fail the whole parse.
 */
fun parseBolt11(invoice: String): Bolt11Invoice? {
    val afterNostr = stripNostrUriPrefix(invoice.trim())
    val normalized = if (afterNostr.startsWith("lightning:", ignoreCase = true)) {
        afterNostr.substring("lightning:".length)
    } else {
        afterNostr
    }
    if (!normalized.startsWith("ln", ignoreCase = true)) return null

    val (hrp, words) = Bech32Encoder.decodeBech32Words(normalized) ?: return null
    if (!HRP_REGEX.matches(hrp)) return null

    val amountMsat = parseAmountMsat(hrp)
    val decodedFields = runCatching { parseTaggedFields(words) }.getOrNull()

    return Bolt11Invoice(
        raw = normalized,
        amountMsat = amountMsat,
        description = decodedFields?.description,
        timestampSeconds = decodedFields?.timestampSeconds,
        expirySeconds = decodedFields?.timestampSeconds?.let { decodedFields.expirySeconds ?: DEFAULT_EXPIRY_SECONDS }
    )
}

private fun parseAmountMsat(hrp: String): Long? {
    val match = HRP_REGEX.find(hrp) ?: return null
    val digits = match.groupValues[2]
    if (digits.isEmpty()) return null // no amount segment == "any amount" invoice
    val value = digits.toLongOrNull() ?: return null
    return when (match.groupValues[3].lowercase()) {
        "" -> value * MSAT_PER_BTC
        "m" -> value * MSAT_PER_MILLI_BTC
        "u" -> value * MSAT_PER_MICRO_BTC
        "n" -> value * MSAT_PER_NANO_BTC
        // 'p' = pico-BTC (10^-12 BTC = 0.1 msat); BOLT11 requires the value be a multiple of 10
        // so it always lands on a whole msat.
        "p" -> value / 10L
        else -> null
    }
}

/**
 * Decodes the leading 35-bit creation timestamp and walks the BOLT11 data part's tagged fields
 * looking for type 13 (short description) and type 6 (expiry). The first 7 five-bit words are
 * always the timestamp; each tagged field after that is type(1 word) + data_length(2 words,
 * 10-bit big-endian) + data(data_length words).
 */
private fun parseTaggedFields(words: List<Int>): DecodedFields {
    if (words.size < 7) return DecodedFields(timestampSeconds = null, description = null, expirySeconds = null)
    val timestampSeconds = words.take(7).fold(0L) { acc, word -> (acc shl 5) or word.toLong() }

    var description: String? = null
    var expirySeconds: Long? = null
    var index = 7
    while (index + 3 <= words.size) {
        val type = words[index]
        val dataLength = (words[index + 1] shl 5) or words[index + 2]
        val dataStart = index + 3
        val dataEnd = dataStart + dataLength
        if (dataEnd > words.size) break
        when (type) {
            FIELD_TYPE_DESCRIPTION -> description = fiveBitWordsToBytes(words.subList(dataStart, dataEnd)).toString(Charsets.UTF_8)
            FIELD_TYPE_EXPIRY -> expirySeconds = words.subList(dataStart, dataEnd).fold(0L) { acc, word -> (acc shl 5) or word.toLong() }
        }
        index = dataEnd
    }
    return DecodedFields(timestampSeconds = timestampSeconds, description = description, expirySeconds = expirySeconds)
}

/** Packs 5-bit words into bytes, discarding any trailing partial byte (BOLT11 zero-pads it). */
private fun fiveBitWordsToBytes(words: List<Int>): ByteArray {
    val out = mutableListOf<Byte>()
    var accumulator = 0
    var bits = 0
    for (word in words) {
        accumulator = (accumulator shl 5) or word
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out += ((accumulator ushr bits) and 0xFF).toByte()
        }
    }
    return out.toByteArray()
}
