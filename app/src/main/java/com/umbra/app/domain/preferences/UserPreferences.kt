package com.umbra.app.domain.preferences

import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for user preferences/auth session state.
 */
interface UserPreferences {

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
}
