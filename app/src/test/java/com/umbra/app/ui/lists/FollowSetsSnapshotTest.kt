package com.umbra.app.ui.lists

import android.app.Application
import com.umbra.app.domain.nip51.FollowSetMember
import com.umbra.app.ui.components.ListSetChip
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
class FollowSetsSnapshotTest {

    private val unknown = "9".repeat(64)

    @Test
    fun followSets() = snapshot("FollowSets") {
        FollowSetsContent(
            state = FollowSetsState(
                sets = listOf(
                    ListSetChip("photo", "Photographers"),
                    ListSetChip("relays", "Relay operators worth following for setup tips")
                ),
                selectedSet = "photo",
                members = listOf(
                    FollowSetMember(SnapshotFixtures.alice.pubkey, isPrivate = true),
                    FollowSetMember(SnapshotFixtures.bob.pubkey, isPrivate = false),
                    FollowSetMember(unknown, isPrivate = false)
                ),
                profiles = mapOf(
                    SnapshotFixtures.alice.pubkey to SnapshotFixtures.alice,
                    SnapshotFixtures.bob.pubkey to SnapshotFixtures.bob
                ),
                privateLocked = true
            ),
            canEdit = true,
            userRepository = SnapshotFixtures.userRepository,
            onNavigateBack = {}, onOpenProfile = {}, onSelectSet = {}, onCreateSet = {},
            onRenameSet = { _, _ -> }, onDeleteSet = {}, onRemoveMember = {}, onUnlockPrivate = {}
        )
    }

    @Test
    fun followSetsNone() = snapshot("FollowSets_none") {
        FollowSetsContent(
            state = FollowSetsState(),
            canEdit = true,
            userRepository = SnapshotFixtures.userRepository,
            onNavigateBack = {}, onOpenProfile = {}, onSelectSet = {}, onCreateSet = {},
            onRenameSet = { _, _ -> }, onDeleteSet = {}, onRemoveMember = {}, onUnlockPrivate = {}
        )
    }
}
