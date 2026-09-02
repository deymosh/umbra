package com.umbra.app.domain.model

import com.umbra.app.domain.relay.normalizeRelayUrl

/**
 * Single source of truth for Nostr channel IDs used across repository and UI layers.
 */
object NostrChannels {
    const val OUTBOX_PROFILE = "outbox-profile"
    // Carries two filters in one subscription: the user's own notes/deletions AND their own
    // reactions/reposts (formerly a separate OUTBOX_INTERACTIONS channel) — both are
    // authors={me}, so folding them into one REQ costs one subscription slot instead of two
    // with no change in what's delivered (NIP-01 allows multiple filters per REQ).
    const val OUTBOX_NOTES = "outbox-notes"
    // Carries two filters in one subscription: notes/deletions AND reactions/reposts that
    // #p-tag the user (formerly a separate INBOX_INTERACTIONS channel) — both are the same
    // #p={me} tag filter, so folding them together is the inbox analog of OUTBOX_NOTES above.
    const val INBOX_NOTES = "inbox-notes"

    // Feed channels
    // Carries the followed-authors note filter(s) as its base plus a standing engagement overlay
    // (FeedViewModel.setChannelOverlay — currently-visible notes' reactions/reposts/replies/zaps,
    // formerly a separate FEED_INTERACTIONS channel) layered on top via
    // EventRepository.setChannelOverlay. See that method's doc comment for why an overlay, not a
    // second multi-filter subscribeChannel() call like OUTBOX_NOTES/INBOX_NOTES above: the two
    // halves here are owned and updated by different callers on different triggers.
    const val FEED_NOTES = "feed-notes"
    const val FEED_PROFILES_ONDEMAND = "feed-profiles-ondemand"
    // Standing (never EOSE-closed) watch on authors whose profile is *already* hydrated among
    // currently-visible notes, so a later profile update from one of them is picked up live —
    // unlike FEED_PROFILES_ONDEMAND, which closes right after its one-shot fetch and would never
    // see a subsequent update from an author it already resolved.
    const val FEED_PROFILES = "feed-profiles"
    // Proactive, follow-list-wide metadata sweep — unlike FEED_PROFILES_ONDEMAND (only authors
    // whose notes are already rendered in the feed), this cycles through the whole follow list
    // over time so outbox relay discovery has data to work with even for authors whose notes
    // haven't scrolled into view yet (with hundreds of follows, on-demand-only hydration would
    // take a very long time to cover more than a handful).
    const val FEED_OUTBOX_SWEEP = "feed-outbox-sweep"
    const val SEARCH = "search"
    const val DEFAULT_EVENTS = "default-events"

    // Public (not private) so SubscriptionType.fromChannelId can classify dynamic, per-pubkey
    // channel ids by prefix without duplicating these literals.
    // Carries this profile's own note filter as its base plus a standing engagement overlay
    // (ProfileViewModel.setChannelOverlay — same pattern as FEED_NOTES above; formerly a separate
    // profile-backfill-engagement channel) layered on top via EventRepository.setChannelOverlay.
    const val PROFILE_BACKFILL_NOTES_PREFIX = "profile-backfill-notes"
    const val PROFILE_BACKFILL_METADATA_PREFIX = "profile-backfill-metadata"
    const val PROFILE_FOLLOWS_META_PREFIX = "profile-follows-meta"
    // Fixed, not per-id — every fetchEventById() lookup shares this one channel so relays get one
    // REQ carrying every currently-pending id instead of one REQ per id (see EventRepositoryImpl's
    // pendingEventLookupIds pool).
    const val EVENT_LOOKUP = "event-lookup"
    // NIP-77 (Negentropy sync): the "ids this relay has that we don't" REQ issued once
    // reconciliation with [relayUrl] completes — see NegentropySyncOrchestrator. One channel per
    // relay, not a single shared one: unlike EVENT_LOOKUP's batched-across-everything id pool,
    // each relay's fetch is scoped to exactly the ids that specific relay's own sync just
    // reconciled, so sharing one channel id across relays would let one relay's applyChannel call
    // silently no-op (content-dedup) or clobber another's still-pending fetch.
    const val NEGENTROPY_FETCH_PREFIX = "negentropy-fetch"
    // NIP-77 (Negentropy sync): the NEG-OPEN/NEG-MSG/NEG-CLOSE handshake itself, one channel per
    // relay. Registered purely for "Active Subscriptions" bookkeeping/UI visibility via
    // NostrClient.registerTrackedSubscription() — its stateful, one-shot-per-sync message exchange
    // doesn't fit this file's other channels' "stable, content-deduped, reconnect-reapplied REQ"
    // model (reapplying a stale initial reconciliation message on reconnect would corrupt the
    // protocol state, not resume it), so it deliberately never goes through applyChannel/
    // subscribeChannel — see NostrClient.negOpen and NegentropySyncOrchestrator.
    const val NEGENTROPY_SYNC_PREFIX = "negentropy-sync"
    // One-shot-channel (see BootstrapOwnProfileUseCase) hydration of the signed-in user's own
    // pubkey before any relay list is known yet (a brand-new/freshly-logged-in account) —
    // conceptually the same shape as a PROFILE_LOOKUP, just always for our own pubkey and used
    // once at cold start rather than for an arbitrary author.
    const val SELF_PROFILE_BOOTSTRAP = "self-profile-bootstrap"
    // Deliberately its own prefix, not profileBackfillMetadata's — TrackReferencedAuthorUseCase's
    // one-shot "does this referenced author have a relay list yet" lookup and
    // BackfillProfileUseCase's actively-open profile-screen backfill both request the same kind
    // set for a pubkey, but have different lifetimes (one closes itself after EOSE, the other
    // stays open for as long as the profile screen is). Sharing a channel id would mean whichever
    // one closes first tears down the REQ the other still needs.
    const val REFERENCED_AUTHOR_HYDRATION_PREFIX = "referenced-author-hydration"

    fun profileBackfillNotes(pubkey: String): String =
        "$PROFILE_BACKFILL_NOTES_PREFIX-${pubkey.take(12).lowercase()}"

    fun profileBackfillMetadata(pubkey: String): String =
        "$PROFILE_BACKFILL_METADATA_PREFIX-${pubkey.take(12).lowercase()}"

    fun profileFollowsMeta(pubkey: String): String =
        "$PROFILE_FOLLOWS_META_PREFIX-${pubkey.take(12).lowercase()}"

    /**
     * One-shot channel for a batch of TrackReferencedAuthorUseCase's referenced-author profile
     * lookups. A batch, not one channel per pubkey: many distinct referenced authors can turn up
     * within the same second or two (scrolling through a feed full of quotes/mentions), and
     * subscribing each individually multiplies REQs against relays' own concurrent-subscription
     * caps for no benefit over asking about all of them in one filter. [batchSuffix] just needs
     * to be unique per flush, not derivable from any single pubkey in the batch.
     */
    fun referencedAuthorHydrationBatch(batchSuffix: String): String =
        "$REFERENCED_AUTHOR_HYDRATION_PREFIX-$batchSuffix"

    // The normalized URL itself, not a hash of it — this channelId is purely local bookkeeping
    // (an applyChannel()/resolveChannelId() lookup key), never sent over the wire, so there's no
    // reason to risk even the very small hashCode() collision chance when the real value is just
    // as cheap to keep and far more debuggable in logs.
    fun negentropyFetch(relayUrl: String): String =
        "$NEGENTROPY_FETCH_PREFIX-${normalizeRelayUrl(relayUrl)}"

    fun negentropySync(relayUrl: String): String =
        "$NEGENTROPY_SYNC_PREFIX-${normalizeRelayUrl(relayUrl)}"
}
