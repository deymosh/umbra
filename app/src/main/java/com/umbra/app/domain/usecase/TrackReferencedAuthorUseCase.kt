package com.umbra.app.domain.usecase

import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.NostrValidation
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Records a non-owner pubkey referenced by fetched/viewed content (quoted note author, thread
 * author, mentioned profile) and — the first time this pubkey is seen this session — requests its
 * profile metadata, including its NIP-65 relay list. Once that kind:10002 arrives,
 * UserRepositoryImpl.saveRelayList() auto-adds any new outbox relays as "discovered" relays,
 * exactly as it already does for follows.
 *
 * Reuses the same base hydration kind set (0/3/10000/10002/10050) that a non-owner profile view
 * gets from [BackfillProfileUseCase], rather than a narrower kind-0/10002-only fetch: a "profile"
 * in this app is everything needed to view and manage it, not just enough to place a relay
 * connection. Never passes `includeOwnerOnlyKinds = true` — referenced authors are by definition
 * not the signed-in user, so the search (10007)/index (10086) relay-list kinds, meaningful only
 * for this client's own behavior, are never requested here.
 *
 * Each batch is partitioned by [DetermineMissingHydrationKindsUseCase]'s result before building
 * filters: pubkeys that share the same still-missing kind set are requested together (one
 * `EventFilter` per distinct signature, all sent in the same REQ), so a pubkey we already have
 * partial data for (e.g. kind:0 but not kind:10002) isn't re-asked about the kinds we already
 * have — the common case, a pubkey seen for the first time this session and missing everything,
 * still gets a single shared filter like before.
 *
 * Newly-referenced authors are coalesced into a single batched REQ instead of one subscription
 * per author (see [BATCH_WINDOW_MS]) — this is a `@Singleton` use case shared by every caller
 * (FeedViewModel, ThreadViewModel, ProfileViewModel, ViewportImagePrefetchPlanner), so a burst of
 * distinct referenced authors turning up within the same second or two — normal while scrolling a
 * feed full of quotes/mentions — coalesces across all of them, not just calls from one screen.
 * Each batch's channel closes itself after EOSE (or [HYDRATION_CLOSE_MS] as a backstop) — this is
 * a one-shot lookup, not a channel meant to stay open, unlike BackfillProfileUseCase's own
 * metadata channel (owned and closed by ProfileViewModel.onCleared() instead).
 */
class TrackReferencedAuthorUseCase(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase,
    private val determineMissingHydrationKindsUseCase: DetermineMissingHydrationKindsUseCase
) {
    companion object {
        private const val HYDRATION_PER_AUTHOR_LIMIT = 5
        private const val HYDRATION_CLOSE_MS = 15_000L
        private const val BATCH_WINDOW_MS = 800L
        private const val BATCH_CHUNK_SIZE = 20
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val batchLock = Any()
    private val pendingPubkeys = mutableSetOf<String>()
    private var flushJob: Job? = null

    /**
     * @param relayHints NIP-19 relay hints (nprofile1's TLV type 1) that came with the reference
     * to this pubkey, if any — e.g. a quoted note's author or a mention. Dialed directly (see
     * [EventRepository.connectToRelayHints]) instead of only relying on the discovered-relay pool
     * eventually reconnecting, so this batch's hydration REQ (below) actually has a chance of
     * reaching the hint relay before [HYDRATION_CLOSE_MS] tears the channel back down. Best-effort:
     * if the hint relay is still mid-handshake when this batch's own EOSE wait completes early
     * (every already-connected relay answered first), the channel closes before the hint relay
     * gets a REQ at all — a later reference to the same pubkey (a fresh batch) gets another try.
     */
    operator fun invoke(rawPubkey: String, relayHints: List<String> = emptyList()) {
        val pubkey = NostrValidation.validate64HexOrNull(rawPubkey)?.lowercase() ?: return
        val isNewlyTracked = eventRepository.noteReferencedAuthor(pubkey)
        if (!isNewlyTracked) return

        // No longer gated on "outbox relay list already known" here (an all-or-nothing check that
        // would skip a pubkey we're still missing e.g. kind:0 for, just because kind:10002 already
        // arrived): flushBatch's per-pubkey DetermineMissingHydrationKindsUseCase check below
        // already only requests the kinds actually still missing, and drops a pubkey from the
        // batch entirely if nothing is missing.
        if (relayHints.isNotEmpty()) {
            userRepository.discoverRelayHints(relayHints)
            eventRepository.connectToRelayHints(relayHints)
            // Routing data for computeAuthorsPerRelay's hint fallback tier — separate from the
            // connect action above, see EventRepository.recordRelayHint's doc comment.
            eventRepository.recordRelayHint(pubkey, relayHints)
        }

        synchronized(batchLock) {
            pendingPubkeys += pubkey
            if (flushJob?.isActive != true) {
                flushJob = scope.launch { flushBatch() }
            }
        }
    }

    private suspend fun flushBatch() {
        delay(BATCH_WINDOW_MS)
        val batch = synchronized(batchLock) {
            pendingPubkeys.toSet().also { pendingPubkeys.clear() }
        }
        if (batch.isEmpty()) return

        val candidateKinds = BuildProfileHydrationFiltersUseCase.applicableKinds(includeOwnerOnlyKinds = false)
        val missingByPubkey = batch.associateWith { pubkey ->
            determineMissingHydrationKindsUseCase(pubkey, candidateKinds)
        }
        // Group pubkeys sharing the same missing-kind signature into one filter each — the common
        // case (a pubkey seen for the first time, missing everything) still yields a single shared
        // filter across the whole batch; a pubkey with partial local data gets its own, smaller one.
        val groups = missingByPubkey.entries
            .filter { it.value.isNotEmpty() }
            .groupBy({ it.value }, { it.key })
        if (groups.isEmpty()) return

        val filters = groups.flatMap { (missingKinds, pubkeysInGroup) ->
            buildProfileHydrationRequestsUseCase(
                authors = pubkeysInGroup,
                chunkSize = BATCH_CHUNK_SIZE,
                perAuthorLimit = HYDRATION_PER_AUTHOR_LIMIT,
                restrictToKinds = missingKinds
            )
        }

        val channelId = NostrChannels.referencedAuthorHydrationBatch(System.currentTimeMillis().toString())
        eventRepository.subscribeChannel(channelId, filters)
        eventRepository.awaitChannelEoseOrTimeout(channelId, HYDRATION_CLOSE_MS)
        eventRepository.clearChannel(channelId)
    }
}
