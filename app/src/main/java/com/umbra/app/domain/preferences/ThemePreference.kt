package com.umbra.app.domain.preferences

/**
 * Persisted appearance selection. Domain-safe identifier only — entries mirror `ui/theme`'s
 * `UmbraThemeOption` by name, since `domain/` cannot import Compose's `ColorScheme`/`Color`; the
 * `ui/` layer maps between the two.
 */
enum class ThemePreference(val prefValue: String) {
    DEFAULT("default"),
    EMBER("ember"),
    VERDANT("verdant"),
    SLATE("slate")
}
