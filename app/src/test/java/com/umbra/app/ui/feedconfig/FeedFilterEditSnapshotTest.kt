package com.umbra.app.ui.feedconfig

import android.app.Application
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
class FeedFilterEditSnapshotTest {

    @Test
    fun edit() = snapshot("FeedFilterEdit") {
        FeedFilterEditContent(
            isNew = false,
            draft = FeedFilterDraft(
                name = "Default",
                hideNsfw = true,
                scopeToFollows = false,
                tags = listOf("bitcoin-spam"),
                hashtags = listOf("airdrop", "giveaway", "pumpfun"),
                prefixes = emptyList()
            ).apply { prefixInput = "nlogpost:" },
            onCancel = {},
            onSave = {}
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
