package com.umbra.app.ui.relay

import android.app.Application
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.domain.nip77.SyncDirection
import com.umbra.app.domain.relay.Relay
import com.umbra.app.ui.components.SectionHeader

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class RelaysSnapshotTest {
    private fun relay(id: String, url: String, enabled: Boolean = true, onion: Boolean = false) =
        Relay(id = id, url = url, isEnabled = enabled, isOnion = onion)

    @Test
    fun relayList() = snapshot("Relays_list") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NegentropySyncCard(direction = SyncDirection.BOTH, onDirectionChange = {})
            RelayTelemetryCard(
                telemetry = RelayTelemetrySnapshot(configured = 14, active = 12, connectedNow = 11, liveSubscriptions = 23, totalReceivedEvents = 4812, nonConnectedIssues = 2),
                onSubscriptionsClick = {}
            )
            SectionHeader(title = "Outbox", actionLabel = "Add relay", onAction = {})
            RelayCard(relay("1", "wss://relay.damus.io"), null, RelayConnectionIndicatorState.CONNECTED, true, true, {}, {}, {})
            RelayCard(relay("2", "wss://nos.lol"), null, RelayConnectionIndicatorState.CONNECTING, true, true, {}, {}, {})
            RelayCard(relay("3", "ws://oxtrdevav64z64yb7x6rjg4ntzqjhedm5b5zjqulugknhzr46ny2qbad.onion", onion = true), null, RelayConnectionIndicatorState.FAILED, true, true, {}, {}, {})
            RelayCard(relay("4", "wss://relay.nostr.band", enabled = false), null, RelayConnectionIndicatorState.DISABLED, false, true, {}, null, {})
        }
    }
}
