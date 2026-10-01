package com.umbra.app.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8LengthTest {

    // The whole contract is byte-for-byte agreement with Java's UTF-8 encoder, so every case is
    // checked against that encoder's own output rather than a hand-computed constant.
    private fun assertMatchesUtf8Encoder(text: String) {
        assertEquals(text.toByteArray(Charsets.UTF_8).size, utf8ByteLength(text))
    }

    @Test
    fun `given an empty string when measuring then it is zero bytes`() {
        assertMatchesUtf8Encoder("")
    }

    @Test
    fun `given ASCII text when measuring then matches the encoder`() {
        assertMatchesUtf8Encoder("Hello, Nostr! 0123456789 ~!#\$%^&*()")
    }

    @Test
    fun `given Latin-1 accented text when measuring then matches the encoder`() {
        assertMatchesUtf8Encoder("café résumé naïve Grüße")
    }

    @Test
    fun `given CJK text when measuring then matches the encoder`() {
        assertMatchesUtf8Encoder("日本語のテキスト 中文 漢字")
    }

    @Test
    fun `given emoji surrogate pairs when measuring then matches the encoder`() {
        assertMatchesUtf8Encoder("😀🎉🚀🔒")
    }

    @Test
    fun `given a zero-width-joiner emoji sequence when measuring then matches the encoder`() {
        // A ZWJ sequence interleaves surrogate-pair emoji (4 bytes each) with U+200D (3 bytes),
        // so it only matches if the pair is consumed as a unit.
        assertMatchesUtf8Encoder("👨👩👧👦")
    }

    @Test
    fun `given a lone high surrogate when measuring then matches the encoder`() {
        // Java's encoder replaces an unpaired surrogate with '?' — one byte, not the three a
        // well-formed BMP character of the same range would take.
        assertMatchesUtf8Encoder("abc\uD83Ddef")
    }

    @Test
    fun `given a lone low surrogate when measuring then matches the encoder`() {
        assertMatchesUtf8Encoder("abc\uDE00def")
    }

    @Test
    fun `given a 100k-char mixed string when measuring then matches the encoder`() {
        // 5 chars per unit, repeated to exactly 100k — ASCII, Latin-1, CJK and a surrogate pair.
        val mixed = "aé日😀".repeat(20_000)
        assertMatchesUtf8Encoder(mixed)
    }
}
