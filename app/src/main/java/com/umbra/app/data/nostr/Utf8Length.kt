package com.umbra.app.data.nostr

/**
 * Exact UTF-8 encoded length of [text] in bytes, computed without materializing the encoded copy.
 * Measuring every relay frame via `text.toByteArray(Charsets.UTF_8).size` allocated a full byte
 * array per frame purely to read its size — the hottest allocation in the app — when only the
 * count was ever needed.
 *
 * Matches `String.toByteArray(Charsets.UTF_8).size` byte for byte, including its handling of
 * malformed input: an unpaired surrogate (high with no low following, or a lone low) is replaced by
 * the encoder with a single '?', so it counts as one byte, not the three a well-formed BMP
 * character of the same range would take.
 */
internal fun utf8ByteLength(text: CharSequence): Int {
    var bytes = 0
    var i = 0
    val length = text.length
    while (i < length) {
        val c = text[i].code
        when {
            c < 0x80 -> bytes += 1
            c < 0x800 -> bytes += 2
            c in 0xD800..0xDBFF -> {
                val next = if (i + 1 < length) text[i + 1].code else -1
                if (next in 0xDC00..0xDFFF) {
                    // A valid surrogate pair encodes as one 4-byte code point; consume both chars.
                    bytes += 4
                    i++
                } else {
                    bytes += 1
                }
            }
            c in 0xDC00..0xDFFF -> bytes += 1
            else -> bytes += 3
        }
        i++
    }
    return bytes
}
