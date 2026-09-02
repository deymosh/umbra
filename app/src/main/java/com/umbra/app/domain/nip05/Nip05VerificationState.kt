package com.umbra.app.domain.nip05

import kotlinx.serialization.Serializable

@Serializable
enum class Nip05VerificationState {
    NotAvailable,
    Pending,
    Failed,
    Verified
}
