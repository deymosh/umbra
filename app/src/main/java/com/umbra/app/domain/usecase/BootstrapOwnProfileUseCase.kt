package com.umbra.app.domain.usecase

import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.NostrValidation
import com.umbra.app.domain.repository.EventRepository

/**
 * One-shot-channel (not a standing subscription) profile/social-graph hydration for the
 * signed-in user's own pubkey, kept open by the caller (NostrSessionManager) until the real
 * outbox is learned or a max ceiling elapses. Reuses the exact mechanism
 * [TrackReferencedAuthorUseCase] already uses to fetch a mentioned/quoted author's profile
 * (batched hydration REQ via [BuildProfileHydrationRequestsUseCase]) rather than
 * [EventRepository.noteReferencedAuthor]'s own bookkeeping, since that set is documented as
 * non-owner pubkeys only — but unlike that use case, this one passes `includeOwnerOnlyKinds =
 * true` since it's always hydrating the signed-in user's own pubkey, so the search (10007) and
 * index (10086) relay-list kinds — meaningless for anyone else — belong in this request.
 *
 * This exists because OUTBOX_PROFILE — the persistent, always-on channel that normally keeps the
 * signed-in user's own profile/notes/interactions in sync — is intentionally gated on
 * `relay.isWriteActive` (a genuine kind:10002 declaration), which a brand-new or freshly
 * logged-in account doesn't have yet. Without a fallback, that cold-start gap is unrecoverable:
 * no relay would ever be asked for the user's own kind:0/kind:3/kind:10002, so isWriteActive
 * could never become true either. The channel this use case registers falls into
 * canApplyChannelToRelay's unclassified "else" branch, which already reaches isDiscovered
 * relays — exactly the bootstrap/default pool that exists for this kind of cold start.
 *
 * [start]/[stop] rather than a single suspend call that awaits EOSE and tears itself down: relay
 * connections trickle in over many seconds on Tor (cold circuit setup, a large discovered pool),
 * and a fixed short-lived REQ only reaches whichever relays happened to be connected in that
 * narrow window. Keeping the channel *registered* means EventRepositoryImpl's own
 * relayOpenedFlow-driven reapply (see applyChannelToRelay/reapplyChannelsToRelay) automatically
 * sends this REQ to every later-connecting relay too, for free, for as long as the caller keeps
 * it open — no polling or per-relay retry logic needed here.
 */
class BootstrapOwnProfileUseCase(
    private val eventRepository: EventRepository,
    private val buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase
) {
    companion object {
        private const val HYDRATION_PER_AUTHOR_LIMIT = 5
        private const val CHANNEL_ID = NostrChannels.SELF_PROFILE_BOOTSTRAP
    }

    fun start(rawPubkey: String) {
        val pubkey = NostrValidation.validate64HexOrNull(rawPubkey)?.lowercase() ?: return
        eventRepository.subscribeChannel(
            CHANNEL_ID,
            buildProfileHydrationRequestsUseCase(
                authors = setOf(pubkey),
                chunkSize = 1,
                perAuthorLimit = HYDRATION_PER_AUTHOR_LIMIT,
                includeOwnerOnlyKinds = true
            )
        )
    }

    fun stop() {
        eventRepository.clearChannel(CHANNEL_ID)
    }
}
