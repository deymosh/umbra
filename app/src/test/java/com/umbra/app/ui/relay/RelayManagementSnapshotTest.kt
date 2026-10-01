package com.umbra.app.ui.relay

import android.app.Application
import com.umbra.app.domain.nip86.RelayManagementEntry
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class RelayManagementSnapshotTest {

    @Test
    fun peopleTabLoaded() = snapshot("RelayManagementPeople") {
        RelayManagementContent(
            state = RelayManagementState(
                relayUrl = "wss://relay.example.com",
                supportedTabs = listOf(
                    RelayManagementTab.PEOPLE,
                    RelayManagementTab.KINDS,
                    RelayManagementTab.IPS
                ),
                lists = RelayManagementLists(
                    bannedPubkeys = listOf(
                        RelayManagementEntry("3bf0c63fcb93463407af97a5e12ee78c", "spam"),
                        RelayManagementEntry("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "harassment")
                    ),
                    allowedPubkeys = listOf(
                        RelayManagementEntry("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                    ),
                    allowedKinds = listOf(1, 10002),
                    disallowedKinds = listOf(4),
                    blockedIps = listOf(RelayManagementEntry("192.0.2.7", "abuse"))
                ),
            ),
            onNavigateBack = {}, onAddPubkey = { _, _, _ -> }, onRemovePubkey = { _, _ -> },
            onAllowEvent = {}, onBanEvent = {}, onUnbanEvent = {}, onUnallowEvent = {},
            onAddKind = { _, _ -> }, onRemoveKind = { _, _ -> },
            onBlockIp = { _, _ -> }, onUnblockIp = {},
            onChangeName = {}, onChangeDescription = {}, onChangeIcon = {},
            onClearError = {}, onRetry = {}
        )
    }

    @Test
    fun notAuthorized() = snapshot("RelayManagementNotAuthorized") {
        RelayManagementContent(
            state = RelayManagementState(
                relayUrl = "wss://relay.example.com",
                supportedTabs = emptyList(),
                notAuthorized = true
            ),
            onNavigateBack = {}, onAddPubkey = { _, _, _ -> }, onRemovePubkey = { _, _ -> },
            onAllowEvent = {}, onBanEvent = {}, onUnbanEvent = {}, onUnallowEvent = {},
            onAddKind = { _, _ -> }, onRemoveKind = { _, _ -> },
            onBlockIp = { _, _ -> }, onUnblockIp = {},
            onChangeName = {}, onChangeDescription = {}, onChangeIcon = {},
            onClearError = {}, onRetry = {}
        )
    }
}
