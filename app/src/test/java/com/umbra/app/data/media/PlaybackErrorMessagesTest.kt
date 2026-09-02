package com.umbra.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorMessagesTest {

    @Test
    fun `given 404 code when mapping message then returns not found resource`() {
        val resId = playbackErrorMessageResId(404)

        assertEquals(com.umbra.app.R.string.video_error_not_found, resId)
    }

    @Test
    fun `given transient gateway code when mapping message then returns temporary unavailable resource`() {
        val resId = playbackErrorMessageResId(503)

        assertEquals(com.umbra.app.R.string.video_error_temporary_unavailable, resId)
    }

    @Test
    fun `given null code when mapping message then returns generic resource`() {
        val resId = playbackErrorMessageResId(null)

        assertEquals(com.umbra.app.R.string.video_error_generic, resId)
    }

}
