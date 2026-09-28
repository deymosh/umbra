package com.umbra.app.ui.snapshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import com.android.resources.NightMode
import com.umbra.app.ui.feed.EventCard
import com.umbra.app.ui.components.QuickActionBottomBar
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.ErrorBanner
import org.junit.Rule
import org.junit.Test

class ComponentSnapshotTest {
    @get:Rule
    val paparazzi = umbraPaparazzi(
        DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT, screenHeight = 2400, softButtons = false)
    )

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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
            SnapshotHost {
                EmptyState(title = "No notes yet", message = "Connecting to relays over Tor…")
            }
        }
    }
}
