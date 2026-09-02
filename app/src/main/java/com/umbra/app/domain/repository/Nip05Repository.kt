package com.umbra.app.domain.repository

import com.umbra.app.domain.nip05.Nip05VerificationState

interface Nip05Repository {
    suspend fun verifyNip05(nip05: String, pubkey: String): Result<Nip05VerificationState>
}

