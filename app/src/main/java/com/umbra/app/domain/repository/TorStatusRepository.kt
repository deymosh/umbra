package com.umbra.app.domain.repository

import com.umbra.app.domain.usecase.TorStatusResult

interface TorStatusRepository {
    suspend fun checkTorStatus(): Result<TorStatusResult>
}
