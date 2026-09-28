package com.umbra.app.ui.settings

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
import com.umbra.app.R
import com.umbra.app.ui.theme.UmbraThemeOption

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class AppearanceSnapshotTest {
    @Test
    fun palettes() = snapshot("Appearance_palettes") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                UmbraThemeOption.DEFAULT to R.string.appearance_theme_default,
                UmbraThemeOption.EMBER to R.string.appearance_theme_ember,
                UmbraThemeOption.VERDANT to R.string.appearance_theme_verdant,
                UmbraThemeOption.SLATE to R.string.appearance_theme_slate
            ).forEach { (theme, name) ->
                AppearanceOptionRow(AppearanceOptionItem(theme, name, selected = theme == UmbraThemeOption.DEFAULT), onClick = {})
            }
        }
    }
}
