package com.umbra.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.umbra.app.ui.components.ErrorBanner
import com.umbra.app.ui.components.QuickActionBottomBar
import com.umbra.app.ui.snapshot.SnapshotFixtures
import com.umbra.app.ui.snapshot.SnapshotHost
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The feed's chrome (top bar, banner, notes, floating nav) assembled the way FeedScreen lays it
 * out. Lives in the feed package because FeedTopBar is internal to it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class FeedChromeSnapshotTest {
    @Test
    fun feedScreen() {
        snapshot("FeedChrome_feedScreen") {
            SnapshotHost {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        FeedTopBar(
                            currentProfile = SnapshotFixtures.bob,
                            currentPubkey = SnapshotFixtures.BOB,
                            searchVisible = false,
                            relayCount = 14,
                            isConnected = true,
                            isTorConnected = true,
                            isTorStarting = false,
                            onAvatarClick = {},
                            onToggleSearch = {}
                        )
                        ErrorBanner(message = "relay.damus.io stopped responding. Tap for details.", onDismiss = {}, onClick = {})
                        SnapshotFixtures.feed.forEach { (event, profile) ->
                            EventCard(
                                event = event,
                                userProfile = profile,
                                replyToProfile = if (event == SnapshotFixtures.reply) SnapshotFixtures.alice else null,
                                userRepository = SnapshotFixtures.userRepository,
                                torDataSourceFactory = SnapshotFixtures.tors,
                                replyCount = 12,
                                reactionCount = if (event == SnapshotFixtures.textNote) 1_348 else 7,
                                repostCount = 21,
                                isLiked = event == SnapshotFixtures.textNote,
                                isReposted = event == SnapshotFixtures.shortNote,
                                currentUserPubkey = SnapshotFixtures.BOB,
                                animateAvatars = false
                            )
                        }
                    }
                    QuickActionBottomBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onGoTop = {}, onCompose = {}, onRelays = {}, onSettings = {}, onFilters = {}
                    )
                }
            }
        }
    }

    @Test
    fun topBarStates() {
        snapshot("FeedChrome_topBarStates") {
            SnapshotHost {
                Column(Modifier.fillMaxWidth()) {
                    FeedTopBar(SnapshotFixtures.bob, SnapshotFixtures.BOB, false, 14, true, true, false, {}, {})
                    FeedTopBar(SnapshotFixtures.bob, SnapshotFixtures.BOB, false, 14, false, false, true, {}, {})
                    FeedTopBar(null, null, true, 0, false, false, false, {}, {})
                    FeedSearchBar(query = "", onQueryChange = {}, onClear = {}, autoFocus = false)
                }
            }
        }
    }
}
