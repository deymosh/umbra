package com.umbra.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real on-device verification of [MediaMetadataStripper] against a real photo: `exif_sample.jpg`
 * (`androidTest/assets/`) was captured with the emulator's own camera + a granted location
 * permission, so it carries genuine EXIF (`Make=Google`, `Model=sdk_gphone64_x86_64`,
 * `DateTime`/`DateTimeOriginal`) written by the platform camera stack — not hand-crafted bytes.
 *
 * Deliberately never touches the network/upload path — this only calls the stripper directly
 * and inspects its output, isolating strip failures from upload/network flakiness.
 */
@RunWith(AndroidJUnit4::class)
class MediaMetadataStripperInstrumentedTest {

    private lateinit var context: Context
    private lateinit var sourceUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context

        val copy = File(context.cacheDir, "exif_sample_input.jpg")
        instrumentationContext.assets.open("exif_sample.jpg").use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        }
        sourceUri = Uri.fromFile(copy)
    }

    @Test
    fun sourceFixtureCarriesSensitiveExifBeforeStripping() {
        // Sanity check on the fixture itself: if this ever fails, the test below would be
        // vacuously true (stripping "clean" metadata proves nothing).
        val exif = ExifInterface(context.contentResolver.openInputStream(sourceUri)!!)
        assertEquals("Google", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("sdk_gphone64_x86_64", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertTrue(exif.getAttribute(ExifInterface.TAG_DATETIME) != null)
    }

    @Test
    fun stripImageMetadataRemovesMakeModelAndTimestamps() {
        val result = MediaMetadataStripper.strip(sourceUri, "image/jpeg", context)

        assertTrue("Expected stripping to succeed on a plain JPEG", result.stripped)

        val strippedExif = ExifInterface(context.contentResolver.openInputStream(result.uri)!!)
        assertNull(strippedExif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(strippedExif.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(strippedExif.getAttribute(ExifInterface.TAG_DATETIME))
        assertNull(strippedExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(strippedExif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
        assertNull(strippedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(strippedExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
    }

    @Test
    fun strippedImageStillDecodesWithSamePixelDimensions() {
        val originalBitmap = context.contentResolver.openInputStream(sourceUri)!!.use {
            BitmapFactory.decodeStream(it)
        }
        val result = MediaMetadataStripper.strip(sourceUri, "image/jpeg", context)
        val strippedBitmap = context.contentResolver.openInputStream(result.uri)!!.use {
            BitmapFactory.decodeStream(it)
        }

        assertEquals(originalBitmap.width, strippedBitmap.width)
        assertEquals(originalBitmap.height, strippedBitmap.height)
    }

    @Test
    fun unsupportedMimeTypeFailsClosed() {
        val result = MediaMetadataStripper.strip(sourceUri, "application/octet-stream", context)
        assertTrue("Non-image/video mime types must fail closed, never pass through", !result.stripped)
    }

    @Test
    fun gifPassesThroughUnmodifiedAsAlreadyStripped() {
        // GIF has no EXIF container at all (see MediaMetadataStripper's class doc comment) — the
        // branch never reads the file, so reusing the JPEG fixture's URI here only exercises the
        // mimeType dispatch, not actual GIF bytes.
        val result = MediaMetadataStripper.strip(sourceUri, "image/gif", context)

        assertTrue("GIF has nothing to strip and must not fail closed", result.stripped)
        assertEquals(sourceUri, result.uri)
        assertEquals("image/gif", result.mimeType)
    }
}
