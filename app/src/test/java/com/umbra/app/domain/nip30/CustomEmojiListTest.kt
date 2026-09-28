package com.umbra.app.domain.nip30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomEmojiListTest {

    private val owner = "a".repeat(64)

    @Test
    fun `given a user emoji list when parsed then inline emoji and well-formed set addresses are returned`() {
        val (inline, sets) = parseUserEmojiList(
            listOf(
                listOf("emoji", "soapbox", "https://example.com/soapbox.png"),
                listOf("emoji", "bad shortcode", "https://example.com/x.png"),
                listOf("a", "30030:$owner:blobcats"),
                listOf("a", "30023:$owner:article"),
                listOf("a", "30030:not-hex:set")
            )
        )

        assertEquals(listOf("soapbox"), inline.map { it.shortcode })
        assertEquals(listOf(EmojiSetAddress(owner, "blobcats")), sets)
    }

    @Test
    fun `given content using one of two emoji when tagging then only the used emoji is tagged`() {
        val available = listOf(
            CustomEmoji("wave", "https://example.com/wave.png"),
            CustomEmoji("cat", "https://example.com/cat.png")
        )

        val tags = emojiTagsFor("hello :wave: there", available)

        assertEquals(listOf(listOf("emoji", "wave", "https://example.com/wave.png")), tags)
    }

    @Test
    fun `given a colon query at the caret when detecting then the partial shortcode is returned`() {
        assertEquals(EmojiQuery(6, "wa"), detectEmojiQuery("hello :wa", 9))
    }

    @Test
    fun `given a closed shortcode or a mid-word colon when detecting then no query is returned`() {
        assertNull(detectEmojiQuery("hello :wave:", 12))
        assertNull(detectEmojiQuery("time 10:30", 10))
    }
}
