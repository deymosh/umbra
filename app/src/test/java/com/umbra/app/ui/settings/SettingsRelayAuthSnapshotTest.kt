package com.umbra.app.ui.settings

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.umbra.app.domain.relay.RelayAuthMode
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import com.umbra.app.ui.snapshot.snapshotScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshots for the relay-AUTH settings row (inside SettingsContent, with the mode's chosen
 * option reflected in the subtitle) and the selection dialog itself. See docs/UI_SNAPSHOTS.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class SettingsRelayAuthSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsWithRelayAuthRow() = snapshot("Settings_relay_auth_row") {
        SettingsContent(
            onBack = {},
            onOpen = {},
            onLogout = {},
            versionName = "0.2.0",
            relayAuthMode = RelayAuthMode.THROWAWAY_KEY
        )
    }

    @Test
    fun relayAuthModeDialog() = composeRule.snapshotScreen("Settings_relay_auth_dialog") {
        RelayAuthModePickerDialog(
            current = RelayAuthMode.THROWAWAY_KEY,
            onSelect = {},
            onDismiss = {}
        )
    }
}
