package com.umbra.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import com.umbra.app.R
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nostr.NostrSessionController
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.usecase.LogoutUseCase
import com.umbra.app.domain.feed.DefaultFeedFilters
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.util.logging.LogScrubber.scrubPubkeyForLogs
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for authentication
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val relayRepository: RelayRepository,
    private val eventRepository: EventRepository,
    private val feedRepository: FeedRepository,
    private val logoutUseCase: LogoutUseCase,
    private val nostrSessionController: NostrSessionController
) : ViewModel() {

    companion object {
        private const val TAG = "UmbraLogin"
    }

    private val logger = UmbraLog.tag(TAG)

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Check if already logged in on init
     */
    init {
        viewModelScope.launch {
            if (userPreferences.isLoggedIn()) {
                _authState.update { 
                    it.copy(isAuthenticated = true) }
            }
        }
    }

    /**
     * Login anonymously
     */
    fun loginAnonymously() {
        viewModelScope.launch {
            try {
                _authState.update { 
                    it.copy(isLoading = true, errorMessage = null) }

                // Ensure no stale authenticated session remains.
                userPreferences.clearAll()
                val anonymousPubkey = UserPreferences.ANONYMOUS_PUBKEY

                userPreferences.savePublicKey(anonymousPubkey)
                // feed_filter isn't pubkey-scoped: a logout wipes it, and FeedRepositoryImpl only
                // auto-reseeds once per process (its init block) — re-arm it here so a same-process
                // re-login after a real-account logout doesn't leave it empty.
                feedRepository.ensureDefaultFiltersSeeded()

                // Activate user session to trigger backfill/hydration. A failure here must not
                // block login itself, since the pubkey is already saved and the user can retry
                // a hydration later — only the missing visibility into that failure was the bug.
                try {
                    eventRepository.activateUserSession(anonymousPubkey, DefaultFeedFilters.DEFAULT)
                } catch (e: Exception) {
                    logger.e(e) { "Session activation failed" }
                }
                // Re-arms the session controller if a prior logout in this process stopped it
                // (idempotent no-op otherwise) — reads the just-saved pubkey above fresh.
                nostrSessionController.start()

                logger.d { "Logged in anonymously" }

                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true
                    )
                }
            } catch (e: Exception) {
                logger.e(e) { "Anonymous login failed" }
                _authState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiMessage.Res(R.string.login_failed)
                    )
                }
            }
        }
    }

    /**
     * Save public key from AMBER
     */
    fun savePublicKey(pubkey: String) {
        viewModelScope.launch {
            try {
                val normalized = pubkey.trim().lowercase()
                if (normalized.isBlank()) {
                    throw IllegalArgumentException("Empty public key from AMBER")
                }

                // Session handover: clear previous state before storing the new key.
                userPreferences.clearAll()
                userPreferences.savePublicKey(normalized)
                relayRepository.bootstrapDefaultsOnFirstLogin()
                // feed_filter isn't pubkey-scoped: a logout wipes it, and FeedRepositoryImpl only
                // auto-reseeds once per process (its init block) — re-arm it here so a same-process
                // re-login after a real-account logout doesn't leave it empty.
                feedRepository.ensureDefaultFiltersSeeded()
                // Ensure event repository activates the session for backfill/hydration. A
                // failure here must not block login itself, since the pubkey is already saved
                // and the user can retry a hydration later — only the missing visibility into
                // that failure was the bug.
                try {
                    eventRepository.activateUserSession(normalized, DefaultFeedFilters.DEFAULT)
                } catch (e: Exception) {
                    logger.e(e) { "Session activation failed" }
                }
                // Re-arms the session controller if a prior logout in this process stopped it
                // (idempotent no-op otherwise) — reads the just-saved pubkey above fresh.
                nostrSessionController.start()
                logger.d { "Logged in with pubkey: ${scrubPubkeyForLogs(normalized)}" }
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                logger.e(e) { "Failed to save public key" }
                _authState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiMessage.Res(
                            R.string.login_error_with_detail,
                            listOf(e.message ?: "")
                        )
                    )
                }
            }
        }
    }

    fun isAmberInstalled(): Boolean = amberSignerGateway.isAmberInstalled()

    fun requestAmberLogin() {
        viewModelScope.launch {
            try {
                _authState.update { it.copy(isLoading = true, errorMessage = null) }
                val publicKey = amberSignerGateway.requestPublicKey()
                if (publicKey != null) {
                    savePublicKey(publicKey)
                } else {
                    _authState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = UiMessage.Res(R.string.login_no_public_key)
                        )
                    }
                }
            } catch (e: Exception) {
                _authState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiMessage.Res(
                            R.string.login_amber_response_error,
                            listOf(e.message ?: "")
                        )
                    )
                }
            }
        }
    }

    fun openAmberStore() {
        amberSignerGateway.openStore()
    }

    /**
     * Set error message
     */
    fun setError(message: UiMessage) {
        _authState.update {
            it.copy(
                isLoading = false,
                errorMessage = message
            )
        }
    }

    fun setLoading(loading: Boolean) {
        _authState.update {
            it.copy(
                isLoading = loading,
                errorMessage = if (loading) null else _authState.value.errorMessage
            )
        }
    }

    /**
     * Logout (blocking): callers should await completion before allowing navigation
     * away from authenticated screens.
     */
    suspend fun logout() {
        _authState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            logoutUseCase()
        } catch (e: Exception) {
            logger.e(e) { "Logout failed" }
        } finally {
            _authState.update { AuthState() }
        }
    }
}




