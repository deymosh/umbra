package com.umbra.app.domain.tor

import androidx.compose.runtime.Immutable

enum class TorRuntimeStatus {
    CHECKING,
    WAITING_FOR_NETWORK,
    STARTING_TOR,
    WAITING_FOR_TOR,
    READY
}

@Immutable
data class TorRuntimeState(
    val status: TorRuntimeStatus,
    val networkAvailable: Boolean,
    val host: String,
    val port: Int,
    val ready: Boolean
)
