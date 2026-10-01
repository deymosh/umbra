package com.umbra.app.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NIP-30: kind-0 `emoji` tags must land on the parsed profile for `:shortcode:` rendering. */
class UserProfileCustomEmojiTest {

    @Test
    fun `given kind-0 content with emoji tags when fromJSON then customEmojis populated`() {
        val profile = UserProfile.fromJSON(
            pubkey = "a".repeat(64),
            jsonContent = """{"name":":umbra:","about":"pick me :moon:"}""",
            createdAt = 100L,
            eventTags = listOf(
                listOf("emoji", "umbra", "https://example.com/umbra.png"),
                listOf("emoji", "moon", "https://example.com/moon.png")
            )
        )

        assertEquals(":umbra:", profile.name)
        assertEquals("https://example.com/umbra.png", profile.customEmojis["umbra"])
        assertEquals("https://example.com/moon.png", profile.customEmojis["moon"])
        assertEquals(2, profile.customEmojis.size)
    }

    @Test
    fun `given non-https emoji urls when fromJSON then they are dropped`() {
        val profile = UserProfile.fromJSON(
            pubkey = "a".repeat(64),
            jsonContent = """{"name":"x"}""",
            createdAt = 100L,
            eventTags = listOf(
                listOf("emoji", "bad", "data:image/png;base64,AAAA"),
                listOf("emoji", "good", "https://example.com/good.png")
            )
        )

        assertNull(profile.customEmojis["bad"])
        assertEquals("https://example.com/good.png", profile.customEmojis["good"])
    }

    @Test
    fun `given no emoji tags when fromJSON then customEmojis empty and parsing still works`() {
        val profile = UserProfile.fromJSON(
            pubkey = "a".repeat(64),
            jsonContent = """{"name":"alice"}""",
            createdAt = 100L
        )

        assertTrue(profile.customEmojis.isEmpty())
        assertEquals("alice", profile.name)
    }

    @Test
    fun `given malformed content when fromJSON then falls back without crashing`() {
        val profile = UserProfile.fromJSON(
            pubkey = "a".repeat(64),
            jsonContent = """not json""",
            createdAt = 100L,
            eventTags = listOf(listOf("emoji", "x", "https://example.com/x.png"))
        )

        assertTrue(profile.customEmojis.isEmpty())
        assertNull(profile.name)
    }
}
