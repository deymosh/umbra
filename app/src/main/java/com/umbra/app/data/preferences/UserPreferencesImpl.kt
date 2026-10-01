package com.umbra.app.data.preferences

import android.content.Context
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.crypto.normalizePubkey
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.relay.RelayAuthMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class UserPreferencesImpl @Inject constructor(
    @ApplicationContext context: Context
) : UserPreferences {

    private val encryptedPreferences = SecurePreferences(context, "user_prefs")
    private val pubkeyFlow = MutableStateFlow(getPublicKey())
    private val relayAuthModeFlow = MutableStateFlow(
        RelayAuthMode.fromStored(encryptedPreferences.getString(KEY_RELAY_AUTH_MODE))
    )
    private val panicWipeEnabled = MutableStateFlow(encryptedPreferences.getString(KEY_PANIC_WIPE) == "1")
    private val notificationsSeenAt = MutableStateFlow(
        encryptedPreferences.getString(KEY_NOTIFICATIONS_SEEN_AT)?.toLongOrNull() ?: 0L
    )

    override fun savePublicKey(pubkey: String) {
        val normalized = normalizePubkey(pubkey)
        encryptedPreferences.putString("pubkey", normalized)
        pubkeyFlow.value = normalized
    }

    override fun getPublicKey(): String? {
        val stored = encryptedPreferences.getString("pubkey") ?: return null
        val normalized = normalizePubkey(stored)
        if (normalized != stored) {
            encryptedPreferences.putString("pubkey", normalized)
        }
        return normalized
    }

    override fun isLoggedIn(): Boolean = getPublicKey() != null

    override fun isAnonymousSession(): Boolean = getPublicKey() == UserPreferences.ANONYMOUS_PUBKEY

    override fun canSignWithAmber(): Boolean {
        val pubkey = getPublicKey()
        return !pubkey.isNullOrBlank() && pubkey != UserPreferences.ANONYMOUS_PUBKEY
    }

    override fun logout() {
        encryptedPreferences.remove("pubkey")
        pubkeyFlow.value = null
    }

    override fun clearAll() {
        encryptedPreferences.clear()
        pubkeyFlow.value = null
        notificationsSeenAt.value = 0L
        panicWipeEnabled.value = false
        relayAuthModeFlow.value = RelayAuthMode.THROWAWAY_KEY
    }

    override fun getPublicKeyFlow(): StateFlow<String?> = pubkeyFlow.asStateFlow()

    override fun getNotificationsSeenAtFlow(): StateFlow<Long> = notificationsSeenAt.asStateFlow()

    override fun markNotificationsSeen(epochSeconds: Long) {
        // update{}, not a read-check-write on the value: two concurrent callers could otherwise
        // both read the same older value, both pass the check, and have the earlier timestamp
        // land last — un-seeing notifications the other caller's newer watermark covered.
        var won = false
        val winningValue = notificationsSeenAt.update { current ->
            if (epochSeconds <= current) {
                current
            } else {
                won = true
                epochSeconds
            }
        }
        if (!won) return
        // Persist the value that actually won the update, not this call's argument.
        encryptedPreferences.putString(KEY_NOTIFICATIONS_SEEN_AT, winningValue.toString())
    }

    override fun getPanicWipeEnabledFlow(): StateFlow<Boolean> = panicWipeEnabled.asStateFlow()

    override fun setPanicWipeEnabled(enabled: Boolean) {
        encryptedPreferences.putString(KEY_PANIC_WIPE, if (enabled) "1" else "0")
        panicWipeEnabled.value = enabled
    }

    override fun getRelayAuthModeFlow(): StateFlow<RelayAuthMode> = relayAuthModeFlow.asStateFlow()

    override fun setRelayAuthMode(mode: RelayAuthMode) {
        encryptedPreferences.putString(KEY_RELAY_AUTH_MODE, mode.name)
        relayAuthModeFlow.value = mode
    }

    private companion object {
        const val KEY_RELAY_AUTH_MODE = "relay_auth_mode"
        const val KEY_PANIC_WIPE = "panic_wipe_enabled"
        const val KEY_NOTIFICATIONS_SEEN_AT = "notifications_seen_at"
    }
}
