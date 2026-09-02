package com.umbra.app.data.preferences

import android.content.Context
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.nip77.SyncDirection
import com.umbra.app.domain.preferences.SyncPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SyncPreferencesImpl @Inject constructor(
    @ApplicationContext context: Context
) : SyncPreferences {

    private val encryptedPreferences = SecurePreferences(context, "sync_prefs")
    private val directionFlow = MutableStateFlow(loadDirection())

    override fun getNegentropySyncDirection(): SyncDirection = loadDirection()

    override fun setNegentropySyncDirection(direction: SyncDirection) {
        encryptedPreferences.putString(KEY_NEGENTROPY_SYNC_DIRECTION, direction.name)
        directionFlow.value = direction
    }

    override fun observeNegentropySyncDirection(): StateFlow<SyncDirection> = directionFlow.asStateFlow()

    // Defaults to DOWNLOAD_ONLY — a fresh/untouched setting shouldn't silently start publishing
    // (uploading) a user's local relay set to a relay without them opting in first.
    private fun loadDirection(): SyncDirection =
        encryptedPreferences.getString(KEY_NEGENTROPY_SYNC_DIRECTION)
            ?.let { runCatching { SyncDirection.valueOf(it) }.getOrNull() }
            ?: SyncDirection.DOWNLOAD_ONLY

    companion object {
        private const val KEY_NEGENTROPY_SYNC_DIRECTION = "negentropy_sync_direction"
    }
}
