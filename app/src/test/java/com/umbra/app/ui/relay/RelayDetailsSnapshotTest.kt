package com.umbra.app.ui.relay

import android.app.Application
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
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
class RelayDetailsSnapshotTest {

    private val nowMs = SnapshotFixtures.now * 1000

    private val relay = Relay(
        id = "r1",
        url = "wss://relay.damus.io",
        isDmEnabled = false,
        relayInfo = RelayInfo(
            name = "damus",
            description = "Damus strfry relay. Fast, public, rate-limited per IP.",
            contact = "admin@damus.io",
            pubkey = SnapshotFixtures.BOB,
            software = "git+https://github.com/hoytech/strfry.git",
            version = "1.0.4",
            supportedNips = listOf(1, 2, 4, 9, 11, 22, 28, 40, 42, 45, 70, 77),
            maxSubscriptions = 300,
            maxLimitEventCount = 500,
            requiresAuth = false,
            minPoW = 0
        )
    )

    @Test
    fun details() = snapshot("RelayDetails") {
        RelayDetailsContent(
            relay = relay,
            relaysLoaded = true,
            connectionState = RelayConnectionIndicatorState.CONNECTED,
            isInfoLoading = false,
            refreshResult = null,
            requests = listOf(
                RelayRequestInfo(
                    relayUrl = relay.url,
                    subscriptionId = "a1",
                    filters = listOf(EventFilter(kinds = setOf(1, 6), limit = 200)),
                    receivedEventCount = 812,
                    sentAtMillis = nowMs,
                    updatedAtMillis = nowMs,
                    lastEventAtMillis = nowMs - 12_000,
                    type = SubscriptionType.FEED_NOTES
                )
            ),
            issues = listOf(
                RelayIssue(relayUrl = relay.url, kind = RelayIssueKind.CONNECTED, rawMessage = "connected over Tor", timestampMs = 1_750_000_000_000L),
                RelayIssue(relayUrl = relay.url, kind = RelayIssueKind.RATE_LIMIT, rawMessage = "rate-limited: slow down", timestampMs = 1_750_000_030_000L)
            ),
            currentUserPubkey = SnapshotFixtures.BOB,
            onNavigateBack = {}, onRefreshInfo = {}, onEdit = {}, onDelete = {}, onOpenUrl = {}
        )
    }
}
