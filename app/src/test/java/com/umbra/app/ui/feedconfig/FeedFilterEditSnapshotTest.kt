package com.umbra.app.ui.feedconfig

import android.app.Application
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import com.umbra.app.ui.components.ListSetChip
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class FeedFilterEditSnapshotTest {

    @Test
    fun edit() = snapshot("FeedFilterEdit") {
        FeedFilterEditContent(
            isNew = false,
            draft = FeedFilterDraft(
                name = "Default",
                hideNsfw = true,
                scopeToFollows = true,
                tags = listOf("bitcoin-spam"),
                hashtags = listOf("airdrop", "giveaway", "pumpfun"),
                prefixes = emptyList(),
                followSets = listOf("30000:${"a".repeat(64)}:photo", "30000:${"a".repeat(64)}:gone")
            ).apply { prefixInput = "nlogpost:" },
            onCancel = {},
            onSave = {},
            followSets = listOf(
                ListSetChip("30000:${"a".repeat(64)}:photo", "Photographers"),
                ListSetChip("30000:${"a".repeat(64)}:relays", "Relay operators")
            )
        )
    }

    @Test
    fun new() = snapshot("FeedFilterEdit_new") {
        FeedFilterEditContent(
            isNew = true,
            draft = FeedFilterDraft.from(null),
            onCancel = {},
            onSave = {}
        )
    }
}
