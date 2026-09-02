package com.umbra.app.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Bitmap.createBitmap() isn't mocked in plain JVM unit tests (no Robolectric in this project),
 * so BlurHash needs a real device/emulator — same reasoning as
 * MediaMetadataStripperInstrumentedTest. Method names avoid spaces/backticks — DEX rejects
 * space characters in method names below DEX version 040, unlike plain JVM unit tests.
 */
@RunWith(AndroidJUnit4::class)
class BlurHashInstrumentedTest {

    // The canonical example from blurha.sh's own README/demo — known-valid encoding.
    private val knownGoodHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"

    @Test
    fun givenKnownGoodHash_whenDecoding_thenProducesNonNullBitmapOfRequestedWidth() {
        val bitmap = BlurHash.decode(knownGoodHash, width = 32)

        assertNotNull(bitmap)
        assertEquals(32, bitmap!!.width)
        assertEquals(true, bitmap.height > 0)
    }

    @Test
    fun givenExplicitAspectRatio_whenDecoding_thenHeightMatchesIt() {
        val bitmap = BlurHash.decode(knownGoodHash, width = 40, aspectRatio = 2f)

        assertNotNull(bitmap)
        assertEquals(20, bitmap!!.height)
    }

    @Test
    fun givenNullHash_whenDecoding_thenReturnsNull() {
        assertNull(BlurHash.decode(null, width = 32))
    }

    @Test
    fun givenTooShortHash_whenDecoding_thenReturnsNull() {
        assertNull(BlurHash.decode("abc", width = 32))
    }

    @Test
    fun givenLengthMismatchingDeclaredComponentCount_whenDecoding_thenReturnsNull() {
        // Valid alphabet characters, but truncated relative to what the first two chars declare.
        assertNull(BlurHash.decode(knownGoodHash.dropLast(4), width = 32))
    }

    @Test
    fun givenNonAlphabetCharacters_whenDecoding_thenReturnsNullInsteadOfThrowing() {
        assertNull(BlurHash.decode("!!!!!!!!!!!!!!!!!!!!!!!!!!!!", width = 32))
    }

    @Test
    fun givenZeroWidth_whenDecoding_thenReturnsNull() {
        assertNull(BlurHash.decode(knownGoodHash, width = 0))
    }

    // ---- Encode ----

    private fun solidColorBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { color }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun channelDelta(a: Int, b: Int, shift: Int): Int =
        abs(((a shr shift) and 0xff) - ((b shr shift) and 0xff))

    @Test
    fun givenSolidColorBitmap_whenEncodingThenDecoding_thenApproximatesTheOriginalColor() {
        val original = Color.rgb(200, 80, 40)
        val bitmap = solidColorBitmap(20, 20, original or (0xff shl 24))

        val hash = BlurHash.encode(bitmap)
        val decoded = BlurHash.decode(hash, width = 8)

        assertNotNull(decoded)
        val decodedPixel = decoded!!.getPixel(4, 4)
        // A solid color's DC component should round-trip closely; generous tolerance since
        // this is a lossy encode/decode round trip through sRGB<->linear conversions, not an
        // exact match.
        assertTrue(channelDelta(original, decodedPixel, 16) <= 12) // red
        assertTrue(channelDelta(original, decodedPixel, 8) <= 12) // green
        assertTrue(channelDelta(original, decodedPixel, 0) <= 12) // blue
    }

    @Test
    fun givenWideBitmap_whenEncoding_thenProducesADecodableHash() {
        val wide = solidColorBitmap(200, 50, Color.WHITE)
        val tall = solidColorBitmap(50, 200, Color.WHITE)

        val wideHash = BlurHash.encode(wide)
        val tallHash = BlurHash.encode(tall)

        assertNotNull(BlurHash.decode(wideHash, width = 16))
        assertNotNull(BlurHash.decode(tallHash, width = 16))
    }

    @Test
    fun givenLargeBitmap_whenEncoding_thenStillProducesADecodableHash() {
        // Exercises the >100px downscale-before-encoding path.
        val large = solidColorBitmap(400, 300, Color.rgb(10, 200, 30))

        val hash = BlurHash.encode(large)

        assertNotNull(BlurHash.decode(hash, width = 16))
    }
}
