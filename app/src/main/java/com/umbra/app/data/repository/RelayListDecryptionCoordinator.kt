@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.umbra.app.data.repository

import com.umbra.app.domain.nip44.Nip44Gateway
import com.umbra.app.domain.nip51.RelayListKind
import com.umbra.app.domain.nip51.decodeRelayTagUrls
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the signed-in user's own kind:10007 (search relays) / kind:10086 (index relays) for a
 * fresh NIP-44 "private" [com.umbra.app.domain.nip51.SearchRelaysList.encryptedContent] /
 * [com.umbra.app.domain.nip51.IndexRelaysList.encryptedContent] worth decrypting, and applies it
 * as a local relay role once decrypted — for the whole session, not just while some particular
 * screen happens to be open (see NostrSessionManager.start()/stop(), which drives this).
 *
 * Decryption itself is just [Nip44Gateway.nip44Decrypt] — background/ContentProvider fast path
 * first, interactive Amber request (via the app-wide [com.umbra.app.data.amber.AmberRequestCoordinator])
 * as fallback. Search and index are watched independently, in their own coroutines, and the two
 * decrypt calls are deliberately left free to run concurrently: when both land within milliseconds
 * of each other at session start (the common case, no remembered Amber permission yet), Amber
 * combines them into one approval screen instead of two — answered together via its batched
 * "results" response, which AmberRequestCoordinator.deliverResult correctly parses and dispatches
 * to each waiter. An earlier revision serialized these two calls behind a Mutex specifically
 * because that batched-response parsing didn't exist yet, so the second request's half of a
 * combined answer was silently dropped and never delivered; now that parsing is fixed, forcing
 * them sequential only costs the user two separate approval taps instead of Amber's own one.
 */
@Singleton
class RelayListDecryptionCoordinator @Inject constructor(
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val nip44Gateway: Nip44Gateway
) {
    /** Common shape both SearchRelaysList and IndexRelaysList reduce to for [watch]. */
    private data class ListSnapshot(val relayUrls: Set<String>, val encryptedContent: String?)

    private var started = false

    /** Idempotent — safe to call on every NostrSessionManager.start(); a second call no-ops. */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch { watch(RelayListKind.SEARCH) }
        scope.launch { watch(RelayListKind.INDEX) }
    }

    fun stop() {
        started = false
    }

    private suspend fun watch(kind: RelayListKind) {
        listSnapshotFlow(kind)
            .collect { (ownPubkey, snapshot) ->
                val content = snapshot.encryptedContent ?: return@collect
                if (userRepository.wasRelayListContentApplied(content)) return@collect
                val plaintext = nip44Gateway.nip44Decrypt(content, ownPubkey, ownPubkey) ?: return@collect
                applyDecrypted(kind, ownPubkey, plaintext, snapshot.relayUrls, content)
            }
    }

    private fun listSnapshotFlow(kind: RelayListKind): Flow<Pair<String, ListSnapshot>> =
        userPreferences.getPublicKeyFlow()
            .flatMapLatest { pubkey ->
                if (pubkey.isNullOrBlank() || pubkey == UserPreferences.ANONYMOUS_PUBKEY) {
                    flowOf<Pair<String, ListSnapshot>?>(null)
                } else {
                    val snapshots: Flow<ListSnapshot?> = when (kind) {
                        RelayListKind.SEARCH -> userRepository.observeSearchRelaysList(pubkey)
                            .map { it?.let { list -> ListSnapshot(list.relayUrls, list.encryptedContent) } }
                        RelayListKind.INDEX -> userRepository.observeIndexRelaysList(pubkey)
                            .map { it?.let { list -> ListSnapshot(list.relayUrls, list.encryptedContent) } }
                    }
                    snapshots.map { snapshot -> snapshot?.let { pubkey to it } }
                }
            }
            .filterNotNull()

    private suspend fun applyDecrypted(
        kind: RelayListKind,
        ownPubkey: String,
        plaintext: String,
        publicRelayUrls: Set<String>,
        encryptedContent: String
    ) {
        val merged = publicRelayUrls + decodeRelayTagUrls(plaintext)
        when (kind) {
            RelayListKind.SEARCH -> userRepository.applyDecryptedSearchRelays(ownPubkey, merged)
            RelayListKind.INDEX -> userRepository.applyDecryptedIndexRelays(ownPubkey, merged)
        }
        userRepository.markRelayListContentApplied(encryptedContent)
    }
}
