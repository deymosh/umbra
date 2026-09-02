package com.umbra.app.data.repository.policy

internal object RelayConnectionPolicy {
    fun shouldConnect(
        isTracked: Boolean,
        isConnected: Boolean,
        hasActiveSocket: Boolean
    ): Boolean {
        return !(isTracked && (isConnected || hasActiveSocket))
    }

    /**
     * Pool reconciliation, removal half: a relay tracked from a previous connect cycle that fell
     * out of the currently eligible set — disabled, deleted, or its last active role removed —
     * needs to be torn down. The connect loop itself
     * only ever adds, so without this a relay that drops out of eligibility would otherwise keep
     * its socket open until `disconnectFromAll()` (logout/Tor-drop/app-stop).
     */
    fun staleRelayUrls(connectedUrls: Set<String>, eligibleUrls: Set<String>): Set<String> =
        connectedUrls - eligibleUrls
}