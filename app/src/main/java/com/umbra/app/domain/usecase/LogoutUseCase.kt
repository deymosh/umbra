package com.umbra.app.domain.usecase

import com.umbra.app.domain.nostr.NostrSessionController
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogoutUseCase(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val contactListRepository: ContactListRepository,
    private val muteListRepository: MuteListRepository,
    private val pinListRepository: PinListRepository,
    private val nostrSessionController: NostrSessionController
) {
    suspend operator fun invoke() {
        withContext(Dispatchers.IO) {
            // Captured before userPreferences.clearAll() below wipes it — needed to scope the
            // backfill-anchor wipe to the account that's actually logging out.
            val pubkey = userPreferences.getPublicKey()

            try {
                // Ensure the socket layer is torn down as part of logout side effects even if the
                // database wipe later throws; this is the client-side guarantee the UI expects.
                // stop() also cancels every session-lifetime background job (bootstrap, retry,
                // auto-disable, Tor recovery) so nothing keeps running post-logout.
                try {
                    nostrSessionController.stop()
                } catch (_: Exception) { }
                try {
                    eventRepository.clearAllData()
                } catch (_: Exception) { }

                // UserRepositoryImpl's in-memory profiles/relayLists/dmRelayLists caches serve
                // reads *before* ever touching Room (see getProfile/getRelayList) — without this,
                // clearAllData()'s DB wipe alone left every profile/relay-list already loaded this
                // process (including the logged-out user's own) still being served from memory
                // after logout, contradicting the app's own "logging out deletes profiles and
                // relay state" promise (see logout_privacy_wipe_message).
                try {
                    userRepository.clearAll()
                } catch (_: Exception) { }

                // Contact/mute/pin lists each keep their own in-memory OwnerTagSetCache (see
                // OwnerTagSetCache.clearAll's doc comment) entirely separate from
                // EventRepository/UserRepository's caches above — without this, the logged-out
                // identity's follow/mute/pin lists stayed resident in memory for the rest of the
                // process's life (and would still answer getCurrent*() calls for that pubkey if
                // the same account logged back in without an app restart).
                try {
                    contactListRepository.clearAll()
                } catch (_: Exception) { }
                try {
                    muteListRepository.clearAll()
                } catch (_: Exception) { }
                try {
                    pinListRepository.clearAll()
                } catch (_: Exception) { }

                if (!pubkey.isNullOrBlank()) {
                    try {
                        eventRepository.clearBackfillAnchors(pubkey)
                    } catch (_: Exception) { }
                }

                userPreferences.clearAll()
            } catch (_: Exception) {
                // best-effort logout; callers handle any further errors
            }
        }
    }
}
