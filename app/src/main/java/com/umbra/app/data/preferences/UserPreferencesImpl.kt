package com.umbra.app.data.preferences

import android.content.Context
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.crypto.normalizePubkey
import com.umbra.app.domain.preferences.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class UserPreferencesImpl @Inject constructor(
    @ApplicationContext context: Context
) : UserPreferences {

    private val encryptedPreferences = SecurePreferences(context, "user_prefs")
    private val pubkeyFlow = MutableStateFlow(getPublicKey())

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
    }

    override fun getPublicKeyFlow(): StateFlow<String?> = pubkeyFlow.asStateFlow()
}
