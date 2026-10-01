package com.umbra.app.domain.nostr

interface NostrSessionController {
    fun start()
    fun stop()

    /**
     * Signals the OS has brought the app back to the foreground, letting the session manager
     * redial relay sockets that died (or were killed) while the app was backgrounded, without
     * waiting for the next reconnect sweep.
     */
    fun onAppForegrounded()
}
