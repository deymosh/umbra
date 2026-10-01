package com.umbra.app.ui.components

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.EmojiGroup
import com.umbra.app.ui.composer.ComposerEmojiContent
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun emoji(shortcode: String) = CustomEmoji(shortcode, "https://img.example/$shortcode.png")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class EmojiPickerSnapshotTest {

    private val quickReactions = listOf(
        ReactionEmoji.Unicode("👍"),
        ReactionEmoji.Unicode("❤️"),
        ReactionEmoji.Unicode("😂"),
        ReactionEmoji.Unicode("😮"),
        ReactionEmoji.Unicode("🔥"),
        ReactionEmoji.Custom(CustomEmoji("eclipse", "https://img.example/eclipse.png"))
    )

    private val groups = listOf(
        EmojiGroup(title = null, emojis = listOf(emoji("gm"), emoji("gn"))),
        EmojiGroup(title = "Party frogs", emojis = listOf(emoji("frogs"), emoji("pepe"), emoji("dance")))
    )

    private fun renderReactionPicker(name: String, groupsParam: List<EmojiGroup>, editMode: Boolean = false) = snapshot(name) {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 16.dp)) {
            ReactionPickerContent(
                reactionEmojis = quickReactions,
                customGroups = groupsParam,
                editMode = editMode,
                onSelect = { _, _ -> },
                onAddReactionEmoji = {},
                onRemoveReactionEmoji = {}
            )
        }
    }

    @Test
    fun reactionPickerNormal() = renderReactionPicker("EmojiPicker_reaction_normal", groups)

    @Test
    fun reactionPickerEditMode() = renderReactionPicker("EmojiPicker_reaction_edit", groups, editMode = true)

    @Test
    fun reactionPickerNoCustom() = renderReactionPicker("EmojiPicker_reaction_noCustom", emptyList())

    @Test
    fun composerEmojiSheet() = snapshot("EmojiPicker_composer") {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 16.dp)) {
            ComposerEmojiContent(
                groups = groups,
                onInsertUnicode = {},
                onInsertCustom = {}
            )
        }
    }
}
