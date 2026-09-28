package com.umbra.app.ui.composer

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.SnapshotFixtures
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class ComposerSnapshotTest {

    private val me = SnapshotFixtures.bob

    @Composable
    private fun Layout(state: ComposerState, text: String, showPreview: Boolean = false) {
        ComposerLayout(
            state = state,
            textState = TextFieldState(text),
            userRepository = SnapshotFixtures.userRepository,
            dataSourceFactory = SnapshotFixtures.tors,
            displayNameForPubkey = { null },
            getQuotedEvent = { null },
            getQuotedEventAuthorProfile = { null },
            onClose = {},
            onPublish = {},
            onPickMedia = {},
            onSelectMention = {},
            onSensitiveChange = {},
            initialShowPreview = showPreview
        )
    }

    private val base = ComposerState(
        currentUserPubkey = me.pubkey,
        currentUserProfile = me,
        canSign = true
    )

    @Test
    fun newNoteEmpty() = snapshot("Composer_new_empty") {
        Layout(base, "")
    }

    @Test
    fun newNoteWithPreview() = snapshot("Composer_new_preview") {
        Layout(
            base.copy(removedTrackingToken = true),
            "Watching the partial phase from the rooftop. Sharing the livestream link below.\n\nhttps://example.com/live #eclipse",
            showPreview = true
        )
    }

    @Test
    fun mentionSuggestions() = snapshot("Composer_mentions") {
        Layout(
            base.copy(mentionSuggestions = listOf(SnapshotFixtures.alice, SnapshotFixtures.carol)),
            "Thanks for the tips @al"
        )
    }

    @Test
    fun reply() = snapshot("Composer_reply") {
        Layout(
            base.copy(
                isReplyMode = true,
                replyToEvent = SnapshotFixtures.textNote,
                replyToProfile = SnapshotFixtures.alice,
                sensitiveContent = true,
                isUploadingAttachment = true
            ),
            "Namibia, 2027 is already booked. Your corona shot is unreal."
        )
    }
}
