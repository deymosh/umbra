package com.umbra.app.ui.feed

import android.app.Application
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.umbra.app.ui.snapshot.SnapshotFixtures

// The focal note shows an absolute date, so it gets a fixed timestamp (rendered in UTC).
private const val FOCAL_CREATED_AT = 1_750_000_000L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class ThreadSnapshotTest {
    @Test
    fun thread() = snapshot("Thread_focal") {
        Box(Modifier.fillMaxSize()) {
            Column {
                EventCard(event = SnapshotFixtures.shortNote, userProfile = SnapshotFixtures.bob, userRepository = SnapshotFixtures.userRepository, torDataSourceFactory = SnapshotFixtures.tors, animateAvatars = false, replyCount = 2)
                EventCard(event = SnapshotFixtures.textNote.copy(createdAt = FOCAL_CREATED_AT), userProfile = SnapshotFixtures.alice, userRepository = SnapshotFixtures.userRepository, torDataSourceFactory = SnapshotFixtures.tors, animateAvatars = false, highlighted = true, initiallyExpanded = true, replyCount = 12, reactionCount = 348, repostCount = 21, isLiked = true)
                EventCard(event = SnapshotFixtures.reply, userProfile = SnapshotFixtures.carol, replyToProfile = SnapshotFixtures.alice, threadDepth = 1, userRepository = SnapshotFixtures.userRepository, torDataSourceFactory = SnapshotFixtures.tors, animateAvatars = false)
            }
            Box(Modifier.align(Alignment.BottomCenter)) {
                ThreadReplyBar(authorName = "Alice Moreau", onClick = {})
            }
        }
    }
}
