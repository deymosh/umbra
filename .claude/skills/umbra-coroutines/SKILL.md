---
name: umbra-coroutines
description: Use when working with Umbra's WebSocket-to-Flow bridge, debounce/conflate/flowOn usage in the relay/repository layer, or deciding where a new coroutine-based data flow should live. Adapted from a broader kotlin-coroutines skill built around a relay pool on callbackFlow + supervisorScope; Umbra's isn't (zero usages of either), it's WebSocketListener callbacks feeding class-scoped MutableSharedFlows plus per-@Singleton CoroutineScopes.
---

# Coroutines in Umbra's data layer

Umbra's relay/data layer coroutine shape is genuinely different from a callbackFlow-based relay pool — don't port `callbackFlow`/`supervisorScope`-based patterns here expecting them to match existing code, because neither is used anywhere in the codebase today.

## The WebSocket-to-Flow bridge: `WebSocketListener`, not `callbackFlow`

`data/nostr/UmbraNostrClient.kt` uses a plain `okhttp3.WebSocketListener` inner class (`WebSocketListenerImpl`) whose `onOpen`/`onMessage`/`onClosing`/`onClosed`/`onFailure` overrides delegate into long-lived, class-scoped `MutableSharedFlow` properties:

```kotlin
protected val _eventFlow = MutableSharedFlow<Event>()
override val eventFlow: SharedFlow<Event> = _eventFlow.asSharedFlow()
protected val _relayIssueFlow = MutableSharedFlow<RelayIssue>(replay = 3000, extraBufferCapacity = 128)
```

If you're adding a new WebSocket-driven data stream, extend this shape — a new `MutableSharedFlow` property fed from the listener callbacks — rather than wrapping the connection in a `callbackFlow`. See [`kotlin-flow-state-event-modeling`](../kotlin-flow-state-event-modeling/SKILL.md)'s "In Umbra" section for why `_relayIssueFlow`'s large replay/buffer is deliberate (multi-consumer, not a copy-paste default).

## `@Singleton` classes own their `CoroutineScope` — this is Umbra's actual convention

Unlike the generic "scopes shouldn't be stored on the callee" guidance, Umbra's `@Singleton` repositories/managers consistently store their own `CoroutineScope(SupervisorJob() + Dispatchers.IO_or_Default)` — `EventRepositoryImpl`, `FeedRepositoryImpl`, `UserRepositoryImpl`, `RelayRepositoryImpl`, `MuteListRepositoryImpl`, `PinListRepositoryImpl`, `ContactListRepositoryImpl`, `BroadcastRepositoryImpl`, `NostrSessionManager`, `TorRuntimeManager`, `UmbraNostrClient` (`clientScope`), `TrackReferencedAuthorUseCase`, `UrlPrefetcher`, `ImagePrefetcher` all do this. **Read [`kotlin-coroutines-structured-concurrency`](../kotlin-coroutines-structured-concurrency/SKILL.md)'s "In Umbra" section before treating a new instance of this pattern as a bug** — it's the established, consistent shape for this codebase's singleton layer (their lifecycle is the process lifecycle), not the generic anti-pattern that skill otherwise warns about.

## Real operator examples, by purpose

- **`debounce`** — `NostrSessionManager.kt`: `.debounce(RELAY_SET_DEBOUNCE_MS).distinctUntilChanged { old, new -> old.signature() == new.signature() }.collect { ... reconcile(...) }` — coalesces a burst of relay-set changes (discovered relays can land one at a time) into one reconcile pass, avoiding a reapply storm.
- **`conflate()`** — `FeedViewModel.kt`: `eventRepository.subscribeToEvents(emptyList()).conflate().distinctUntilChanged { ... }`, with an in-code comment explaining `conflate` is safe there specifically because persistence already happened upstream in `EventRepositoryImpl.subscribeToEvents()` — dropping intermediate emissions here doesn't drop data, only redundant UI refresh signals. **Don't copy `conflate()` onto a new flow without checking whether persistence already happened upstream the same way** — `EventRepositoryImpl` also has a documented case (a relay-list/profile transform stage) that deliberately does *not* conflate, because those emissions must not be dropped.
- **`flowOn(Dispatchers.IO)`** — `EventRepositoryImpl.kt`/`FeedRepositoryImpl.kt`, on `Flow`s that decode Room-stored JSON (`encryptedEventDao.observeCountEventsByPubkeyAndKind(...).flowOn(Dispatchers.IO)`). A Room-backed `Flow<List<T>>` repository method with per-item JSON/parsing work in a `.map{}` and no `flowOn` is a documented past bug shape (main-thread JSON decoding scaling with row count) — see [`nostr-performance-review`](../nostr-performance-review/SKILL.md) for the two concrete instances already fixed this way.
- **ViewModel-owned coroutines** use plain `viewModelScope.launch { }` throughout — no custom scope ownership at the ViewModel layer, matching the UI ↔ state-holder boundary carve-out in `kotlin-coroutines-structured-concurrency`.
- **No `Channel(...)` construction anywhere** — buffering is done via `MutableSharedFlow(extraBufferCapacity = ...)` instead. If a new one-shot, single-consumer event stream is needed, check `kotlin-flow-state-event-modeling` for when a `Channel` would actually be the better fit before defaulting to Umbra's existing `MutableSharedFlow` habit.

## Don't

- Don't introduce `callbackFlow` for a new WebSocket/callback-based integration without a specific reason — the existing `WebSocketListener` + class-scoped `MutableSharedFlow` shape is what every current relay-facing class uses, and mixing patterns makes the data layer harder to reason about, not easier.
- Don't add `conflate()` to a flow without checking whether the emissions it would drop matter (persisted already vs not) — see the `FeedViewModel` vs "deliberately not conflated" example above.
- Don't flag a new `@Singleton`'s stored `CoroutineScope(SupervisorJob() + Dispatchers.X)` as the generic anti-pattern — it's the establishment convention here. Do flag a *non-singleton*, shorter-lived class doing the same thing; that's still the real bug the generic guidance describes.
