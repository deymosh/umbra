package com.umbra.app.domain.preferences

import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for the selected app theme. Distinct from [UserPreferences] because this is an
 * install-wide appearance setting, not who is signed in, and must survive logout/login.
 */
interface AppearancePreferences {

    fun getSelectedTheme(): ThemePreference

    fun setSelectedTheme(theme: ThemePreference)

    /**
     * Currently-selected theme as a StateFlow, re-emitted on every [setSelectedTheme] call.
     */
    fun observeSelectedTheme(): StateFlow<ThemePreference>
}
