package com.umbra.app.ui.networkusage

import android.app.Application
import com.umbra.app.domain.model.NetworkUsageSnapshot
import com.umbra.app.domain.model.RelayTraffic
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val MB = 1024L * 1024L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class NetworkUsageSnapshotTest {

    @Test
    fun usage() = snapshot("NetworkUsage") {
        NetworkUsageContent(
            usage = NetworkUsageSnapshot(
                sessionStartedAtMillis = System.currentTimeMillis() - 42 * 60_000,
                relays = listOf(
                    RelayTraffic("wss://relay.primal.net", 180_000, 14 * MB, 42, 9_812),
                    RelayTraffic("wss://relay.damus.io", 96_000, 6 * MB, 31, 4_120),
                    RelayTraffic("wss://nos.lol", 40_000, 900_000, 12, 610)
                ),
                mediaBytesReceived = 38 * MB,
                otherHttpBytesReceived = 2 * MB,
                httpBytesSent = 3 * MB,
                lifetimeBytes = 1_540 * MB
            ),
            onNavigateBack = {},
            onReset = {},
            activity = com.umbra.app.domain.usecase.OwnActivity(notes = 214, reactions = 1_380, reposts = 97)
        )
    }

    @Test
    fun `byte formatting is short and stable`() {
        assertEquals("512 B", formatBytesShort(512))
        assertEquals("1.50 KB", formatBytesShort(1_536))
        assertEquals("14.0 MB", formatBytesShort(14 * MB))
        assertEquals("1.50 GB", formatBytesShort(1_536 * MB))
    }
}
