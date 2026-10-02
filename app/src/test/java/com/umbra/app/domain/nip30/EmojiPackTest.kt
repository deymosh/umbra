package com.umbra.app.domain.nip30

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiPackTest {

    private val author = "a".repeat(64)

    private fun set(d: String, createdAt: Long, vararg extra: List<String>) = Event(
        id = "$d$createdAt".padEnd(64, '0'),
        pubkey = author,
        createdAt = createdAt,
        kind = Event.KIND_EMOJI_SET,
        tags = listOf(listOf("d", d), listOf("emoji", "frog", "https://img.example/frog.png")) + extra,
        content = "",
        sig = "s".repeat(128)
    )

    @Test
    fun `given a set with a title when parsing then metadata and emoji are read`() {
        val pack = parseEmojiPack(set("frogs", 10, listOf("title", "Frogs"), listOf("image", "https://img.example/cover.png")))!!

        assertEquals(EmojiSetAddress(author, "frogs"), pack.address)
        assertEquals("Frogs", pack.title)
        assertEquals("https://img.example/cover.png", pack.image)
        assertEquals(listOf("frog"), pack.emojis.map { it.shortcode })
    }

    @Test
    fun `given a non-http cover image when parsing then it is dropped`() {
        assertNull(parseEmojiPack(set("frogs", 10, listOf("image", "file:///etc/passwd")))!!.image)
    }

    @Test
    fun `given two revisions of a set when picking the latest then only the newest remains`() {
        val packs = latestEmojiPacks(listOf(set("frogs", 10, listOf("title", "Old")), set("frogs", 20, listOf("title", "New"))))

        assertEquals(listOf("New"), packs.map { it.title })
    }

    @Test
    fun `given a coordinate when parsing and printing then it round trips`() {
        val address = EmojiSetAddress(author, "frogs")

        assertEquals(address, parseEmojiSetCoordinate(address.coordinate()))
        assertNull(parseEmojiSetCoordinate("30030:notahexkey:frogs"))
    }
}
