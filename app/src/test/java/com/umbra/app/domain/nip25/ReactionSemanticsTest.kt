package com.umbra.app.domain.nip25

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionSemanticsTest {

    @Test
    fun `given reaction content when checking for a dislike then only a minus is one`() {
        assertTrue(isDislikeReactionContent("-"))
        assertTrue(isDislikeReactionContent(" - "))
        assertFalse(isDislikeReactionContent("+"))
        assertFalse(isDislikeReactionContent(""))
        assertFalse(isDislikeReactionContent("🔥"))
        assertFalse(isDislikeReactionContent(":soapbox:"))
    }
}
