package com.umbra.app.ui.snapshot

import android.app.Application
import com.umbra.app.ui.settings.SettingsContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Taller than PHONE so the whole scrolling list fits in one image.
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = "w412dp-h1500dp-night-xxhdpi")
class SettingsSnapshotTest {
    @Test
    fun settings() = snapshot("Settings_main") {
        SettingsContent(onBack = {}, onOpen = {}, onLogout = {}, versionName = "0.2.0")
    }
}
