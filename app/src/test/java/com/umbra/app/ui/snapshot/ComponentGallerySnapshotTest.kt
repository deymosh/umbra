package com.umbra.app.ui.snapshot

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.ui.components.ContentWarningPlaceholder
import com.umbra.app.ui.components.InlineEmptyText
import com.umbra.app.ui.components.KeyValueCopyRow
import com.umbra.app.ui.components.LnurlPaymentCard
import com.umbra.app.ui.components.NetworkStatusPill
import com.umbra.app.ui.components.QuotedNoteCard
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = "w412dp-h1200dp-night-xxhdpi")
class ComponentGallerySnapshotTest {
    @Test
    fun gallery() = snapshot("Component_gallery") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkStatusPill(isTorConnected = true, isTorStarting = false, relayCount = 14, relaysConnected = true)
                NetworkStatusPill(isTorConnected = false, isTorStarting = true, relayCount = 14, relaysConnected = false)
                NetworkStatusPill(isTorConnected = false, isTorStarting = false, relayCount = 0, relaysConnected = false)
            }
            QuotedNoteCard(
                quotedEvent = SnapshotFixtures.shortNote,
                authorProfile = SnapshotFixtures.bob,
                onClick = {},
                torDataSourceFactory = SnapshotFixtures.tors,
                userRepository = SnapshotFixtures.userRepository
            )
            ContentWarningPlaceholder(reason = "Eclipse glare, bright flashes", onShowEvent = {})
            ContentWarningPlaceholder(reason = null, onShowEvent = {})
            LnurlPaymentCard(lnurl = "lnurl1dp68gurn8ghj7um9wfmxjcm99e3k7mf0v9cxj0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexejxxepnxscrvwfnv9nxzcn9xq6xyefhvgcxxcmyxymnserxfq5fns", onOpen = {})
            KeyValueCopyRow(label = "npub", value = "npub1a2b3c4d5e6f70718293a4b5c6d7e8f90a1b2c3d4e5f6", onCopy = {})
            InlineEmptyText("No inbox relays yet. Add one to receive replies and mentions.")
        }
    }
}
