package com.umbra.app.ui.profile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.umbra.app.ui.feed.EventCard
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.SnapshotFixtures
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The profile header, pinned tab bar and first notes, assembled as ProfileScreen lays them out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class ProfileSnapshotTest {

    private fun render(name: String, isOwn: Boolean, following: Boolean) = snapshot(name) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ProfileHero(
                    profile = SnapshotFixtures.alice,
                    pubkey = SnapshotFixtures.ALICE,
                    canSign = true,
                    isOwnProfile = isOwn,
                    isFollowing = following,
                    isFollowActionInFlight = false,
                    followersCount = 1_204,
                    followingCount = 128,
                    onFollowingClick = {},
                    onToggleFollow = {},
                    onEditProfile = {},
                    onMuteUser = {},
                    npub = "npub1a2b3c4d5e6f70718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9",
                    onCopyHex = {},
                    onCopyNpub = {},
                    onWebsiteClick = {},
                    onBioUrlClick = {},
                    userRepository = SnapshotFixtures.userRepository
                )
                val tabs = ProfileTab.entries.filter { isOwn || (it != ProfileTab.MUTES && it != ProfileTab.PINNED) }
                ProfileTabBar(
                    tabs = tabs,
                    selectedIndex = 0,
                    countFor = { if (it == ProfileTab.NOTES) 342 else if (it == ProfileTab.FOLLOWS) 128 else 12 },
                    onSelect = {}
                )
                EventCard(
                    event = SnapshotFixtures.textNote,
                    userProfile = SnapshotFixtures.alice,
                    userRepository = SnapshotFixtures.userRepository,
                    torDataSourceFactory = SnapshotFixtures.tors,
                    replyCount = 4,
                    reactionCount = 58,
                    repostCount = 9,
                    animateAvatars = false
                )
            }
            ProfileTopOverlay(title = "Alice Moreau", collapseFraction = 0f, onBack = {})
        }
    }

    @Test
    fun otherPerson() = render("Profile_other", isOwn = false, following = false)

    @Test
    fun ownProfile() = render("Profile_own", isOwn = true, following = false)
}
