package com.umbra.app.ui.feed

import android.app.Application
import com.umbra.app.domain.nip01.Event
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
class PicturePostSnapshotTest {

    private val picture = Event(
        id = "p1".padEnd(64, '0'),
        pubkey = SnapshotFixtures.ALICE,
        createdAt = SnapshotFixtures.now - 3 * 3600,
        kind = Event.KIND_PICTURE,
        tags = listOf(
            listOf("title", "Diamond ring, second contact"),
            listOf("imeta", "url https://image.example/corona.jpg", "m image/jpeg", "dim 1600x1067", "blurhash L03[%;j[00ayofj[ayj[00ay~qj["),
            listOf("t", "eclipse")
        ),
        content = "The last bead of sunlight before totality. Shot at 1/4000s. #eclipse"
    )

    @Test
    fun picturePost() = snapshot("PicturePost") {
        EventCard(
            event = picture,
            userProfile = SnapshotFixtures.alice,
            userRepository = SnapshotFixtures.userRepository,
            torDataSourceFactory = SnapshotFixtures.tors,
            animateAvatars = false,
            replyCount = 4,
            reactionCount = 212
        )
    }
}
