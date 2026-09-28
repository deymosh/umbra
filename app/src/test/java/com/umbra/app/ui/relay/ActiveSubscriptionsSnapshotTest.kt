package com.umbra.app.ui.relay

import android.app.Application
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.SubscriptionType
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
class ActiveSubscriptionsSnapshotTest {

    private val now = 1_750_000_000_000L

    private fun req(relay: String, id: String, type: SubscriptionType, count: Int, filter: EventFilter) =
        RelayRequestInfo(
            relayUrl = relay,
            subscriptionId = id,
            filters = listOf(filter),
            receivedEventCount = count,
            lastEventAtMillis = SnapshotFixtures.now * 1000 - 42_000,
            sentAtMillis = now,
            updatedAtMillis = now,
            type = type
        )

    @Test
    fun subscriptions() = snapshot("ActiveSubscriptions") {
        ActiveSubscriptionsContent(
            requests = listOf(
                req("wss://relay.damus.io", "a1", SubscriptionType.OUTBOX_NOTES, 184, EventFilter(authors = setOf(SnapshotFixtures.BOB), kinds = setOf(1, 5))),
                req("wss://nos.lol", "b2", SubscriptionType.INBOX_NOTES, 57, EventFilter(kinds = setOf(1, 7))),
                req("wss://relay.primal.net", "c3", SubscriptionType.FEED_NOTES, 1320, EventFilter(authors = setOf(SnapshotFixtures.ALICE, SnapshotFixtures.CAROL), kinds = setOf(1, 6), limit = 200))
            ),
            currentUserPubkey = SnapshotFixtures.BOB,
            onNavigateBack = {}
        )
    }
}
