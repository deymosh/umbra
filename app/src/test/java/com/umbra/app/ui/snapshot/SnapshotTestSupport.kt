package com.umbra.app.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.umbra.app.ui.components.LocalImageLoadGate
import com.umbra.app.ui.theme.UmbraTheme
import com.umbra.app.ui.theme.UmbraThemeOption
import com.umbra.app.util.ImageLoadGate

internal fun umbraPaparazzi(
    deviceConfig: DeviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT, softButtons = false)
) = Paparazzi(
    deviceConfig = deviceConfig,
    // Screens are dark-only; render at 1:1 of the device's own density for crisp review images.
    maxPercentDifference = 0.1
)

/** Wraps [content] in the app theme plus the CompositionLocals real screens get from MainActivity. */
@Composable
internal fun SnapshotHost(
    themeOption: UmbraThemeOption = UmbraThemeOption.DEFAULT,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalImageLoadGate provides ImageLoadGate()) {
        UmbraTheme(themeOption = themeOption) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                content()
            }
        }
    }
}
