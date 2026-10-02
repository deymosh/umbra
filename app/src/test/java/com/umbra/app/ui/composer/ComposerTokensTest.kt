package com.umbra.app.ui.composer

import com.umbra.app.domain.nip30.CustomEmoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTokensTest {

    private val frog = CustomEmoji("frog", "https://img.example/frog.png")
    private val emojis = mapOf("frog" to frog)
    private val label: (String) -> String = { "@name" }

    @Test
    fun `given a known shortcode when tokenizing then it becomes one placeholder character`() {
        val text = "hi :frog: there"

        val tokens = composerTokens(text, label, emojis)

        assertEquals(listOf(ComposerToken.Emoji(3, 9, frog)), tokens)
        assertEquals("hi ${EMOJI_PLACEHOLDER} there", displayTextFor(text, tokens))
    }

    @Test
    fun `given an unknown shortcode when tokenizing then it stays text`() {
        assertTrue(composerTokens("a :toad: b", label, emojis).isEmpty())
    }

    @Test
    fun `given adjacent emoji when tokenizing then each is its own token`() {
        val tokens = composerTokens(":frog::frog:", label, emojis)

        assertEquals(2, tokens.size)
        assertEquals(EMOJI_PLACEHOLDER.repeat(2), displayTextFor(":frog::frog:", tokens))
    }

    @Test
    fun `given a mention and an emoji when tokenizing then both are replaced in order`() {
        val text = "nostr:npub1abc :frog:"

        val tokens = composerTokens(text, label, emojis)

        assertEquals("@name ${EMOJI_PLACEHOLDER}", displayTextFor(text, tokens))
    }
}
