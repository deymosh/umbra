package com.umbra.app.ui.devoptions

import android.app.Application
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.preferences.DeveloperFlag

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class DeveloperOptionsSnapshotTest {
    @Test
    fun toggles() = snapshot("DeveloperOptions_toggles") {
        Column(Modifier.padding(16.dp)) {
            DeveloperToggleRow(DeveloperToggleItem(DeveloperFlag.ENABLE_FEED_ERROR_BANNER, R.string.dev_options_enable_error_banner_title, R.string.dev_options_enable_error_banner_subtitle, true), {})
            DeveloperToggleRow(DeveloperToggleItem(DeveloperFlag.SHOW_ALL_RELAY_BANNERS, R.string.dev_options_verbose_relay_banners_title, R.string.dev_options_verbose_relay_banners_subtitle, false), {})
            DeveloperToggleRow(DeveloperToggleItem(DeveloperFlag.SHOW_RELAY_TELEMETRY, R.string.dev_options_show_relay_telemetry_title, R.string.dev_options_show_relay_telemetry_subtitle, false), {})
        }
    }
}
