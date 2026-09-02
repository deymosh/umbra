package com.umbra.app.testutil.fakes

import com.umbra.app.domain.preferences.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared across every test that needs a [UserPreferences] fake — previously hand-rolled with
 * slightly different constructor shapes (and one stale no-op `logout()`) in each of
 * BackfillDeleteLogoutUseCaseTest, ProfileNip05VerificationStateTest, MuteListRepositoryImplTest,
 * and PinListRepositoryImplTest, so a real interface change (e.g. adding a method) meant fixing
 * the same boilerplate in four places. [throwOnClearAll] and [clearAllCalls] only matter to tests
 * that exercise logout; everything else defaults to sensible no-ops.
 */
internal class FakeUserPreferences(
    initialPubkey: String? = null,
    private val throwOnClearAll: Boolean = false
) : UserPreferences {
    private val flow = MutableStateFlow(initialPubkey)
    var clearAllCalls: Int = 0

    override fun savePublicKey(pubkey: String) {
        flow.value = pubkey
    }

    override fun getPublicKey(): String? = flow.value
    override fun isLoggedIn(): Boolean = flow.value != null
    override fun isAnonymousSession(): Boolean = flow.value == UserPreferences.ANONYMOUS_PUBKEY
    override fun canSignWithAmber(): Boolean = flow.value != null && flow.value != UserPreferences.ANONYMOUS_PUBKEY

    override fun logout() {
        flow.value = null
    }

    override fun clearAll() {
        clearAllCalls += 1
        if (throwOnClearAll) throw IllegalStateException("prefs")
        flow.value = null
    }

    override fun getPublicKeyFlow(): StateFlow<String?> = flow
}
