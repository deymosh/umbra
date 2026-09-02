package com.umbra.app.domain.nip68

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureEventTest {

    private fun event(kind: Int, tags: List<List<String>>, content: String = "a scenic view"): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = kind,
        tags = tags,
        content = content,
        sig = "c".repeat(128)
    )

    @Test
    fun `given non picture kind when extracting then returns null`() {
        assertNull(extractPictureEvent(event(kind = Event.KIND_TEXT_NOTE, tags = emptyList())))
    }

    @Test
    fun `given picture event with title and images when extracting then returns full model`() {
        val target = event(
            kind = Event.KIND_PICTURE,
            tags = listOf(
                listOf("title", "Costa Rica coast"),
                listOf(
                    "imeta",
                    "url https://nostr.build/i/my-image.jpg",
                    "m image/jpeg",
                    "dim 3024x4032",
                    "alt A scenic photo"
                )
            )
        )

        val picture = extractPictureEvent(target)

        assertEquals("Costa Rica coast", picture?.title)
        assertEquals("a scenic view", picture?.description)
        assertEquals(1, picture?.images?.size)
        assertEquals("https://nostr.build/i/my-image.jpg", picture?.images?.first()?.url)
        assertEquals("image/jpeg", picture?.images?.first()?.mimeType)
        assertNull(picture?.contentWarning)
    }

    @Test
    fun `given picture event without title when extracting then title is null`() {
        val picture = extractPictureEvent(event(kind = Event.KIND_PICTURE, tags = emptyList()))

        assertNull(picture?.title)
        assertTrue(picture?.images?.isEmpty() == true)
    }

    @Test
    fun `given nsfw picture event when extracting then includes content warning`() {
        val target = event(
            kind = Event.KIND_PICTURE,
            tags = listOf(listOf("content-warning", "graphic imagery"))
        )

        assertEquals("graphic imagery", extractPictureEvent(target)?.contentWarning?.reason)
    }
}
