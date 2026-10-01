package com.umbra.app.domain.nip30

import com.umbra.app.domain.usecase.EmojiSetContent
import com.umbra.app.domain.usecase.toGroups
import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiGroupTest {

    private fun emoji(shortcode: String) = CustomEmoji(shortcode, "https://img.example/$shortcode.png")

    @Test
    fun `allEmojis flattens groups and keeps the first shortcode occurrence`() {
        val groups = listOf(
            EmojiGroup(null, listOf(emoji("a"), emoji("b"))),
            EmojiGroup("Set", listOf(emoji("b"), emoji("c")))
        )
        assertEquals(listOf("a", "b", "c"), groups.allEmojis().map { it.shortcode })
    }

    @Test
    fun `toGroups keeps inline first, sets in order, drops empty groups and dupes`() {
        val inline = listOf(emoji("inline"), emoji("shared"))
        val setA = EmojiSetAddress("1".repeat(64), "set-a")
        val setB = EmojiSetAddress("1".repeat(64), "set-b")
        val groups = toGroups(
            inline = inline,
            setContents = listOf(
                EmojiSetContent(setA, listOf(emoji("shared"), emoji("za")), title = "Party"),
                EmojiSetContent(setB, emptyList(), title = "set-b")
            )
        )

        assertEquals(null, groups[0].title)
        assertEquals(listOf("inline", "shared"), groups[0].emojis.map { it.shortcode })
        assertEquals("Party", groups[1].title)
        assertEquals(listOf("za"), groups[1].emojis.map { it.shortcode })
        assertEquals(2, groups.size)
    }

    @Test
    fun `toGroups with nothing leaves an empty list`() {
        assertEquals(emptyList<EmojiGroup>(), toGroups(emptyList(), emptyList()))
    }
}
