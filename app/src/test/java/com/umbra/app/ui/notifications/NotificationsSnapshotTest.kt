package com.umbra.app.ui.notifications

import android.app.Application
import com.umbra.app.domain.notifications.NotificationGroup
import com.umbra.app.domain.notifications.NotificationType
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
class NotificationsSnapshotTest {
    private val now = SnapshotFixtures.now
    private val myNote = SnapshotFixtures.note("mine", SnapshotFixtures.BOB, "Watching the partial phase from the rooftop. Clear skies all afternoon.", 3 * 3600)

    @Test
    fun notifications() = snapshot("Notifications") {
        NotificationsContent(
            state = NotificationsState(
                groups = listOf(
                    NotificationGroup("ZAP:x", NotificationType.ZAP, listOf(SnapshotFixtures.ALICE), now - 120, myNote.id, zapTotalSats = 5_000, zapComment = "for the rooftop shot"),
                    NotificationGroup("REPLY:y", NotificationType.REPLY, listOf(SnapshotFixtures.CAROL), now - 900, myNote.id, event = SnapshotFixtures.reply),
                    NotificationGroup("REACTION:z", NotificationType.REACTION, listOf(SnapshotFixtures.ALICE, SnapshotFixtures.CAROL, SnapshotFixtures.BOB), now - 3_600, myNote.id, reactions = listOf("🔥", "+")),
                    NotificationGroup("REPOST:w", NotificationType.REPOST, listOf(SnapshotFixtures.CAROL), now - 7_200, myNote.id)
                ),
                profiles = mapOf(
                    SnapshotFixtures.ALICE to SnapshotFixtures.alice,
                    SnapshotFixtures.BOB to SnapshotFixtures.bob,
                    SnapshotFixtures.CAROL to SnapshotFixtures.carol
                ),
                targets = mapOf(myNote.id to myNote),
                seenAt = now - 1_000,
                isLoading = false
            ),
            onNavigateBack = {}, onFilter = {}, onOpenThread = {}, onOpenProfile = {}
        )
    }
}
