package com.umbra.app.ui.feedconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.R
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.usecase.GetAllFiltersUseCase
import com.umbra.app.domain.usecase.GetActiveFilterUseCase
import com.umbra.app.domain.usecase.AddFeedFilterUseCase
import com.umbra.app.domain.usecase.UpdateFeedFilterUseCase
import com.umbra.app.domain.usecase.RemoveFeedFilterUseCase
import com.umbra.app.domain.usecase.SetFilterActiveUseCase
import com.umbra.app.domain.usecase.AddMutedAuthorUseCase
import com.umbra.app.domain.usecase.RemoveMutedAuthorUseCase
import com.umbra.app.domain.usecase.ResetFeedFiltersUseCase
import com.umbra.app.ui.common.UiMessage
import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for feed configuration screen
 */
@Immutable
data class FeedConfigState(
    val filters: List<FeedFilter> = emptyList(),
    val activeFilters: List<FeedFilter> = emptyList(),
    val isLoading: Boolean = false,
    val selectedFilter: FeedFilter? = null,
    val errorMessage: UiMessage? = null,
    val showAddDialog: Boolean = false,
    val editingFilter: FeedFilter? = null
)

/**
 * ViewModel for feed configuration management
 * Handles all feed filter operations and UI state
 */
@HiltViewModel
class FeedConfigViewModel @Inject constructor(
    private val getAllFiltersUseCase: GetAllFiltersUseCase,
    private val getActiveFilterUseCase: GetActiveFilterUseCase,
    private val addFeedFilterUseCase: AddFeedFilterUseCase,
    private val updateFeedFilterUseCase: UpdateFeedFilterUseCase,
    private val removeFeedFilterUseCase: RemoveFeedFilterUseCase,
    private val setFilterActiveUseCase: SetFilterActiveUseCase,
    private val addMutedAuthorUseCase: AddMutedAuthorUseCase,
    private val removeMutedAuthorUseCase: RemoveMutedAuthorUseCase,
    private val resetFeedFiltersUseCase: ResetFeedFiltersUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(FeedConfigState())
    val state: StateFlow<FeedConfigState> = _state.asStateFlow()

    init {
        observeFilters()
    }

    private fun observeFilters() {
        viewModelScope.launch {
            combine(
                getAllFiltersUseCase(),
                getActiveFilterUseCase()
            ) { filters, activeFilters ->
                Pair(filters, activeFilters)
            }.collect { (filters, activeFilters) ->
                _state.update {
                    it.copy(
                        filters = filters,
                        activeFilters = activeFilters,
                        isLoading = false
                    )
                }
            }
        }
    }

    // UI Event handlers

    fun selectFilter(filter: FeedFilter) {
        _state.update { 
            it.copy(selectedFilter = filter) }
    }

    fun openAddDialog() {
        _state.update {
            it.copy(showAddDialog = true, editingFilter = null) }
    }

    fun closeAddDialog() {
        _state.update { 
            it.copy(showAddDialog = false, editingFilter = null) }
    }

    fun saveFilter(filter: FeedFilter) {
        viewModelScope.launch {
            try {
                _state.update { 
                    it.copy(isLoading = true) }
                // Treat IDs starting with "filter_" as newly created filters
                if (filter.id.isEmpty() || filter.id.startsWith("filter_")) {
                    addFeedFilterUseCase(filter)
                } else {
                    updateFeedFilterUseCase(filter)
                }
                _state.update {
                    it.copy(
                        showAddDialog = false,
                        editingFilter = null,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_save_filter, listOf(e.message ?: "")),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun deleteFilter(filterId: String) {
        viewModelScope.launch {
            try {
                _state.update { 
                    it.copy(isLoading = true) }
                removeFeedFilterUseCase(filterId)
                _state.update {
                    it.copy(
                        selectedFilter = null,
                        errorMessage = null,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_delete_filter, listOf(e.message ?: "")),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun setActiveFilter(filterId: String) {
        viewModelScope.launch {
            try {
                setFilterActiveUseCase(filterId, true)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_set_active_filter, listOf(e.message ?: ""))
                    )
                }
            }
        }
    }

    fun setFilterActive(filterId: String, active: Boolean) {
        viewModelScope.launch {
            try {
                setFilterActiveUseCase(filterId, active)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_set_active_filter, listOf(e.message ?: ""))
                    )
                }
            }
        }
    }

    fun addMutedAuthor(filterId: String, pubkey: String) {
        viewModelScope.launch {
            try {
                addMutedAuthorUseCase(filterId, pubkey)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_mute_author, listOf(e.message ?: ""))
                    )
                }
            }
        }
    }

    fun removeMutedAuthor(filterId: String, pubkey: String) {
        viewModelScope.launch {
            try {
                removeMutedAuthorUseCase(filterId, pubkey)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_unmute_author, listOf(e.message ?: ""))
                    )
                }
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                resetFeedFiltersUseCase()
                _state.update {
                    it.copy(
                        selectedFilter = null,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_reset_filters, listOf(e.message ?: ""))
                    )
                }
            }
        }
    }

    fun clearError() {
        _state.update { 
            it.copy(errorMessage = null) }
    }

    fun startEditingFilter(filter: FeedFilter) {
        _state.update { 
            it.copy(editingFilter = filter, showAddDialog = true) }
    }
}




