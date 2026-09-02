package com.umbra.app.ui.components.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlayerControllerTest {

    @Test
    fun `given square pixels when computing aspect ratio then returns width over height`() {
        val result = computeVideoAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = 1.0f)

        assertEquals(1920f / 1080f, result!!, 0.0001f)
    }

    @Test
    fun `given anamorphic pixel ratio when computing aspect ratio then pixelWidthHeightRatio is applied`() {
        val result = computeVideoAspectRatio(width = 1440, height = 1080, pixelWidthHeightRatio = 1.333f)

        assertEquals((1440f * 1.333f) / 1080f, result!!, 0.0001f)
    }

    @Test
    fun `given zero width when computing aspect ratio then returns null`() {
        val result = computeVideoAspectRatio(width = 0, height = 1080, pixelWidthHeightRatio = 1.0f)

        assertNull(result)
    }
}
