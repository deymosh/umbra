package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.NostrValidation
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts a bounded on-demand backfill for a profile timeline.
 *
 * This use case keeps profile hydration logic out of the ViewModel by:
 * 1) subscribing metadata + text-note channels for the target author,
 * 2) requesting an older-events page anchored at the oldest cached note.
 *
 * A no-op for the signed-in user's own pubkey: their own notes/metadata/relay-lists are already
 * kept continuously in sync from login onward via the always-on OUTBOX_PROFILE/OUTBOX_NOTES
 * channels and NostrSessionManager's own history backfill — none of this use case's pinning,
 * relay-hint dialing, or channel subscriptions add any coverage for that case, they'd just
 * duplicate work OUTBOX_NOTES/OUTBOX_PROFILE already do continuously.
 *
 * Profile hydration requests the base kinds (0, 3, 10000, 10002, 10050) that aren't already
 * cached locally — never the owner-only search (10007)/index (10086) relay-list kinds (see
 * [BuildProfileHydrationFiltersUseCase]'s doc comment for why those must never be requested for
 * anyone else's profile): the only pubkey those could apply to is the signed-in user's own, which
 * this use case no-ops for before ever reaching the hydration-kind computation below. See
 * [DetermineMissingHydrationKindsUseCase] for why only-missing-kinds matters: without it,
 * reopening a profile screen re-requests kinds a relay already answered (e.g. kind:0) right
 * alongside the ones it's still missing (e.g. kind:10002).
 *
 * The initial notes subscription is bounded to the last [PROFILE_BACKFILL_WINDOW_SECS] (same
 * window `loadOlderEvents` pages use below) via `since`, rather than relying on `limit` alone —
 * matching how INBOX_NOTES/OUTBOX_NOTES' own interaction filters are time-windowed, not just
 * limit-capped (see EventRepositoryImpl.applySessionChannelsToRelay).
 */
class BackfillProfileUseCase(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase,
    private val determineMissingHydrationKindsUseCase: DetermineMissingHydrationKindsUseCase,
    private val resolveProfileRelayHintsUseCase: ResolveProfileRelayHintsUseCase
) {
    companion object {
        private const val PROFILE_BACKFILL_WINDOW_SECS = 365L * 24 * 60 * 60L
        private const val PROFILE_BACKFILL_LIMIT = 1000
    }

    suspend operator fun invoke(rawPubkey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pubkey = NostrValidation.validate64HexOrNull(rawPubkey)
                    ?.lowercase()
                    ?: error("Invalid profile pubkey for backfill")

                if (userRepository.isSignedInUser(pubkey)) return@runCatching

                eventRepository.pinProfileAuthorForPersistence(pubkey)

                // Proactively dial this profile's already-known relays — same immediate,
                // debounce-bypassing fast path TrackReferencedAuthorUseCase already uses for a
                // referenced author's NIP-19 relay hints, just applied to a viewed profile. Without
                // this, the notes/metadata channels below only ever reach relays already connected
                // for some other reason. Uses ResolveProfileRelayHintsUseCase (declared kind:10002
                // relays unioned with the relay-hints-by-pubkey cache) rather than only the declared
                // relay list: a profile viewed for the first time has no cached kind:10002 yet, so
                // the declared-relays-only version had nothing to dial even when a relay hint for
                // this exact pubkey was already known from elsewhere (e.g. an nprofile TLV).
                resolveProfileRelayHintsUseCase(pubkey).let { relays ->
                    if (relays.isNotEmpty()) eventRepository.connectToRelayHints(relays)
                }

                val metadataChannelId = NostrChannels.profileBackfillMetadata(pubkey)
                val notesChannelId = NostrChannels.profileBackfillNotes(pubkey)

                // Never includeOwnerOnlyKinds here — the only pubkey that could apply to (the
                // signed-in user's own) already returned above.
                val candidateKinds = BuildProfileHydrationFiltersUseCase.applicableKinds(includeOwnerOnlyKinds = false)
                val missingKinds = determineMissingHydrationKindsUseCase(pubkey, candidateKinds)

                eventRepository.subscribeChannel(
                    metadataChannelId,
                    buildProfileHydrationRequestsUseCase(
                        authors = setOf(pubkey),
                        chunkSize = 1,
                        perAuthorLimit = 5,
                        restrictToKinds = missingKinds
                    )
                )

                eventRepository.subscribeChannel(
                    notesChannelId,
                    listOf(
                        EventFilter(
                            // Includes NIP-09 deletion requests so a deletion this author
                            // published (on any client) is actually requested from relays and
                            // applied here too — see the matching comment in
                            // EventRepositoryImpl.applySessionChannelsToRelay(). Also includes
                            // NIP-18 reposts (kind 6/16) so this profile's Notes tab can show what
                            // they've reposted, not just what they've authored — see
                            // EventRepositoryImpl.observeProfileNotes's non-self branch.
                            kinds = setOf(
                                Event.KIND_TEXT_NOTE,
                                Event.KIND_EVENT_DELETION,
                                Event.KIND_REPOST,
                                Event.KIND_GENERIC_REPOST
                            ),
                            authors = setOf(pubkey),
                            since = System.currentTimeMillis() / 1000L - PROFILE_BACKFILL_WINDOW_SECS,
                            limit = PROFILE_BACKFILL_LIMIT
                        )
                    )
                )

                val anchor = eventRepository.getOldestAuthorNoteTimestamp(pubkey)
                    ?: (System.currentTimeMillis() / 1000L)

                eventRepository.loadOlderEvents(
                    channelId = notesChannelId,
                    untilTimestamp = anchor,
                    windowSeconds = PROFILE_BACKFILL_WINDOW_SECS,
                    limit = PROFILE_BACKFILL_LIMIT
                )
            }
        }
}

/**
 * Stops a profile backfill started by [BackfillProfileUseCase] — a separate use case (rather
 * than a second method there) since callers invoke it at a distinct point in the screen
 * lifecycle (leaving the profile screen), not as part of starting the backfill itself.
 *
 * No own-user guard needed here (unlike [BackfillProfileUseCase]'s): [unpinProfileAuthorForPersistence]
 * only ever un-pins a set entry that [BackfillProfileUseCase] itself never pinned for the signed-in
 * user's own pubkey (it now no-ops before reaching that call) — calling this for the own pubkey is
 * already a harmless no-op (own events persist unconditionally regardless of pin state — see
 * EventRepositoryImpl.shouldPersistEvent's isCurrentUserPubkey short-circuit).
 */
class StopProfileBackfillUseCase(
    private val eventRepository: EventRepository
) {
    operator fun invoke(rawPubkey: String) {
        val pubkey = NostrValidation.validate64HexOrNull(rawPubkey)
            ?.lowercase()
            ?: return
        eventRepository.unpinProfileAuthorForPersistence(pubkey)
    }
}

