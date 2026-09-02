package com.umbra.app.data.nostr

internal object SessionReconnectPolicy {
    fun shouldReconnect(relaysConnected: Boolean, relaysChanged: Boolean): Boolean {
        return !relaysConnected || relaysChanged
    }
}