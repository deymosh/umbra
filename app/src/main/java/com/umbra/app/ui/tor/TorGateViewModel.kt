package com.umbra.app.ui.tor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.tor.TorRuntimeController
import com.umbra.app.domain.tor.TorRuntimeStatus
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TorGateViewModel @Inject constructor(
    // Application context is needed to resolve package launch/store intents safely from ViewModel side effects.
    @ApplicationContext private val context: Context,
    private val torRuntimeManager: TorRuntimeController
) : ViewModel() {

    companion object {
        private const val TAG = "UmbraTor"
        private const val ORBOT_PACKAGE = "org.torproject.android"
    }

    private val logger = UmbraLog.tag(TAG)

    private val _sideEffects = MutableSharedFlow<TorSideEffect>(extraBufferCapacity = 1)
    val sideEffects: SharedFlow<TorSideEffect> = _sideEffects.asSharedFlow()

    val state: StateFlow<TorState> = torRuntimeManager.state
        .map { runtimeState ->
            when {
                runtimeState.ready -> TorState.Connected
                runtimeState.status == TorRuntimeStatus.STARTING_TOR -> TorState.StartingOrbot
                runtimeState.status == TorRuntimeStatus.WAITING_FOR_NETWORK -> TorState.WaitingForNetwork
                runtimeState.status == TorRuntimeStatus.CHECKING -> TorState.Checking
                else -> TorState.WaitingForOrbot
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TorState.Checking)

    init {
        torRuntimeManager.start()
    }

    fun retry() {
        torRuntimeManager.stop()
        torRuntimeManager.start()
    }

    fun openOrbot() {
        val orbotIntent = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE)
        if (orbotIntent != null) {
            orbotIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            viewModelScope.launch {
                _sideEffects.emit(TorSideEffect.OpenOrbot(orbotIntent))
            }
        } else {
            val storeUri = "market://details?id=$ORBOT_PACKAGE".toUri()
            val storeIntent = Intent(Intent.ACTION_VIEW, storeUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            viewModelScope.launch {
                _sideEffects.emit(TorSideEffect.OpenOrbotStore(storeIntent))
            }
        }
        logger.d { "Emitting OpenOrbot side effect" }
    }
}
