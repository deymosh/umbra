package com.umbra.app.ui.feed

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedViewModelStateTest {

    @Test
    fun `given loading ui and no computed events when evaluating initial loading then returns true`() {
        val uiState = FeedState(isLoading = true)

        val shouldShowLoading = shouldShowFeedInitialLoading(uiState, computedEvents = emptyList())

        assertTrue(shouldShowLoading)
    }

    @Test
    fun `given loading ui and cached computed events when evaluating initial loading then returns false`() {
        val uiState = FeedState(isLoading = true)
        val cachedEvent = Event(
            id = "a".repeat(64),
            pubkey = "b".repeat(64),
            createdAt = 1_700_000_000L,
            kind = Event.KIND_TEXT_NOTE,
            content = "cached",
            sig = "c".repeat(128)
        )

        val shouldShowLoading = shouldShowFeedInitialLoading(uiState, computedEvents = listOf(cachedEvent))

        assertFalse(shouldShowLoading)
    }
}

