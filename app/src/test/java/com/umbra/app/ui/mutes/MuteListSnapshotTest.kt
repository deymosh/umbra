package com.umbra.app.ui.mutes

import android.app.Application
import com.umbra.app.domain.nip51.MuteItem
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
class MuteListSnapshotTest {

    @Test
    fun muteList() = snapshot("MuteList") {
        MuteListContent(
            state = MuteListState(
                items = listOf(
                    MuteItem(MuteItem.Kind.PERSON, SnapshotFixtures.alice.pubkey, isPrivate = true),
                    MuteItem(MuteItem.Kind.PERSON, SnapshotFixtures.bob.pubkey, isPrivate = false),
                    MuteItem(MuteItem.Kind.HASHTAG, "airdrop", isPrivate = false),
                    MuteItem(MuteItem.Kind.HASHTAG, "drama", isPrivate = true),
                    MuteItem(MuteItem.Kind.WORD, "giveaway", isPrivate = false),
                    MuteItem(MuteItem.Kind.THREAD, "c".repeat(64), isPrivate = false)
                ),
                profiles = mapOf(
                    SnapshotFixtures.alice.pubkey to SnapshotFixtures.alice,
                    SnapshotFixtures.bob.pubkey to SnapshotFixtures.bob
                ),
                privateLocked = true,
                canEdit = true
            ),
            userRepository = SnapshotFixtures.userRepository,
            onNavigateBack = {}, onOpenProfile = {}, onOpenThread = {}, onMuteHashtag = {}, onMuteWord = {},
            onUnmute = {}, onNewMutesPrivateChange = {}, onUnlockPrivate = {}
        )
    }
}
