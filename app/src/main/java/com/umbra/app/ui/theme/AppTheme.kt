package com.umbra.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.umbra.app.domain.preferences.ThemePreference

/**
 * Curated color palettes selectable in Settings > Appearance. Entry order is the picker's
 * display order. [DEFAULT] is "Totality" — Umbra's own identity (void black, lavender corona,
 * gold diamond-ring flare); the others re-tint the same structure.
 */
enum class UmbraThemeOption {
    DEFAULT,
    EMBER,
    VERDANT,
    SLATE
}

/**
 * The handful of hues a palette actually chooses; everything else in the Material scheme is
 * derived from these so every palette gets a complete, consistent surface ramp (Material's own
 * darkColorScheme defaults for surfaceContainer* are baseline purple-grey and would leak through
 * any tone not set explicitly).
 */
private data class PaletteSpec(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    // Surface ramp, darkest → lightest: void (background), then five container steps.
    val void: Color,
    val lowest: Color,
    val low: Color,
    val container: Color,
    val high: Color,
    val highest: Color,
    val text: Color,
    val textMuted: Color,
    val outline: Color,
    val hairline: Color
)

private fun PaletteSpec.toScheme(): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = primaryContainer,
    secondary = secondary,
    onSecondary = void,
    // Material uses secondaryContainer for generic tonal fills (tonal buttons, selected
    // segments/chips). Teal is reserved for "secure" semantics here, so those stay neutral.
    secondaryContainer = highest,
    onSecondaryContainer = text,
    tertiary = tertiary,
    onTertiary = void,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = void,
    onBackground = text,
    surface = void,
    onSurface = text,
    surfaceVariant = container,
    onSurfaceVariant = textMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = text,
    inverseOnSurface = void,
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF2B0508),
    errorContainer = Color(0xFF3A1518),
    onErrorContainer = Color(0xFFFFD6D6),
    outline = outline,
    outlineVariant = hairline,
    scrim = Color(0xE6000000),
    surfaceBright = highest,
    surfaceDim = void,
    surfaceContainerLowest = lowest,
    surfaceContainerLow = low,
    surfaceContainer = container,
    surfaceContainerHigh = high,
    surfaceContainerHighest = highest
)

private val Totality = PaletteSpec(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF17112E),
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondary = Color(0xFF7ADFCB),
    tertiary = Color(0xFFF2C987),
    tertiaryContainer = Color(0xFF362A14),
    onTertiaryContainer = Color(0xFFFBE6C2),
    void = Color(0xFF07070A),
    lowest = Color(0xFF0B0B0F),
    low = Color(0xFF101015),
    container = Color(0xFF15151C),
    high = Color(0xFF1B1B24),
    highest = Color(0xFF23232E),
    text = Color(0xFFEDEBF3),
    textMuted = Color(0xFF9C99A9),
    outline = Color(0xFF3B3946),
    hairline = Color(0xFF1F1E27)
)

private val Ember = PaletteSpec(
    primary = Color(0xFFF0B777),
    onPrimary = Color(0xFF2B1A05),
    primaryContainer = Color(0xFF45301A),
    onPrimaryContainer = Color(0xFFFFE2BF),
    secondary = Color(0xFF8FD6BF),
    tertiary = Color(0xFFF2C987),
    tertiaryContainer = Color(0xFF3A2C12),
    onTertiaryContainer = Color(0xFFFBE6C2),
    void = Color(0xFF0B0806),
    lowest = Color(0xFF0F0B08),
    low = Color(0xFF15100C),
    container = Color(0xFF1B1510),
    high = Color(0xFF231B15),
    highest = Color(0xFF2C231B),
    text = Color(0xFFF4EDE5),
    textMuted = Color(0xFFA89C8F),
    outline = Color(0xFF4A3E32),
    hairline = Color(0xFF261F18)
)

private val Verdant = PaletteSpec(
    primary = Color(0xFF9FDB9A),
    onPrimary = Color(0xFF0B2309),
    primaryContainer = Color(0xFF1F3B1D),
    onPrimaryContainer = Color(0xFFD3F0CE),
    secondary = Color(0xFF7ADFCB),
    tertiary = Color(0xFFE6D08A),
    tertiaryContainer = Color(0xFF34301A),
    onTertiaryContainer = Color(0xFFF6EBC4),
    void = Color(0xFF060907),
    lowest = Color(0xFF090D0A),
    low = Color(0xFF0E130F),
    container = Color(0xFF131A14),
    high = Color(0xFF19211A),
    highest = Color(0xFF212A22),
    text = Color(0xFFEBF2EA),
    textMuted = Color(0xFF93A393),
    outline = Color(0xFF394739),
    hairline = Color(0xFF1B231C)
)

private val Slate = PaletteSpec(
    primary = Color(0xFFA9CBF2),
    onPrimary = Color(0xFF0B2438),
    primaryContainer = Color(0xFF1E3850),
    onPrimaryContainer = Color(0xFFD5E7FA),
    secondary = Color(0xFF7ADFCB),
    tertiary = Color(0xFFF2C987),
    tertiaryContainer = Color(0xFF362A14),
    onTertiaryContainer = Color(0xFFFBE6C2),
    void = Color(0xFF06080B),
    lowest = Color(0xFF090C10),
    low = Color(0xFF0E1216),
    container = Color(0xFF13181E),
    high = Color(0xFF192028),
    highest = Color(0xFF212933),
    text = Color(0xFFEDF1F6),
    textMuted = Color(0xFF94A0AE),
    outline = Color(0xFF394452),
    hairline = Color(0xFF1B222B)
)

private val DefaultColors = Totality.toScheme()
private val EmberColors = Ember.toScheme()
private val VerdantColors = Verdant.toScheme()
private val SlateColors = Slate.toScheme()

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
