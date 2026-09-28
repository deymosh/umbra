package com.umbra.app.ui.networkusage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.model.NetworkUsageSnapshot
import com.umbra.app.domain.repository.NetworkUsageRepository
import com.umbra.app.domain.usecase.ObserveOwnActivityUseCase
import com.umbra.app.domain.usecase.OwnActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class NetworkUsageViewModel @Inject constructor(
    private val repository: NetworkUsageRepository,
    observeOwnActivity: ObserveOwnActivityUseCase
) : ViewModel() {
    val activity: StateFlow<OwnActivity?> = observeOwnActivity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val usage: StateFlow<NetworkUsageSnapshot?> = repository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reset() = repository.reset()
}
