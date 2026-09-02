package com.umbra.app.ui.relay

import com.umbra.app.R
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip44.Nip44Gateway
import com.umbra.app.domain.nip51.encodeRelayTagUrls
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which relay-list kind the publish/Save flow is signing — 10007/10086 (search/index) also go
 * through nip44_encrypt first; 10002/10050 (outbox/inbox, DM) sign directly. See
 * RelayListPublishingCoordinator.signRelayListKind.
 */
internal enum class SignableRelayListKind { OUTBOX_INBOX, DM, SEARCH, INDEX }

/**
 * Relay-list signing/publishing cluster extracted from [RelayConfigViewModel]. Manually
 * constructed by the facade (not Hilt-injected), sharing the facade's own [state] instance — same
 * shape as [RelayCrudCoordinator]/[com.umbra.app.ui.profile.ProfileObserversCoordinator]'s
 * precedent.
 *
 * Owns publishRelayLists (NIP-65/17/51 encoding) and its signing/encryption helpers. Preserves the
 * exact encrypt-then-sign ordering for SEARCH/INDEX kinds (nip44Encrypt before
 * buildRelayListEventJson/signEvent) — a swapped order would publish plaintext where NIP-44
 * ciphertext is expected.
 */
internal class RelayListPublishingCoordinator(
    private val amberSignerGateway: AmberSignerGateway,
    private val nip44Gateway: Nip44Gateway,
    private val publishSignedEventUseCase: PublishSignedEventUseCase,
    private val userPreferences: UserPreferences,
    private val state: MutableStateFlow<RelayConfigState>,
    private val scope: CoroutineScope
) {

    /**
     * Publishes every relay-list kind with local changes not yet reflected in a published event
     * (explicit "Save" — see RelayConfigScreen's top bar). 10002 (outbox/inbox)
     * and 10050 (DM) go straight to sign_event; 10007 (search) and 10086 (index) go through
     * nip44_encrypt first, per the private-by-default convention for those two kinds.
     * Processed one kind at a time (a plain suspend loop — each kind's encrypt/sign round trip
     * goes through AmberSignerGateway/Nip44Gateway's high-level suspend methods, which already
     * handle background-fast-path + the app-wide Amber launcher internally); a rejected/failed
     * round trip stops the rest of the backlog, matching the previous "discard on failure, no
     * retry cascade" behavior.
     */
    fun publishRelayLists() {
        if (!userPreferences.canSignWithAmber()) {
            state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_publish_relay_lists_unavailable)) }
            return
        }
        val snapshot = state.value
        val backlog = buildList {
            if (snapshot.relayListDirty) add(SignableRelayListKind.OUTBOX_INBOX)
            if (snapshot.dmRelayListDirty) add(SignableRelayListKind.DM)
            if (snapshot.searchListDirty) add(SignableRelayListKind.SEARCH)
            if (snapshot.indexListDirty) add(SignableRelayListKind.INDEX)
        }
        if (backlog.isEmpty()) return
        state.update { it.copy(isPublishing = true) }
        scope.launch {
            for (kind in backlog) {
                val signedEvent = signRelayListKind(kind)
                if (signedEvent == null) {
                    state.update { it.copy(isPublishing = false) }
                    return@launch
                }
                val result = publishSignedEventUseCase(signedEvent)
                if (result.isFailure) {
                    state.update {
                        it.copy(
                            isPublishing = false,
                            errorMessage = UiMessage.Res(R.string.error_publish_relay_list, listOf(result.exceptionOrNull()?.message ?: ""))
                        )
                    }
                    return@launch
                }
                clearDirtyFlag(kind)
            }
            state.update { it.copy(isPublishing = false) }
        }
    }

    /** Encrypts (10007/10086 only) then signs [kind]'s current local state. Null on any rejection/failure. */
    private suspend fun signRelayListKind(kind: SignableRelayListKind): String? {
        val ownPubkey = userPreferences.getPublicKey() ?: return null
        val contentOverride = when (kind) {
            SignableRelayListKind.SEARCH, SignableRelayListKind.INDEX -> {
                val buckets = state.value.relayBuckets
                val urls = (if (kind == SignableRelayListKind.SEARCH) buckets.search else buckets.index)
                    .map { it.url }.toSet()
                nip44Gateway.nip44Encrypt(encodeRelayTagUrls(urls), ownPubkey, ownPubkey) ?: return null
            }
            SignableRelayListKind.OUTBOX_INBOX, SignableRelayListKind.DM -> null
        }
        val eventJson = buildRelayListEventJson(kind, contentOverride) ?: return null
        return amberSignerGateway.signEvent(eventJson, ownPubkey)
    }

    private fun buildRelayListEventJson(kind: SignableRelayListKind, contentOverride: String?): String? {
        val buckets = state.value.relayBuckets
        return when (kind) {
            SignableRelayListKind.OUTBOX_INBOX -> {
                val writeUrls = buckets.outbox.map { it.url }.toSet()
                val readUrls = buckets.inbox.map { it.url }.toSet()
                NostrEventBuilder.relayList(
                    writeOnly = writeUrls - readUrls,
                    readOnly = readUrls - writeUrls,
                    both = writeUrls intersect readUrls
                )
            }
            SignableRelayListKind.DM -> NostrEventBuilder.dmRelayList(buckets.dm.map { it.url }.toSet())
            SignableRelayListKind.SEARCH -> contentOverride?.let { NostrEventBuilder.searchRelaysListEncrypted(it) }
            SignableRelayListKind.INDEX -> contentOverride?.let { NostrEventBuilder.indexRelaysListEncrypted(it) }
        }
    }

    private fun clearDirtyFlag(kind: SignableRelayListKind) {
        state.update {
            when (kind) {
                SignableRelayListKind.OUTBOX_INBOX -> it.copy(relayListDirty = false)
                SignableRelayListKind.DM -> it.copy(dmRelayListDirty = false)
                SignableRelayListKind.SEARCH -> it.copy(searchListDirty = false)
                SignableRelayListKind.INDEX -> it.copy(indexListDirty = false)
            }
        }
    }
}
