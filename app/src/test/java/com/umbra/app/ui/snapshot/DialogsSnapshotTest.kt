package com.umbra.app.ui.snapshot

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.ui.components.ActionItem
import com.umbra.app.ui.components.ActionsBottomSheet
import com.umbra.app.ui.components.ConfirmDialog
import com.umbra.app.ui.components.EmojiReactionPickerSheet
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.PrivacyLogoutProgressDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class DialogsSnapshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun leaveTor() = compose.snapshotScreen("Dialog_leaveTor") {
        ExternalUrlWarningDialog(url = "https://example.com/some/article?id=42", onConfirm = {}, onDismiss = {})
    }

    @Test
    fun confirmDelete() = compose.snapshotScreen("Dialog_confirmDelete") {
        ConfirmDialog(
            title = "Delete this note?",
            message = "Relays that honour deletion requests will remove it. Others may keep a copy.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
            isDestructive = true
        )
    }

    @Test
    fun logoutWipe() = compose.snapshotScreen("Dialog_logoutWipe") { PrivacyLogoutProgressDialog() }

    @Test
    fun noteActions() = compose.snapshotScreen("Sheet_noteActions") {
        Box(Modifier.fillMaxSize()) {
            ActionsBottomSheet(
                actions = listOf(
                    ActionItem(Icons.Default.PushPin, "Pin to profile") {},
                    ActionItem(Icons.AutoMirrored.Filled.VolumeOff, "Mute author") {},
                    ActionItem(Icons.Default.ContentCopy, "Copy note ID") {},
                    ActionItem(Icons.Default.ContentCopy, "Copy text") {},
                    ActionItem(Icons.Default.Delete, "Delete note", destructive = true) {}
                ),
                onDismissRequest = {}
            )
        }
    }

    @Test
    fun reactionPicker() = compose.snapshotScreen("Sheet_reactionPicker") {
        EmojiReactionPickerSheet(
            reactionEmojis = listOf("❤️", "🔥", "🤙", "😂", "🌑", "⚡", "👀", "🙏", "💜", "🫡").map { ReactionEmoji.Unicode(it) },
            onSelect = { _, _ -> },
            onAddReactionEmoji = {},
            onRemoveReactionEmoji = {},
            onDismissRequest = {}
        )
    }
}
