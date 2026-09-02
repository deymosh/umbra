package com.umbra.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.umbra.app.R
import com.umbra.app.domain.preferences.AppearancePreferences
import com.umbra.app.ui.theme.UmbraThemeOption
import com.umbra.app.ui.theme.toThemePreference
import com.umbra.app.ui.theme.toUmbraThemeOption
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class AppearanceState(
    val options: List<AppearanceOptionItem> = emptyList()
)

@Immutable
data class AppearanceOptionItem(
    val theme: UmbraThemeOption,
    val nameRes: Int,
    val selected: Boolean
)

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val appearancePreferences: AppearancePreferences
) : ViewModel() {

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<AppearanceState> = _state.asStateFlow()

    fun selectTheme(theme: UmbraThemeOption) {
        appearancePreferences.setSelectedTheme(theme.toThemePreference())
        _state.update { buildState() }
    }

    private fun buildState(): AppearanceState {
        val selected = appearancePreferences.getSelectedTheme().toUmbraThemeOption()
        return AppearanceState(
            options = UmbraThemeOption.entries.map { theme ->
                AppearanceOptionItem(
                    theme = theme,
                    nameRes = theme.nameRes(),
                    selected = theme == selected
                )
            }
        )
    }

    private fun UmbraThemeOption.nameRes(): Int = when (this) {
        UmbraThemeOption.DEFAULT -> R.string.appearance_theme_default
        UmbraThemeOption.EMBER -> R.string.appearance_theme_ember
        UmbraThemeOption.VERDANT -> R.string.appearance_theme_verdant
        UmbraThemeOption.SLATE -> R.string.appearance_theme_slate
    }
}
