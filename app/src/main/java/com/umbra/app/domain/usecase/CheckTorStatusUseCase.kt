package com.umbra.app.domain.usecase

import com.umbra.app.domain.repository.TorStatusRepository

data class TorStatusResult(
    val isTor: Boolean,
    val exitIp: String?,
    val countryCode: String?
)

class CheckTorStatusUseCase(
    private val torStatusRepository: TorStatusRepository
) {
    suspend operator fun invoke(): Result<TorStatusResult> =
        torStatusRepository.checkTorStatus()
}
