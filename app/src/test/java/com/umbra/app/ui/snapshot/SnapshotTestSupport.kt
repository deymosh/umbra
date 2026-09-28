package com.umbra.app.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.umbra.app.ui.components.LocalImageLoadGate
import com.umbra.app.ui.theme.UmbraTheme
import com.umbra.app.ui.theme.UmbraThemeOption
import com.umbra.app.util.ImageLoadGate

/**
 * Robolectric qualifiers every snapshot test class uses: a Pixel-7-class phone in night mode.
 * Snapshot classes are annotated:
 *   RunWith(RobolectricTestRunner::class), GraphicsMode(NATIVE),
 *   Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
 * The plain Application (instead of the manifest's Hilt app) keeps app start-up (Tor, database,
 * relays) out of rendering entirely.
 */
internal const val PHONE = "w412dp-h915dp-night-xxhdpi"
internal const val SNAPSHOT_SDK = 36

/** Renders [content] inside the app theme and writes/compares `src/test/snapshots/<name>.png`. */
internal fun snapshot(
    name: String,
    themeOption: UmbraThemeOption = UmbraThemeOption.DEFAULT,
    content: @Composable () -> Unit
) {
    captureRoboImage(
        filePath = "src/test/snapshots/$name.png",
        roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)
        )
    ) {
        SnapshotHost(themeOption = themeOption, content = content)
    }
}

/**
 * Whole-screen capture for content that opens its own window — dialogs and bottom sheets, which
 * [snapshot]'s view capture can't see. Call from a test with a `createComposeRule()` rule.
 */
internal fun ComposeContentTestRule.snapshotScreen(
    name: String,
    content: @Composable () -> Unit
) {
    setContent { SnapshotHost(content = content) }
    waitForIdle()
    captureScreenRoboImage(
        filePath = "src/test/snapshots/$name.png",
        roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)
        )
    )
}

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
