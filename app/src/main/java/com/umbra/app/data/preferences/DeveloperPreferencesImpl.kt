package com.umbra.app.data.preferences

import android.content.Context
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.preferences.DeveloperFlag
import com.umbra.app.domain.preferences.DeveloperPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class DeveloperPreferencesImpl @Inject constructor(
    @ApplicationContext context: Context
) : DeveloperPreferences {

    private val encryptedPreferences = SecurePreferences(context, "developer_prefs")
    private val enabledFlagsFlow = MutableStateFlow(loadEnabledFlags())

    override fun isEnabled(flag: DeveloperFlag): Boolean =
        encryptedPreferences.getString(flag.prefKey)?.toBoolean() ?: false

    override fun setEnabled(flag: DeveloperFlag, enabled: Boolean) {
        encryptedPreferences.putString(flag.prefKey, enabled.toString())
        enabledFlagsFlow.value = loadEnabledFlags()
    }

    override fun observeEnabledFlags(): StateFlow<Set<DeveloperFlag>> = enabledFlagsFlow.asStateFlow()

    private fun loadEnabledFlags(): Set<DeveloperFlag> =
        DeveloperFlag.entries.filterTo(mutableSetOf()) { isEnabled(it) }
}
