package com.umbra.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun UmbraTheme(
    themeOption: UmbraThemeOption = UmbraThemeOption.DEFAULT,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = themeOption.toColorScheme(),
        content = content
    )
}
