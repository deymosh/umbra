package com.umbra.app.data.preferences

import android.content.Context
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.preferences.AppearancePreferences
import com.umbra.app.domain.preferences.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AppearancePreferencesImpl @Inject constructor(
    @ApplicationContext context: Context
) : AppearancePreferences {

    private companion object {
        const val KEY_SELECTED_THEME = "selected_theme"
    }

    private val encryptedPreferences = SecurePreferences(context, "appearance_prefs")
    private val selectedThemeFlow = MutableStateFlow(loadSelectedTheme())

    override fun getSelectedTheme(): ThemePreference = loadSelectedTheme()

    override fun setSelectedTheme(theme: ThemePreference) {
        encryptedPreferences.putString(KEY_SELECTED_THEME, theme.prefValue)
        selectedThemeFlow.value = theme
    }

    override fun observeSelectedTheme(): StateFlow<ThemePreference> = selectedThemeFlow.asStateFlow()

    private fun loadSelectedTheme(): ThemePreference =
        ThemePreference.entries.find { it.prefValue == encryptedPreferences.getString(KEY_SELECTED_THEME) }
            ?: ThemePreference.DEFAULT
}
