package com.umbra.app.ui.tor

import android.content.Intent

sealed class TorSideEffect {
    data class OpenOrbot(val intent: Intent) : TorSideEffect()
    data class OpenOrbotStore(val intent: Intent) : TorSideEffect()
}
