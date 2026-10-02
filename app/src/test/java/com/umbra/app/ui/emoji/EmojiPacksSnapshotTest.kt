package com.umbra.app.ui.emoji

import android.app.Application
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.EmojiPack
import com.umbra.app.domain.nip30.EmojiSetAddress
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
class EmojiPacksSnapshotTest {

    private fun pack(d: String, title: String, author: String, count: Int) = EmojiPack(
        address = EmojiSetAddress(author, d),
        title = title,
        description = null,
        image = null,
        emojis = (1..count).map { CustomEmoji("e$it", "https://img.example/$d/$it.png") },
        createdAt = SnapshotFixtures.now
    )

    private val frogs = pack("frogs", "Party frogs", SnapshotFixtures.ALICE, 24)
    private val eclipse = pack("eclipse", "Eclipse", SnapshotFixtures.CAROL, 9)
    private val cats = pack("cats", "Blob cats with a very long pack name that keeps going", SnapshotFixtures.BOB, 1)

    @Test
    fun emojiPacks() = snapshot("EmojiPacks") {
        EmojiPacksContent(
            state = EmojiPacksState(
                yourPacks = listOf(
                    OwnedEmojiPack(frogs.address, PackStorage.THIS_DEVICE, frogs),
                    OwnedEmojiPack(eclipse.address, PackStorage.PUBLIC_LIST, eclipse)
                ),
                discover = listOf(frogs, cats),
                authors = mapOf(
                    SnapshotFixtures.ALICE to SnapshotFixtures.alice,
                    SnapshotFixtures.BOB to SnapshotFixtures.bob
                ),
                canPublish = true
            ),
            onNavigateBack = {}, onQueryChange = {}, onAdd = { _, _ -> }, onRemove = {}, onMove = {}
        )
    }

    @Test
    fun emojiPacksEmpty() = snapshot("EmojiPacks_empty") {
        EmojiPacksContent(
            state = EmojiPacksState(canPublish = false),
            onNavigateBack = {}, onQueryChange = {}, onAdd = { _, _ -> }, onRemove = {}, onMove = {}
        )
    }
}
