package com.umbra.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.umbra.app.domain.preferences.ThemePreference

/**
 * Curated color palettes selectable in Settings > Appearance. Entry order is the picker's
 * display order. [DEFAULT] is exactly today's single hardcoded dark palette, so an existing
 * install sees no visual change until it explicitly picks a different one.
 */
enum class UmbraThemeOption {
    DEFAULT,
    EMBER,
    VERDANT,
    SLATE
}

private val DefaultColors = darkColorScheme(
    primary = Color(0xFFA990FF),
    onPrimary = Color(0xFF151024),
    primaryContainer = Color(0xFF34284F),
    onPrimaryContainer = Color(0xFFF0E8FF),
    secondary = Color(0xFF6FD6C8),
    onSecondary = Color(0xFF09211D),
    secondaryContainer = Color(0xFF173C36),
    onSecondaryContainer = Color(0xFFC7F4EC),
    tertiary = Color(0xFF9DB8FF),
    onTertiary = Color(0xFF101D3E),
    tertiaryContainer = Color(0xFF243760),
    onTertiaryContainer = Color(0xFFE0E8FF),
    background = Color(0xFF0A0A0F),
    onBackground = Color(0xFFF2F0F8),
    surface = Color(0xFF14131A),
    onSurface = Color(0xFFF2F0F8),
    surfaceVariant = Color(0xFF201D27),
    onSurfaceVariant = Color(0xFFD0CADB),
    error = Color(0xFFFF8D94),
    onError = Color(0xFF38000D),
    errorContainer = Color(0xFF5F1C2B),
    onErrorContainer = Color(0xFFFFD9DF),
    outline = Color(0xFF454050),
    outlineVariant = Color(0xFF2A2733)
)

private val EmberColors = darkColorScheme(
    primary = Color(0xFFE0A96B),
    onPrimary = Color(0xFF2B1B04),
    primaryContainer = Color(0xFF4A3418),
    onPrimaryContainer = Color(0xFFFFDEB0),
    secondary = Color(0xFFE0937A),
    onSecondary = Color(0xFF2E1208),
    secondaryContainer = Color(0xFF4A2718),
    onSecondaryContainer = Color(0xFFFFD9C8),
    tertiary = Color(0xFFD9C08A),
    onTertiary = Color(0xFF291F04),
    tertiaryContainer = Color(0xFF473616),
    onTertiaryContainer = Color(0xFFF2E4B8),
    background = Color(0xFF120D0A),
    onBackground = Color(0xFFF5EEE6),
    surface = Color(0xFF1B140F),
    onSurface = Color(0xFFF5EEE6),
    surfaceVariant = Color(0xFF2A2016),
    onSurfaceVariant = Color(0xFFDDD0BF),
    error = Color(0xFFFF8D94),
    onError = Color(0xFF38000D),
    errorContainer = Color(0xFF5F1C2B),
    onErrorContainer = Color(0xFFFFD9DF),
    outline = Color(0xFF50432F),
    outlineVariant = Color(0xFF332A1D)
)

private val VerdantColors = darkColorScheme(
    primary = Color(0xFF8FCB8A),
    onPrimary = Color(0xFF0B2309),
    primaryContainer = Color(0xFF203D1D),
    onPrimaryContainer = Color(0xFFD3F0CE),
    secondary = Color(0xFF7FCFB8),
    onSecondary = Color(0xFF07271F),
    secondaryContainer = Color(0xFF1B3F35),
    onSecondaryContainer = Color(0xFFC5F0E2),
    tertiary = Color(0xFFB9CE7C),
    onTertiary = Color(0xFF212C04),
    tertiaryContainer = Color(0xFF394616),
    onTertiaryContainer = Color(0xFFE6F2C4),
    background = Color(0xFF0A0F0B),
    onBackground = Color(0xFFEEF3EC),
    surface = Color(0xFF131A14),
    onSurface = Color(0xFFEEF3EC),
    surfaceVariant = Color(0xFF1E2820),
    onSurfaceVariant = Color(0xFFCBD8CB),
    error = Color(0xFFFF8D94),
    onError = Color(0xFF38000D),
    errorContainer = Color(0xFF5F1C2B),
    onErrorContainer = Color(0xFFFFD9DF),
    outline = Color(0xFF3F4C3F),
    outlineVariant = Color(0xFF273229)
)

private val SlateColors = darkColorScheme(
    primary = Color(0xFF9FC2E8),
    onPrimary = Color(0xFF0B2438),
    primaryContainer = Color(0xFF1F3B52),
    onPrimaryContainer = Color(0xFFD5E7FA),
    secondary = Color(0xFFB6C2D6),
    onSecondary = Color(0xFF202934),
    secondaryContainer = Color(0xFF37414F),
    onSecondaryContainer = Color(0xFFE6EDF7),
    tertiary = Color(0xFFA6B8E0),
    onTertiary = Color(0xFF141F3E),
    tertiaryContainer = Color(0xFF2A3557),
    onTertiaryContainer = Color(0xFFE1E7FA),
    background = Color(0xFF0A0C0F),
    onBackground = Color(0xFFEFF1F5),
    surface = Color(0xFF13161B),
    onSurface = Color(0xFFEFF1F5),
    surfaceVariant = Color(0xFF1F2530),
    onSurfaceVariant = Color(0xFFCAD1DD),
    error = Color(0xFFFF8D94),
    onError = Color(0xFF38000D),
    errorContainer = Color(0xFF5F1C2B),
    onErrorContainer = Color(0xFFFFD9DF),
    outline = Color(0xFF414A57),
    outlineVariant = Color(0xFF272E38)
)

fun UmbraThemeOption.toColorScheme(): ColorScheme = when (this) {
    UmbraThemeOption.DEFAULT -> DefaultColors
    UmbraThemeOption.EMBER -> EmberColors
    UmbraThemeOption.VERDANT -> VerdantColors
    UmbraThemeOption.SLATE -> SlateColors
}

fun UmbraThemeOption.toThemePreference(): ThemePreference = when (this) {
    UmbraThemeOption.DEFAULT -> ThemePreference.DEFAULT
    UmbraThemeOption.EMBER -> ThemePreference.EMBER
    UmbraThemeOption.VERDANT -> ThemePreference.VERDANT
    UmbraThemeOption.SLATE -> ThemePreference.SLATE
}

fun ThemePreference.toUmbraThemeOption(): UmbraThemeOption = when (this) {
    ThemePreference.DEFAULT -> UmbraThemeOption.DEFAULT
    ThemePreference.EMBER -> UmbraThemeOption.EMBER
    ThemePreference.VERDANT -> UmbraThemeOption.VERDANT
    ThemePreference.SLATE -> UmbraThemeOption.SLATE
}
