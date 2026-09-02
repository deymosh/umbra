package com.umbra.app.ui.components.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UserAvatarCacheKeyTest {

    @Test
    fun `given equivalent pubkey casing when building key then cache identity is shared`() {
        val lower = buildAvatarMemoryCacheKey("abcdef", "https://example.com/avatar.jpg", 96)
        val upper = buildAvatarMemoryCacheKey("ABCDEF", "https://example.com/avatar.jpg", 96)

        assertEquals(lower, upper)
    }

    @Test
    fun `given different target sizes when building key then decoded bitmaps do not collide`() {
        val small = buildAvatarMemoryCacheKey("abcdef", "https://example.com/avatar.jpg", 96)
        val large = buildAvatarMemoryCacheKey("abcdef", "https://example.com/avatar.jpg", 256)

        assertNotEquals(small, large)
    }

    @Test
    fun `given case-sensitive urls when building key then cache identities remain distinct`() {
        val first = buildAvatarMemoryCacheKey("abcdef", "https://example.com/Avatar.jpg", 96)
        val second = buildAvatarMemoryCacheKey("abcdef", "https://example.com/avatar.jpg", 96)

        assertNotEquals(first, second)
    }

    @Test
    fun `given gif url with query when detecting animation then returns true`() {
        val animated = isAnimatedAvatarUrl("https://example.com/avatar.GIF?size=96")

        org.junit.Assert.assertTrue(animated)
    }

    @Test
    fun `given gif token outside path when detecting animation then returns false`() {
        val animated = isAnimatedAvatarUrl("https://example.com/avatar.jpg?format=.gif")

        org.junit.Assert.assertFalse(animated)
    }
}