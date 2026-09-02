package com.umbra.app.domain.preferences

import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for developer/debug toggles. Distinct from [UserPreferences] because these
 * flags describe how this install of the app behaves, not who is signed in, and must survive
 * logout/login.
 */
interface DeveloperPreferences {

    fun isEnabled(flag: DeveloperFlag): Boolean

    fun setEnabled(flag: DeveloperFlag, enabled: Boolean)

    /**
     * Currently-enabled flags as a StateFlow, re-emitted on every [setEnabled] call.
     */
    fun observeEnabledFlags(): StateFlow<Set<DeveloperFlag>>
}
