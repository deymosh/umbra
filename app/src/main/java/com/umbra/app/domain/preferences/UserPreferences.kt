package com.umbra.app.domain.preferences

import com.umbra.app.domain.relay.RelayAuthMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for user preferences/auth session state.
 */
interface UserPreferences {

    /**
     * How the app signs NIP-42 relay AUTH events against relays the user did not configure
     * (discovered/unknown relays). Own relays always use the real key via the external signer,
     * regardless of this setting. Defaults to [RelayAuthMode.THROWAWAY_KEY].
     */
    fun getRelayAuthMode(): RelayAuthMode = getRelayAuthModeFlow().value

    fun getRelayAuthModeFlow(): StateFlow<RelayAuthMode> =
        kotlinx.coroutines.flow.MutableStateFlow(RelayAuthMode.THROWAWAY_KEY)

    fun setRelayAuthMode(mode: RelayAuthMode) {}

    companion object {
        const val ANONYMOUS_PUBKEY = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    /**
     * Save user's public key
     */
    fun savePublicKey(pubkey: String)

    /**
     * Get user's public key
     */
    fun getPublicKey(): String?

    /**
     * Check if user is logged in
     */
    fun isLoggedIn(): Boolean

    /**
     * True when current session is anonymous read-only mode.
     */
    fun isAnonymousSession(): Boolean

    /**
     * True when AMBER signing is allowed for current session.
     */
    fun canSignWithAmber(): Boolean

    /**
     * Logout (clear public key)
     */
    fun logout()

    /**
     * Clear all persisted user-scoped values.
     */
    fun clearAll()

    /**
     * Get public key as a StateFlow that re-emits on every login/logout change.
     */
    fun getPublicKeyFlow(): StateFlow<String?>

    /** Epoch seconds of the newest notification the user has seen; drives the unread dot. */
    fun getNotificationsSeenAtFlow(): StateFlow<Long> = kotlinx.coroutines.flow.MutableStateFlow(0L)

    fun markNotificationsSeen(epochSeconds: Long) {}

    /** Opt-in: long-pressing the Umbra wordmark wipes everything immediately. Off by default. */
    fun getPanicWipeEnabledFlow(): StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun setPanicWipeEnabled(enabled: Boolean) {}

    /** Whether new bookmarks go into the list's encrypted private part. On by default. */
    fun getPrivateBookmarksFlow(): StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)

    fun setPrivateBookmarks(private: Boolean) {}

    /** Whether new mutes go into the mute list's encrypted private part. On by default. */
    fun getPrivateMutesFlow(): StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)

    fun setPrivateMutes(private: Boolean) {}
}
