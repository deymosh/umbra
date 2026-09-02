package com.umbra.app.ui.blossom

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.R
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nipb7.DefaultBlossomServer
import com.umbra.app.domain.nipb7.normalizeBlossomServerUrl
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class BlossomServersState(
    val servers: List<String> = emptyList(),
    val newServerInput: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val errorMessage: UiMessage? = null
) {
    /** BUD-03: shown when [servers] is empty so the user knows uploads still work via the default. */
    val defaultServerUrl: String get() = DefaultBlossomServer.URL
}

/**
 * BUD-03 kind:10063 user server list management — same publish shape as [com.umbra.app.ui.profile.EditProfileViewModel]
 * (Amber-signed replaceable event, published via [PublishSignedEventUseCase]), but for the
 * ordered Blossom server list instead of profile metadata. Local state only reflects what the
 * user is editing; the canonical [UserRepository.getServerList] value updates once the published
 * event round-trips back through the normal relay ingestion pipeline, same as
 * RelayConfigViewModel's relay-list publish.
 */
@HiltViewModel
class BlossomServersViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val publishSignedEventUseCase: PublishSignedEventUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(BlossomServersState())
    val state: StateFlow<BlossomServersState> = _state.asStateFlow()

    init {
        loadCurrentServerList()
    }

    private fun loadCurrentServerList() {
        val pubkey = userPreferences.getPublicKey()
        val existing = pubkey?.let { userRepository.getServerList(it) }
        _state.update { it.copy(servers = existing?.servers.orEmpty(), isLoading = false) }
    }

    fun onNewServerInputChange(value: String) = _state.update { it.copy(newServerInput = value) }

    fun addServer() {
        val normalized = normalizeBlossomServerUrl(_state.value.newServerInput)
        if (normalized == null) {
            if (_state.value.newServerInput.isNotBlank()) {
                _state.update { it.copy(errorMessage = UiMessage.Res(R.string.blossom_server_invalid_url)) }
            }
            return
        }
        _state.update {
            if (normalized in it.servers) {
                it.copy(newServerInput = "")
            } else {
                it.copy(servers = it.servers + normalized, newServerInput = "")
            }
        }
    }

    fun removeServer(server: String) = _state.update { it.copy(servers = it.servers - server) }

    fun moveServerUp(index: Int) = reorder(index, index - 1)
    fun moveServerDown(index: Int) = reorder(index, index + 1)

    private fun reorder(fromIndex: Int, toIndex: Int) {
        _state.update { current ->
            if (fromIndex !in current.servers.indices || toIndex !in current.servers.indices) return@update current
            val reordered = current.servers.toMutableList()
            val moved = reordered.removeAt(fromIndex)
            reordered.add(toIndex, moved)
            current.copy(servers = reordered)
        }
    }

    fun clearError() = _state.update { it.copy(errorMessage = null) }
    fun clearSavedFlag() = _state.update { it.copy(savedSuccessfully = false) }

    fun save() {
        if (!userPreferences.canSignWithAmber()) {
            _state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
            return
        }
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val eventJson = NostrEventBuilder.blossomServerList(_state.value.servers)
            val currentUserHex = userPreferences.getPublicKey()
            val signed = amberSignerGateway.signEvent(eventJson, currentUserHex)
            if (signed == null) {
                _state.update { it.copy(isSaving = false, errorMessage = UiMessage.Res(R.string.error_amber_sign_cancelled)) }
                return@launch
            }
            publishSignedEventUseCase(signed)
                .onSuccess {
                    _state.update { it.copy(isSaving = false, savedSuccessfully = true) }
                }
                .onFailure {
                    _state.update { it.copy(isSaving = false, errorMessage = UiMessage.Res(R.string.error_publish_failed)) }
                }
        }
    }
}
