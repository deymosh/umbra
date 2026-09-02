package com.umbra.app.domain.repository

import com.umbra.app.domain.nip11.RelayInfo

interface RelayInfoRepository {
    /**
     * Fetches NIP-11 info for [relayUrl] and persists it to Room.
     * Respects a 24-hour TTL unless [force] = true.
     * No-op if Tor is not ready.
     */
    suspend fun fetchAndPersist(relayUrl: String, force: Boolean = false)
}

