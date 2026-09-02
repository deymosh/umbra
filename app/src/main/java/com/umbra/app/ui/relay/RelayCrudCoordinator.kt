package com.umbra.app.ui.relay

import com.umbra.app.R
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIdGenerator
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.usecase.AddRelayUseCase
import com.umbra.app.domain.usecase.RemoveRelayUseCase
import com.umbra.app.domain.usecase.UpdateRelayUseCase
import com.umbra.app.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Relay CRUD/enable-flag cluster extracted from [RelayConfigViewModel]. Manually constructed by
 * the facade (not Hilt-injected), sharing the facade's own [state] instance so writes from either
 * side stay mutually visible — same shape as
 * [com.umbra.app.ui.profile.ProfileObserversCoordinator]'s precedent.
 *
 * Owns saveRelay/deleteRelay/removeRelayRole and the per-role enable-flag setters
 * (setOutboxEnabled/setInboxEnabled/setDmEnabled/setSearchEnabled/setIndexEnabled/
 * setDiscoveredRelayEnabled), plus the anonymous-session DM/inbox restrictions those methods
 * enforce today. Neither this class nor [RelayListPublishingCoordinator] performs any network
 * I/O directly — both only call already-Tor-routed use cases/gateways.
 */
internal class RelayCrudCoordinator(
    private val addRelayUseCase: AddRelayUseCase,
    private val updateRelayUseCase: UpdateRelayUseCase,
    private val removeRelayUseCase: RemoveRelayUseCase,
    private val eventRepository: EventRepository,
    private val userPreferences: UserPreferences,
    private val state: MutableStateFlow<RelayConfigState>,
    private val scope: CoroutineScope
) {

    fun saveRelay(relay: Relay) {
        scope.launch {
            try {
                state.update { it.copy(isLoading = true) }

                // Normalized up front so a manually-entered/edited URL (case, trailing slash)
                // never diverges from the normalized form other paths (NIP-65 sync, discovered
                // relays) already store — a relay whose stored url changes case later while a
                // WebSocket connection is open under the old string leaves that connection
                // orphaned and every url-keyed lookup (isConnected, relay issues/subscriptions
                // display) silently mismatching.
                val normalizedInputRelay = relay.copy(url = normalizeRelayUrl(relay.url))
                // Reaching saveRelay() at all means the relay is being given an owned role via
                // the add/edit dialog (the Discovered-section on/off toggle goes through the
                // separate setDiscoveredRelayEnabled() instead), so it must stop being classified
                // as discovered — same rule the apply*RelayListToLocalConfig sync paths already
                // enforce on promotion.
                val sanitizedRelay = (if (userPreferences.isAnonymousSession()) {
                    normalizedInputRelay.copy(
                        isReadEnabled = false,
                        isReadActive = false,
                        isDmEnabled = false,
                        isDmActive = false,
                        dmRequiresAuth = false,
                        isEnabled = normalizedInputRelay.isWriteActive
                    )
                } else {
                    normalizedInputRelay
                }).copy(isDiscovered = false)

                if (relay.id.isEmpty()) {
                    val existingRelay = state.value.relays.firstOrNull {
                        it.url.equals(sanitizedRelay.url, ignoreCase = true)
                    }

                    if (existingRelay != null) {
                        updateRelayUseCase(
                            existingRelay.copy(
                                url = sanitizedRelay.url,
                                isOnion = sanitizedRelay.isOnion,
                                isDiscovered = false,
                                isReadEnabled = existingRelay.isReadEnabled || sanitizedRelay.isReadEnabled,
                                isReadActive = existingRelay.isReadActive || sanitizedRelay.isReadActive,
                                isWriteEnabled = existingRelay.isWriteEnabled || sanitizedRelay.isWriteEnabled,
                                isWriteActive = existingRelay.isWriteActive || sanitizedRelay.isWriteActive,
                                isDmEnabled = existingRelay.isDmEnabled || sanitizedRelay.isDmEnabled,
                                isDmActive = existingRelay.isDmActive || sanitizedRelay.isDmActive,
                                dmRequiresAuth = (existingRelay.isDmEnabled || sanitizedRelay.isDmEnabled),
                                isSearchEnabled = existingRelay.isSearchEnabled || sanitizedRelay.isSearchEnabled,
                                isSearchActive = existingRelay.isSearchActive || sanitizedRelay.isSearchActive,
                                isIndexEnabled = existingRelay.isIndexEnabled || sanitizedRelay.isIndexEnabled,
                                isIndexActive = existingRelay.isIndexActive || sanitizedRelay.isIndexActive,
                                isEnabled =
                                    existingRelay.isReadActive || sanitizedRelay.isReadActive ||
                                    existingRelay.isWriteActive || sanitizedRelay.isWriteActive ||
                                    existingRelay.isDmActive || sanitizedRelay.isDmActive ||
                                    existingRelay.isSearchActive || sanitizedRelay.isSearchActive ||
                                    existingRelay.isIndexActive || sanitizedRelay.isIndexActive
                            )
                        )
                    } else {
                        val newRelay = sanitizedRelay.copy(id = RelayIdGenerator.create())
                        addRelayUseCase(newRelay)
                    }
                } else {
                    updateRelayUseCase(sanitizedRelay)
                }

                // Conservatively marks all four kinds dirty rather than diffing which role flags
                // actually changed — the add/edit dialog can touch any combination at once, and
                // over-marking just means one extra harmless re-publish, not a correctness bug.
                state.update {
                    it.copy(
                        showAddDialog = false,
                        editingRelay = null,
                        errorMessage = null,
                        isLoading = false,
                        relayListDirty = true,
                        dmRelayListDirty = true,
                        searchListDirty = true,
                        indexListDirty = true
                    )
                }
            } catch (e: Exception) {
                state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_save_relay, listOf(e.message ?: "")),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun deleteRelay(relayId: String) {
        scope.launch {
            try {
                state.update { it.copy(isLoading = true) }
                removeRelayUseCase(relayId)
                // Same conservative all-four-dirty marking as saveRelay — the deleted relay may
                // have held any combination of roles.
                state.update {
                    it.copy(
                        selectedRelay = null,
                        errorMessage = null,
                        isLoading = false,
                        relayListDirty = true,
                        dmRelayListDirty = true,
                        searchListDirty = true,
                        indexListDirty = true
                    )
                }
            } catch (e: Exception) {
                state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(R.string.error_delete_relay, listOf(e.message ?: "")),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun removeRelayRole(relayId: String, role: RelayRole) {
        scope.launch {
            val relay = state.value.relays.find { it.id == relayId } ?: return@launch

            if ((role == RelayRole.INBOX || role == RelayRole.DM) && userPreferences.isAnonymousSession()) {
                state.update {
                    it.copy(
                        errorMessage = UiMessage.Res(
                            if (role == RelayRole.INBOX) R.string.error_inbox_anonymous_disabled else R.string.error_dm_anonymous_disabled
                        )
                    )
                }
                return@launch
            }

            val updatedRelay = when (role) {
                RelayRole.OUTBOX -> relay.copy(isWriteEnabled = false, isWriteActive = false)
                RelayRole.INBOX -> relay.copy(isReadEnabled = false, isReadActive = false)
                RelayRole.DM -> relay.copy(isDmEnabled = false, isDmActive = false, dmRequiresAuth = false)
                RelayRole.SEARCH -> relay.copy(isSearchEnabled = false, isSearchActive = false)
                RelayRole.INDEX -> relay.copy(isIndexEnabled = false, isIndexActive = false)
            }.let {
                it.copy(isEnabled = it.hasAnyActiveRole())
            }

            state.update {
                when (role) {
                    RelayRole.OUTBOX, RelayRole.INBOX -> it.copy(relayListDirty = true)
                    RelayRole.DM -> it.copy(dmRelayListDirty = true)
                    RelayRole.SEARCH -> it.copy(searchListDirty = true)
                    RelayRole.INDEX -> it.copy(indexListDirty = true)
                }
            }

            try {
                // Always update the relay - don't delete it when disabled
                updateRelayUseCase(updatedRelay)
                if (relay.isEnabled && !updatedRelay.isEnabled) {
                    eventRepository.disconnectRelay(relay.url)
                }
            } catch (e: Exception) {
                state.update {
                    it.copy(errorMessage = UiMessage.Res(R.string.error_delete_relay, listOf(e.message ?: "")))
                }
            }
        }
    }

    fun setOutboxEnabled(relayId: String, enabled: Boolean) {
        state.update { it.copy(relayListDirty = true) }
        updateRelayRole(relayId) { relay ->
            relay.copy(
                isWriteActive = enabled,
                isEnabled = enabled || relay.isReadActive || relay.isDmActive || relay.isSearchActive || relay.isIndexActive
            )
        }
    }

    fun setInboxEnabled(relayId: String, enabled: Boolean) {
        if (userPreferences.isAnonymousSession()) {
            state.update {
                it.copy(errorMessage = UiMessage.Res(R.string.error_inbox_anonymous_disabled))
            }
            return
        }

        state.update { it.copy(relayListDirty = true) }
        updateRelayRole(relayId) { relay ->
            relay.copy(
                isReadActive = enabled,
                isEnabled = relay.isWriteActive || enabled || relay.isDmActive || relay.isSearchActive || relay.isIndexActive
            )
        }
    }

    fun setDmEnabled(relayId: String, enabled: Boolean) {
        if (userPreferences.isAnonymousSession()) {
            state.update {
                it.copy(errorMessage = UiMessage.Res(R.string.error_dm_anonymous_disabled))
            }
            return
        }

        state.update { it.copy(dmRelayListDirty = true) }
        updateRelayRole(relayId) { relay ->
            if (enabled && !isDmTransportAllowed(relay.url)) {
                state.update {
                    it.copy(errorMessage = UiMessage.Res(R.string.relay_dm_wss_required))
                }
                return@updateRelayRole relay
            }

            relay.copy(
                isDmActive = enabled,
                dmRequiresAuth = if (enabled) true else false,
                isEnabled = relay.isReadActive || relay.isWriteActive || enabled || relay.isSearchActive || relay.isIndexActive
            )
        }
    }

    fun setSearchEnabled(relayId: String, enabled: Boolean) {
        state.update { it.copy(searchListDirty = true) }
        updateRelayRole(relayId) { relay ->
            relay.copy(
                isSearchActive = enabled,
                isEnabled = enabled || relay.isReadActive || relay.isWriteActive || relay.isDmActive || relay.isIndexActive
            )
        }
    }

    fun setIndexEnabled(relayId: String, enabled: Boolean) {
        state.update { it.copy(indexListDirty = true) }
        updateRelayRole(relayId) { relay ->
            relay.copy(
                isIndexActive = enabled,
                isEnabled = enabled || relay.isReadActive || relay.isWriteActive || relay.isDmActive || relay.isSearchActive
            )
        }
    }

    /**
     * Toggle for the Discovered section only — a discovered relay never carries a real
     * isReadEnabled/isReadActive of its own (those exclusively reflect the user's genuine
     * kind:10002 declaration, see RelayRepositoryImpl.buildFirstLoginRelaySet /
     * UserRepositoryImpl.addDiscoveredRelays), so this must not route through setInboxEnabled —
     * doing so would fabricate a fake inbox declaration, which is exactly what made the Relay
     * Details "READ" badge (driven by isReadActive) incorrectly show ON for a relay that was
     * never actually declared as the user's inbox. This purely flips whether Umbra keeps using
     * this auto-discovered relay at all (mirrors connectToEnabledRelays()'s own `isEnabled ||
     * isDiscovered` eligibility check) — no relay-list kind becomes dirty, since a discovered
     * relay's on/off state is never part of anything published.
     */
    fun setDiscoveredRelayEnabled(relayId: String, enabled: Boolean) {
        updateRelayRole(relayId) { relay -> relay.copy(isEnabled = enabled) }
    }

    private fun isDmTransportAllowed(url: String): Boolean {
        val normalized = url.trim().lowercase()
        if (normalized.startsWith("wss://")) return true
        if (!normalized.startsWith("ws://")) return false

        val host = runCatching { URI(normalized).host?.lowercase() }.getOrNull()
        return host?.endsWith(".onion") == true || normalized.contains(".onion")
    }

    private fun updateRelayRole(relayId: String, mapper: (Relay) -> Relay) {
        scope.launch {
            val relay = state.value.relays.find { it.id == relayId } ?: return@launch
            try {
                val updated = mapper(relay)
                updateRelayUseCase(updated)
                // The relay may have been auto-disabled for failing to connect too many times in
                // a row (RelayIssueKind.AUTO_DISABLED) — its consecutive-failure count otherwise
                // sits at that threshold forever, so re-enabling it here would immediately
                // re-trip on the very next failure instead of getting a fresh run.
                if (!relay.isEnabled && updated.isEnabled) {
                    eventRepository.resetRelayFailureCount(relay.url)
                }
                if (relay.isEnabled && !updated.isEnabled) {
                    eventRepository.disconnectRelay(relay.url)
                }
            } catch (e: Exception) {
                state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_update_relay, listOf(e.message ?: ""))) }
            }
        }
    }
}
