package com.umbra.app.ui.devoptions.dbinspector

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.model.DbEventDetail
import com.umbra.app.domain.model.DbTableSummary
import com.umbra.app.domain.repository.DbInspectorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class DbInspectorState(
    val tableSummaries: List<DbTableSummary> = emptyList(),
    val isLoadingSummaries: Boolean = false,
    val searchKind: String = "",
    val searchPubkey: String = "",
    val searchContent: String = "",
    val searchResults: List<DbEventDetail> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val hasMoreResults: Boolean = false,
    val selectedEvent: DbEventDetail? = null
)

@HiltViewModel
class DbInspectorViewModel @Inject constructor(
    private val dbInspectorRepository: DbInspectorRepository
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 50
    }

    private val _state = MutableStateFlow(DbInspectorState())
    val state: StateFlow<DbInspectorState> = _state.asStateFlow()

    init {
        refreshTableSummaries()
    }

    fun refreshTableSummaries() {
        _state.update { it.copy(isLoadingSummaries = true) }
        viewModelScope.launch {
            val summaries = dbInspectorRepository.getTableSummaries()
            _state.update { it.copy(tableSummaries = summaries, isLoadingSummaries = false) }
        }
    }

    fun updateSearchKind(value: String) = _state.update { it.copy(searchKind = value) }
    fun updateSearchPubkey(value: String) = _state.update { it.copy(searchPubkey = value) }
    fun updateSearchContent(value: String) = _state.update { it.copy(searchContent = value) }

    fun search() {
        val current = _state.value
        val kind = current.searchKind.trim().toIntOrNull()
        _state.update { it.copy(isSearching = true, hasSearched = true) }
        viewModelScope.launch {
            val results = dbInspectorRepository.searchEvents(
                kind = kind,
                pubkey = current.searchPubkey.trim(),
                contentQuery = current.searchContent.trim(),
                limit = PAGE_SIZE,
                offset = 0
            )
            _state.update {
                it.copy(searchResults = results, isSearching = false, hasMoreResults = results.size == PAGE_SIZE)
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isSearching || !current.hasMoreResults) return
        val kind = current.searchKind.trim().toIntOrNull()
        _state.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            val more = dbInspectorRepository.searchEvents(
                kind = kind,
                pubkey = current.searchPubkey.trim(),
                contentQuery = current.searchContent.trim(),
                limit = PAGE_SIZE,
                offset = current.searchResults.size
            )
            _state.update {
                it.copy(
                    searchResults = it.searchResults + more,
                    isSearching = false,
                    hasMoreResults = more.size == PAGE_SIZE
                )
            }
        }
    }

    fun selectEvent(id: String) {
        viewModelScope.launch {
            val detail = dbInspectorRepository.getEventDetail(id)
            _state.update { it.copy(selectedEvent = detail) }
        }
    }

    fun clearSelectedEvent() = _state.update { it.copy(selectedEvent = null) }
}
