package com.umbra.app.domain.repository

import com.umbra.app.domain.nip86.RelayManagementMethod
import com.umbra.app.domain.nip86.RelayManagementResult

/**
 * NIP-86 relay management API — one HTTP POST to the relay per call, NIP-98-authenticated.
 * Implementations sign via the external NIP-55 signer for every call and never expose
 * private key material. All failures surface as typed [RelayManagementResult] values, never
 * exceptions.
 */
interface RelayManagementRepository {
    suspend fun call(relayUrl: String, method: RelayManagementMethod, params: List<String>): RelayManagementResult
}
