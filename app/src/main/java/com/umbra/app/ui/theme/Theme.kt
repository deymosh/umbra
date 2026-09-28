package com.umbra.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val UmbraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Semantic colors Material's scheme has no slot for. Each one encodes meaning — never use them
 * decoratively:
 * - [secure]: Tor connected / relay healthy / NIP-05 verified.
 * - [caution]: in progress, degraded, retrying.
 * - [like] / [repost] / [zap]: engagement state the user has taken.
 * - [corona]: the brand glow (the single bold element — TorGate/Login hero, compose button).
 */
@Immutable
data class UmbraExtendedColors(
    val secure: Color,
    val caution: Color,
    val like: Color,
    val repost: Color,
    val zap: Color,
    val corona: Color
)

private val DefaultExtended = UmbraExtendedColors(
    secure = Color(0xFF7ADFCB),
    caution = Color(0xFFF2C987),
    like = Color(0xFFFF7A9C),
    repost = Color(0xFF7ADFCB),
    zap = Color(0xFFF2C987),
    corona = Color(0xFFCBBEFF)
)

val LocalUmbraColors = staticCompositionLocalOf { DefaultExtended }

object UmbraTheme {
    val colors: UmbraExtendedColors
        @Composable @ReadOnlyComposable get() = LocalUmbraColors.current
}

@Composable
fun UmbraTheme(
    themeOption: UmbraThemeOption = UmbraThemeOption.DEFAULT,
    content: @Composable () -> Unit
) {
    val scheme = themeOption.toColorScheme()
    // The corona follows each palette's primary so the brand glow re-tints with the theme.
    val extended = DefaultExtended.copy(corona = scheme.primary)
    CompositionLocalProvider(LocalUmbraColors provides extended) {
        MaterialTheme(
            colorScheme = scheme,
            typography = UmbraTypography,
            shapes = UmbraShapes,
            content = content
        )
    }
}
