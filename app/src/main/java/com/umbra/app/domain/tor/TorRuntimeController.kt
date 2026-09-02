package com.umbra.app.domain.tor

import kotlinx.coroutines.flow.StateFlow

interface TorRuntimeController {
    val state: StateFlow<TorRuntimeState>

    fun start()

    fun stop()
}
