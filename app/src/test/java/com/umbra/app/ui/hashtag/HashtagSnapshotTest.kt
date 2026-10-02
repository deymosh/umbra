package com.umbra.app.ui.hashtag

import android.app.Application
import androidx.compose.foundation.layout.Column
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
class HashtagSnapshotTest {

    @Test
    fun followButton() = snapshot("Hashtag_follow") {
        Column {
            HashtagTopBar(tag = "nostr", isFollowed = false, canFollow = true, onNavigateBack = {}, onToggleFollow = {})
            HashtagTopBar(tag = "photography", isFollowed = true, canFollow = true, onNavigateBack = {}, onToggleFollow = {})
            HashtagTopBar(
                tag = "averyveryverylonghashtagthatkeepsgoingpastthewidth",
                isFollowed = false,
                canFollow = true,
                onNavigateBack = {},
                onToggleFollow = {}
            )
            HashtagTopBar(tag = "readonly", isFollowed = false, canFollow = false, onNavigateBack = {}, onToggleFollow = {})
        }
    }
}
