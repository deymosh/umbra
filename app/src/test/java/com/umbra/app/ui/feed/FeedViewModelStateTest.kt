package com.umbra.app.ui.feed

import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.ui.common.UiMessage
import org.junit.Assert.assertEquals
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

    @Test
    fun `given a successful mute write when mapping the result then returns the mute success message`() {
        val message = muteWriteResultMessage(Result.success(Unit))

        assertEquals(UiMessage.Res(R.string.user_muted_success), message)
    }

    @Test
    fun `given a failed mute write when mapping the result then returns the mute error message with the failure text`() {
        val message = muteWriteResultMessage(Result.failure<Unit>(IllegalStateException("boom")))

        val resWithArgs = message as UiMessage.ResWithArgs
        assertEquals(R.string.error_mute_author, resWithArgs.id)
        assertEquals("boom", resWithArgs.args.single())
    }

    @Test
    fun `given a failed mute write with a null exception message when mapping the result then the formatted argument is empty`() {
        val message = muteWriteResultMessage(Result.failure<Unit>(IllegalStateException()))

        val resWithArgs = message as UiMessage.ResWithArgs
        assertEquals(R.string.error_mute_author, resWithArgs.id)
        assertEquals("", resWithArgs.args.single())
    }

    @Test
    fun `given a successful pin write when the note was previously unpinned then returns the pinned success message`() {
        val message = pinWriteResultMessage(Result.success(Unit), wasPinned = false)

        assertEquals(UiMessage.Res(R.string.note_pinned_success), message)
    }

    @Test
    fun `given a successful pin write when the note was previously pinned then returns the unpinned success message`() {
        val message = pinWriteResultMessage(Result.success(Unit), wasPinned = true)

        assertEquals(UiMessage.Res(R.string.note_unpinned_success), message)
    }

    @Test
    fun `given a failed pin write when the note was previously unpinned then returns the pin error message with the failure text`() {
        val message = pinWriteResultMessage(Result.failure<Unit>(IllegalStateException("boom")), wasPinned = false)

        val resWithArgs = message as UiMessage.ResWithArgs
        assertEquals(R.string.error_pin_note, resWithArgs.id)
        assertEquals("boom", resWithArgs.args.single())
    }

    @Test
    fun `given a failed pin write when the note was previously pinned then returns the unpin error message with the failure text`() {
        val message = pinWriteResultMessage(Result.failure<Unit>(IllegalStateException("boom")), wasPinned = true)

        val resWithArgs = message as UiMessage.ResWithArgs
        assertEquals(R.string.error_unpin_note, resWithArgs.id)
        assertEquals("boom", resWithArgs.args.single())
    }
}

