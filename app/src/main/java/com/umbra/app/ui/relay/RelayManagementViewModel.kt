package com.umbra.app.ui.relay

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.R
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip86.RelayManagementCategory
import com.umbra.app.domain.nip86.RelayManagementCause
import com.umbra.app.domain.nip86.RelayManagementEntry
import com.umbra.app.domain.nip86.RelayManagementMethod
import com.umbra.app.domain.nip86.RelayManagementResult
import com.umbra.app.domain.nip86.parseSupportedMethods
import com.umbra.app.domain.repository.RelayManagementRepository
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.util.coroutines.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One manageable section of the screen, in display order. */
enum class RelayManagementTab(val category: RelayManagementCategory, val titleRes: Int) {
    PEOPLE(RelayManagementCategory.PEOPLE, R.string.relay_management_tab_people),
    EVENTS(RelayManagementCategory.EVENTS, R.string.relay_management_tab_events),
    KINDS(RelayManagementCategory.KINDS, R.string.relay_management_tab_kinds),
    IPS(RelayManagementCategory.IPS, R.string.relay_management_tab_ips),
    RELAY(RelayManagementCategory.RELAY, R.string.relay_management_tab_relay)
}

/** Lists shown within a tab. Each mutates independently and refreshes itself after a write. */
@Immutable
data class RelayManagementLists(
    val bannedPubkeys: List<RelayManagementEntry> = emptyList(),
    val allowedPubkeys: List<RelayManagementEntry> = emptyList(),
    val needingModeration: List<RelayManagementEntry> = emptyList(),
    val bannedEvents: List<RelayManagementEntry> = emptyList(),
    val allowedKinds: List<Int> = emptyList(),
    val disallowedKinds: List<Int> = emptyList(),
    val blockedIps: List<RelayManagementEntry> = emptyList()
)

@Immutable
data class RelayManagementState(
    val relayUrl: String = "",
    // Null while the supportedmethods probe is in flight; empty when the relay answered but
    // offers nothing manageable (calm message), non-empty when the tabs to show.
    val supportedTabs: List<RelayManagementTab>? = null,
    val lists: RelayManagementLists = RelayManagementLists(),
    // Per-list loading flag, keyed by list identity below.
    val loadingLists: Set<String> = emptySet(),
    val relayName: String? = null,
    val relayDescription: String? = null,
    val relayIcon: String? = null,
    val errorMessage: UiMessage? = null,
    // Set when the relay answered 401/403 — the whole screen becomes a calm not-authorized note.
    val notAuthorized: Boolean = false
)

@HiltViewModel
class RelayManagementViewModel @Inject constructor(
    private val relayManagementRepository: RelayManagementRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RelayManagementState())
    val state: StateFlow<RelayManagementState> = _state.asStateFlow()

    fun start(relayUrl: String) {
        if (_state.value.relayUrl == relayUrl) return
        _state.update { it.copy(relayUrl = relayUrl) }
        probeSupportedMethods(relayUrl)
    }

    private fun probeSupportedMethods(relayUrl: String) {
        viewModelScope.launch {
            val result = runCatchingCancellable {
                relayManagementRepository.call(relayUrl, RelayManagementMethod.SUPPORTED_METHODS, emptyList())
            }.getOrElse {
                _state.update { it.copy(supportedTabs = emptyList(), errorMessage = UiMessage.Res(R.string.relay_management_error_network)) }
                return@launch
            }
            when (result) {
                is RelayManagementResult.NotAuthorized -> _state.update { it.copy(notAuthorized = true) }
                is RelayManagementResult.Ok -> {
                    val methods = RelayListsCodec.methodNames(result.data.raw)
                    val supported = parseSupportedMethods(methods)
                    val tabs = RelayManagementTab.entries.filter { tab ->
                        RelayManagementMethod.entries.any { it.category == tab.category && it in supported }
                    }
                    _state.update { it.copy(supportedTabs = tabs) }
                    tabs.forEach { loadListsForTab(it) }
                }
                is RelayManagementResult.RelayError ->
                    _state.update { it.copy(supportedTabs = emptyList(), errorMessage = UiMessage.Literal(result.error)) }
                is RelayManagementResult.Transport ->
                    _state.update { it.copy(supportedTabs = emptyList(), errorMessage = result.toUiMessage()) }
            }
        }
    }

    /** Loads every list a tab shows; the list identity strings are the state's loading keys. */
    fun loadListsForTab(tab: RelayManagementTab) {
        val methods = when (tab) {
            RelayManagementTab.PEOPLE -> listOf(
                RelayManagementMethod.LIST_BANNED_PUBKEYS to "bannedPubkeys",
                RelayManagementMethod.LIST_ALLOWED_PUBKEYS to "allowedPubkeys"
            )
            RelayManagementTab.EVENTS -> listOf(
                RelayManagementMethod.LIST_EVENTS_NEEDING_MODERATION to "needingModeration",
                RelayManagementMethod.LIST_BANNED_EVENTS to "bannedEvents"
            )
            RelayManagementTab.KINDS -> listOf(
                RelayManagementMethod.LIST_ALLOWED_KINDS to "allowedKinds",
                RelayManagementMethod.LIST_DISALLOWED_KINDS to "disallowedKinds"
            )
            RelayManagementTab.IPS -> listOf(RelayManagementMethod.LIST_BLOCKED_IPS to "blockedIps")
            RelayManagementTab.RELAY -> emptyList() // no lists; fields come from NIP-11
        }
        methods.forEach { (method, listKey) -> fetchList(method, listKey) }
        if (tab == RelayManagementTab.RELAY) {
            // Fields the user can edit live in RelayInfo, which RelayDetailsScreen already
            // fetched; the ViewModel reads name/description/icon from the relay's answers only
            // when a management call reports them (changerelay* responses echo the new value).
        }
    }

    private fun fetchList(method: RelayManagementMethod, listKey: String) {
        if (listKey in _state.value.loadingLists) return
        _state.update { it.copy(loadingLists = it.loadingLists + listKey) }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                relayManagementRepository.call(_state.value.relayUrl, method, emptyList())
            }.getOrElse { RelayManagementResult.Transport(RelayManagementCause.Network) }
            applyListResult(listKey, result)
        }
    }

    private fun applyListResult(listKey: String, result: RelayManagementResult) {
        _state.update { current ->
            when (result) {
                is RelayManagementResult.Ok -> when (listKey) {
                    "bannedPubkeys" -> current.copy(
                        lists = current.lists.copy(bannedPubkeys = result.data.entries),
                        loadingLists = current.loadingLists - listKey
                    )
                    "allowedPubkeys" -> current.copy(
                        lists = current.lists.copy(allowedPubkeys = result.data.entries),
                        loadingLists = current.loadingLists - listKey
                    )
                    "needingModeration" -> current.copy(
                        lists = current.lists.copy(needingModeration = result.data.entries),
                        loadingLists = current.loadingLists - listKey
                    )
                    "bannedEvents" -> current.copy(
                        lists = current.lists.copy(bannedEvents = result.data.entries),
                        loadingLists = current.loadingLists - listKey
                    )
                    "allowedKinds" -> current.copy(
                        lists = current.lists.copy(allowedKinds = result.data.kindNumbers),
                        loadingLists = current.loadingLists - listKey
                    )
                    "disallowedKinds" -> current.copy(
                        lists = current.lists.copy(disallowedKinds = result.data.kindNumbers),
                        loadingLists = current.loadingLists - listKey
                    )
                    "blockedIps" -> current.copy(
                        lists = current.lists.copy(blockedIps = result.data.entries),
                        loadingLists = current.loadingLists - listKey
                    )
                    else -> current.copy(loadingLists = current.loadingLists - listKey)
                }
                is RelayManagementResult.NotAuthorized -> current.copy(
                    notAuthorized = true,
                    loadingLists = current.loadingLists - listKey
                )
                is RelayManagementResult.RelayError -> current.copy(
                    errorMessage = UiMessage.Literal(result.error),
                    loadingLists = current.loadingLists - listKey
                )
                is RelayManagementResult.Transport -> current.copy(
                    errorMessage = result.toUiMessage(),
                    loadingLists = current.loadingLists - listKey
                )
            }
        }
    }

    fun addPubkey(rawInput: String, reason: String?, toAllowed: Boolean) {
        val pubkey = Bech32Encoder.decodeNpub(rawInput.trim()) ?: rawInput.trim()
        val method = if (toAllowed) RelayManagementMethod.ALLOW_PUBKEY else RelayManagementMethod.BAN_PUBKEY
        mutate(method, listOfNotNull(pubkey, reason?.takeIf { it.isNotBlank() })) {
            loadListsForTab(RelayManagementTab.PEOPLE)
        }
    }

    fun removePubkey(entry: RelayManagementEntry, fromAllowed: Boolean) {
        val method = if (fromAllowed) RelayManagementMethod.UNALLOW_PUBKEY else RelayManagementMethod.UNBAN_PUBKEY
        mutate(method, listOf(entry.identifier)) { loadListsForTab(RelayManagementTab.PEOPLE) }
    }

    fun allowEvent(id: String) {
        mutate(RelayManagementMethod.ALLOW_EVENT, listOf(id)) { loadListsForTab(RelayManagementTab.EVENTS) }
    }

    fun banEvent(id: String) {
        mutate(RelayManagementMethod.BAN_EVENT, listOf(id)) { loadListsForTab(RelayManagementTab.EVENTS) }
    }

    fun unbanEvent(id: String) {
        mutate(RelayManagementMethod.UNBAN_EVENT, listOf(id)) { loadListsForTab(RelayManagementTab.EVENTS) }
    }

    fun unallowEvent(id: String) {
        mutate(RelayManagementMethod.UNALLOW_EVENT, listOf(id)) { loadListsForTab(RelayManagementTab.EVENTS) }
    }

    fun addKind(kind: Int, toAllowed: Boolean) {
        val method = if (toAllowed) RelayManagementMethod.ALLOW_KIND else RelayManagementMethod.DISALLOW_KIND
        mutate(method, listOf(kind.toString())) { loadListsForTab(RelayManagementTab.KINDS) }
    }

    fun removeKind(kind: Int, fromAllowed: Boolean) {
        val method = if (fromAllowed) RelayManagementMethod.DISALLOW_KIND else RelayManagementMethod.ALLOW_KIND
        mutate(method, listOf(kind.toString())) { loadListsForTab(RelayManagementTab.KINDS) }
    }

    fun blockIp(ip: String, reason: String?) {
        mutate(RelayManagementMethod.BLOCK_IP, listOfNotNull(ip, reason?.takeIf { it.isNotBlank() })) {
            loadListsForTab(RelayManagementTab.IPS)
        }
    }

    fun unblockIp(ip: String) {
        mutate(RelayManagementMethod.UNBLOCK_IP, listOf(ip)) { loadListsForTab(RelayManagementTab.IPS) }
    }

    fun changeRelayName(name: String) {
        mutate(RelayManagementMethod.CHANGE_RELAY_NAME, listOf(name)) {
            _state.update { it.copy(relayName = name) }
        }
    }

    fun changeRelayDescription(description: String) {
        mutate(RelayManagementMethod.CHANGE_RELAY_DESCRIPTION, listOf(description)) {
            _state.update { it.copy(relayDescription = description) }
        }
    }

    fun changeRelayIcon(iconUrl: String) {
        mutate(RelayManagementMethod.CHANGE_RELAY_ICON, listOf(iconUrl)) {
            _state.update { it.copy(relayIcon = iconUrl) }
        }
    }

    private fun mutate(method: RelayManagementMethod, params: List<String>, refresh: () -> Unit) {
        viewModelScope.launch {
            val result = runCatchingCancellable {
                relayManagementRepository.call(_state.value.relayUrl, method, params)
            }.getOrElse { RelayManagementResult.Transport(RelayManagementCause.Network) }
            when (result) {
                is RelayManagementResult.NotAuthorized -> _state.update { it.copy(notAuthorized = true) }
                is RelayManagementResult.Ok -> refresh()
                is RelayManagementResult.RelayError ->
                    _state.update { it.copy(errorMessage = UiMessage.Literal(result.error)) }
                is RelayManagementResult.Transport ->
                    _state.update { it.copy(errorMessage = result.toUiMessage()) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}

/**
 * User-facing text for a transport-level failure — never the raw exception/message from the data
 * layer. Tor and signing reuse the app's existing wording; the rest are relay-management specific.
 */
private fun RelayManagementResult.Transport.toUiMessage(): UiMessage = when (cause) {
    RelayManagementCause.TorNotReady -> UiMessage.Res(R.string.tor_waiting_orbot)
    RelayManagementCause.SigningCancelled -> UiMessage.Res(R.string.error_amber_sign_cancelled)
    RelayManagementCause.UnparseableResponse -> UiMessage.Res(R.string.relay_management_error_bad_response)
    is RelayManagementCause.HttpError -> UiMessage.Res(R.string.relay_management_error_http, listOf(cause.code))
    RelayManagementCause.Network -> UiMessage.Res(R.string.relay_management_error_network)
}

/**
 * Parses the supportedmethods answer's flat JSON ("[\"banpubkey\",…]") — kept outside the
 * ViewModel so the parsing itself is plain-Kotlin testable on top of [RelayManagementLists].
 */
private object RelayListsCodec {
    fun methodNames(raw: String): List<String> =
        raw.removePrefix("[").removeSuffix("]")
            .split(',')
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
}
