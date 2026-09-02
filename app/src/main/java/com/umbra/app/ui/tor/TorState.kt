package com.umbra.app.ui.tor

import androidx.compose.runtime.Immutable

@Immutable
sealed class TorState {
    object Checking          : TorState()
    object StartingOrbot     : TorState()
    object WaitingForNetwork : TorState()
    object WaitingForOrbot   : TorState()
    object Connected         : TorState()
    data class Error(val message: String) : TorState()
}
