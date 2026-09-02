package com.umbra.app.ui.resourceusage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.model.ResourceUsageSnapshot
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.usecase.ObserveResourceUsageUseCase
import com.umbra.app.domain.usecase.TrimMemoryCachesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class AppResourceUsageState(
    val snapshot: ResourceUsageSnapshot? = null,
    val isClearingEventCache: Boolean = false,
    val isTrimmingCaches: Boolean = false
)

@HiltViewModel
class AppResourceUsageViewModel @Inject constructor(
    observeResourceUsageUseCase: ObserveResourceUsageUseCase,
    private val eventRepository: EventRepository,
    private val trimMemoryCachesUseCase: TrimMemoryCachesUseCase
) : ViewModel() {

    private val _isClearingEventCache = MutableStateFlow(false)
    private val _isTrimmingCaches = MutableStateFlow(false)

    val state: StateFlow<AppResourceUsageState> =
        combine(
            observeResourceUsageUseCase(),
            _isClearingEventCache,
            _isTrimmingCaches
        ) { snapshot, isClearing, isTrimming ->
            AppResourceUsageState(snapshot = snapshot, isClearingEventCache = isClearing, isTrimmingCaches = isTrimming)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppResourceUsageState())

    fun clearEventCache() {
        if (_isClearingEventCache.value) return
        _isClearingEventCache.value = true
        viewModelScope.launch {
            eventRepository.clearCache()
            _isClearingEventCache.value = false
        }
    }

    /** Manual escape hatch for the same trim `UmbraApp.onTrimMemory` triggers automatically. */
    fun trimAllCaches() {
        if (_isTrimmingCaches.value) return
        _isTrimmingCaches.value = true
        viewModelScope.launch {
            trimMemoryCachesUseCase(aggressive = true)
            _isTrimmingCaches.value = false
        }
    }
}
