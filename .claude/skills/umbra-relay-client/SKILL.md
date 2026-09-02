---
name: umbra-relay-client
description: Use when wiring a new relay subscription (SubscriptionType, NostrChannels, EventRepository.subscribeChannel), or working with UmbraNostrClient's connect/reconnect/cooldown logic. Adapted from a broader relay-client skill built around a ComposeSubscriptionManager/Subscribable abstraction — Umbra has no such abstraction; a new subscription is a direct EventRepository.subscribeChannel() call from a ViewModel's init{}, same as every existing one.
---

# Relay subscriptions in Umbra

There's no compose-scoped subscription-manager layer between a ViewModel and the relay client — a new screen that needs events it doesn't already have calls `eventRepository.subscribeChannel(channelId, filters)` directly, typically from `init {}`, the same way `FeedViewModel`, `ThreadViewModel`, and `NostrSessionManager` already do. Don't build a `Subscribable`/`ComposeSubscriptionManager`-style indirection layer to match another client's abstraction — there isn't enough repetition yet (three call sites, all straightforward) to justify one.

## Channel ids: `NostrChannels`

`domain/model/NostrChannels.kt` — a plain `object` of `const val` channel-id strings (`OUTBOX_NOTES`, `FEED_NOTES`, etc.) plus prefix-builder functions for per-pubkey channels: `profileBackfillNotes(pubkey)`, `profileBackfillMetadata(pubkey)`, `profileFollowsMeta(pubkey)`, `referencedAuthorHydrationBatch(batchSuffix)`. A new subscription almost always means adding one `const val` (or reusing a prefix-builder) here, not inventing an ad-hoc string at the call site.

## Subscription semantics: `SubscriptionType`

`domain/relay/SubscriptionType.kt` — an enum resolved from a channel id via `fromChannelId(channelId)`, each variant carrying `(icon, family: SubscriptionFamily)`: `OUTBOX_PROFILE, OUTBOX_NOTES, INBOX_NOTES, FEED_NOTES, FEED_PROFILES_ONDEMAND, FEED_PROFILES, FEED_OUTBOX_SWEEP, SEARCH_NOTES, SEARCH_PROFILES, EVENT_LOOKUP, EVENT_INTERACTIONS, PROFILE_BACKFILL, PROFILE_LOOKUP, COUNT, DEFAULT, OTHER`. This is what drives the relay-diagnostics UI's grouping/iconography (`ui/relay/`) — if you add a new `NostrChannels` constant that should show up meaningfully there rather than falling into `OTHER`, add the matching `SubscriptionType` variant and wire `fromChannelId`.

## Subscribing: `EventRepository.subscribeChannel`

`domain/repository/EventRepository.kt`: `fun subscribeChannel(channelId: String, filters: List<EventFilter>)`, implemented in `EventRepositoryImpl.kt`. Call it once per logical subscription; re-calling with the same `channelId` and new filters is how existing channels are meant to be updated (see `NostrSessionManager`'s reconcile loop below), not a new `subscribeChannel` call per change.

## Where connect/reconnect/cooldown lives: `UmbraNostrClient`

`data/nostr/UmbraNostrClient.kt` (`@Singleton`, implements `NostrClient`), the class actually holding OkHttp WebSocket connections. Key constants: `RECONNECT_DELAYS_MS = [0, 5_000, 30_000, 60_000]`, `MAX_CONSECUTIVE_FAILURES_BEFORE_AUTO_DISABLE = 20`, plus SOCKS/Tor-specific throttles (`RATE_LIMIT_THROTTLE_MS`, `BLOCKED_THROTTLE_MS`, `ORBOT_WAIT_DELAYS_MS`) — this is also where Tor-proxy-specific connection failure handling lives, not a generic OkHttp retry. Key methods: `connect(relayUrl): Result<Unit>`, `disconnect(relayUrl)`, `disconnectAll()`, `recordFailureAndScheduleReconnect(relayUrl, reconnectAction)`. The WebSocket bridge is a plain `WebSocketListener` inner class (`WebSocketListenerImpl`) delegating into class-scoped `MutableSharedFlow`s (`_eventFlow`, `_relayIssueFlow`) — **not** `callbackFlow` (see [`umbra-coroutines`](../umbra-coroutines/SKILL.md) for why, and for the hand-rolled `scanEventFrame()` fast-path duplicate-EVENT-frame skip).

## Reconciling the relay set

`NostrSessionManager.start()` (`data/nostr/NostrSessionManager.kt`) `debounce(RELAY_SET_DEBOUNCE_MS)`s relay-set changes before reapplying every channel's REQ to every connected relay — necessary because precise per-relay author routing can shift for *existing* relays too, not just newly-added ones. This is the mechanism a new persistent subscription channel automatically benefits from once it's wired through the standard `subscribeChannel` path — don't build a separate reconcile loop for a new feature's channel.

## Wiring a new subscription — checklist

1. Add a `const val`/prefix-builder to `NostrChannels`.
2. Add a `SubscriptionType` variant if the channel should be distinguishable in relay diagnostics UI.
3. Call `eventRepository.subscribeChannel(NostrChannels.NEW_ID, filters)` from the owning ViewModel's `init {}` (or wherever its lifecycle naturally starts) — key any `LaunchedEffect`/flow collection around it by whatever the subscription actually depends on (pubkey, screen id), not `Unit` (see [`compose-side-effects`](../compose-side-effects/SKILL.md)).
4. If the new channel needs a second, narrower filter layered on top of an existing one rather than a fully separate subscription, check `EventRepositoryImpl` for `setChannelOverlay` before adding a parallel `subscribeChannel` call.
5. Don't forget to unsubscribe/let the channel go stale appropriately when the owning screen leaves — check how `FeedViewModel`/`ThreadViewModel` handle their own channel lifecycle rather than inventing a new teardown path.

## Don't

- Don't build a `ComposeSubscriptionManager`/`Subscribable` abstraction layer — three direct call sites (`FeedViewModel`, `ThreadViewModel`, `NostrSessionManager`) is the current shape and it isn't repetitive enough yet to warrant one.
- Don't open a second `WebSocketListener`/client — `UmbraNostrClient` is the single relay connection point, matching CLAUDE.md's single-`OkHttpClient`, TOR-only rule; there is intentionally no second connection path.
- Don't hand-roll a new reconnect/cooldown mechanism for a feature-specific need — `UmbraNostrClient`'s existing per-relay cooldown and `RECONNECT_DELAYS_MS` backoff already cover the general case (see [`nostr-performance-review`](../nostr-performance-review/SKILL.md) for the reconnect-storm-prevention rationale already documented there).
