package com.umbra.app.domain.preferences

import com.umbra.app.domain.nip77.SyncDirection
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for the user-owned NIP-77 sync direction preference (see
 * RelayConfigScreen's sync card). Distinct from [DeveloperPreferences]: this is a permanent,
 * always-visible user setting, not a dev-flag-gated diagnostic toggle, and distinct from
 * [UserPreferences] since it describes how this install of the app behaves rather than who is
 * signed in — same rationale as [DeveloperPreferences]'s own doc comment.
 */
interface SyncPreferences {

    fun getNegentropySyncDirection(): SyncDirection

    fun setNegentropySyncDirection(direction: SyncDirection)

    /**
     * Live value, re-emitted on every [setNegentropySyncDirection] call.
     */
    fun observeNegentropySyncDirection(): StateFlow<SyncDirection>
}
