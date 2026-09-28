package com.umbra.app.ui.snapshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.umbra.app.ui.feed.EventCard
import com.umbra.app.ui.components.QuickActionBottomBar
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.ErrorBanner
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class ComponentSnapshotTest {
    @Composable
    private fun Card(event: com.umbra.app.domain.nip01.Event, profile: com.umbra.app.domain.profile.UserProfile, liked: Boolean = false, replyToProfile: com.umbra.app.domain.profile.UserProfile? = null) {
        EventCard(
            event = event,
            userProfile = profile,
            replyToProfile = replyToProfile,
            userRepository = SnapshotFixtures.userRepository,
            torDataSourceFactory = SnapshotFixtures.tors,
            replyCount = 12,
            reactionCount = if (liked) 348 else 7,
            repostCount = 21,
            isLiked = liked,
            currentUserPubkey = SnapshotFixtures.BOB,
            animateAvatars = false
        )
    }

    @Test
    fun feedCards() {
        snapshot("Component_feedCards") {
            SnapshotHost {
                Column(Modifier.fillMaxWidth()) {
                    Card(SnapshotFixtures.textNote, SnapshotFixtures.alice, liked = true)
                    Card(SnapshotFixtures.shortNote, SnapshotFixtures.bob)
                    Card(SnapshotFixtures.reply, SnapshotFixtures.carol, replyToProfile = SnapshotFixtures.alice)
                    ErrorBanner(message = "wss://relay… is not responding", onDismiss = {})
                    QuickActionBottomBar(onGoTop = {}, onCompose = {}, onRelays = {}, onSettings = {})
                }
            }
        }
    }

    @Test
    fun emptyState() {
        snapshot("Component_emptyState") {
            SnapshotHost {
                EmptyState(title = "No notes yet", message = "Connecting to relays over Tor…")
            }
        }
    }
}
