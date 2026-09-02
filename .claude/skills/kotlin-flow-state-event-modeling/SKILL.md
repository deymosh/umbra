---
name: kotlin-flow-state-event-modeling
description: Use when writing or reviewing Kotlin StateFlow/SharedFlow/Channel choices, sentinel default values, stateIn/shareIn placement, WhileSubscribed staleness, or MutableStateFlow update patterns. Technique-layer skill — grounded in Umbra's FeedViewModel.notesFlow (shareIn) and UmbraNostrClient's relay-issue SharedFlow.
---

# Kotlin Flow: state and event modeling

## Core principle

**Pick the primitive that matches replay, fan-out, and synchronous-read requirements.** `StateFlow`, `SharedFlow`, `Channel`-backed flows, and cold `Flow` differ in buffering, who sees each emission, and whether `.value` exists. Wrong choices drop events, leak sharing coroutines, or force fake domain sentinels into state.

## When to use this skill

- `MutableStateFlow<T>(SomeSentinel)` — `NoUser`, `Empty`, `Loading` — because the real value is async.
- `.stateIn(...)`/`.shareIn(...)` called inside a function rather than assigned to a property.
- `SharingStarted.WhileSubscribed(...)` on a flow whose `.value` is read synchronously and must stay fresh.
- `MutableSharedFlow` for navigation events, snackbars, or other one-shot emissions where loss would be a bug.
- `MutableStateFlow.value = _state.value.copy(...)` instead of `.update { }`.

## SharedFlow for single-consumer fire-once events

`SharedFlow` defaults have no replay buffer — if nothing is collecting at the exact instant of emission, the event is gone. For a **single UI consumer** handling exactly-once events (navigation, snackbars), a buffered `Channel` exposed as a `Flow` often matches the semantics better:

```kotlin
// ❌ can drop events with no active collector
private val _navEvents = MutableSharedFlow<NavigationEvent>()

// ✅ Channel.receiveAsFlow() is fan-out, not broadcast — one collector per event, no drops
private val _navEvents = Channel<NavigationEvent>(Channel.BUFFERED)
val navEvents: Flow<NavigationEvent> = _navEvents.receiveAsFlow()
```

If multiple observers must all see the same event, `Channel` is wrong (it's fan-out) — use explicit state, durable storage, or a deliberately configured `SharedFlow` with enough replay (see "In Umbra" below for a real multi-consumer case).

## StateFlow polluted with invalid sentinel defaults

`StateFlow` forces an initial value. When the real value is async, don't invent a fake domain value (`NoUser`, placeholder IDs) that every consumer must special-case — either phase the exposure (suspend until the real value exists) or model absence explicitly (`User?`, a sealed `UiState`).

## Mutate `MutableStateFlow` with `update { ... }`

```kotlin
// ❌ read/modify/write can lose concurrent updates
_state.value = _state.value.copy(selectedId = id)

// ✅ transform starts from the latest state, retried atomically
_state.update { it.copy(selectedId = id) }
```

Keep expensive object creation *outside* the `update` block unless it depends on the current state — the block can be retried.

## `stateIn()`/`shareIn()` inside a function

```kotlin
// ❌ new sharing coroutine every call, never completes
fun getPreferences(): StateFlow<Prefs> = repo.prefsFlow.stateIn(scope, SharingStarted.Eagerly, Prefs.Default)

// ✅ one shared instance, computed once
val preferences: StateFlow<Prefs> = repo.prefsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Prefs.Default)
```

## `WhileSubscribed` with synchronous `.value`

`SharingStarted.WhileSubscribed(timeout)` disconnects the upstream when there are no active collectors — `.value` then returns the last cached value, possibly stale. If `.value` must be fresh or initialized with no active collector, use `SharingStarted.Eagerly` instead. `WhileSubscribed` is fine when consumers primarily collect asynchronously and staleness while disconnected is acceptable.

## `.map` on `StateFlow` loses `.value`

Terminate with `.stateIn(...)` if synchronous `.value` reads are still needed downstream.

## Decision: which Flow type?

| Need | Primitive |
|------|-----------|
| State that always has a value, read by both async collectors **and** synchronous code | `StateFlow`, often `SharingStarted.Eagerly` when `.value` matters |
| Hot stream, multiple subscribers, **no** requirement for synchronous `.value` | `SharedFlow` |
| Discrete events for **one** consumer, exactly-once handoff | `Channel(BUFFERED).receiveAsFlow()` |
| Cold stream, one consumer per collection | Plain `Flow` |

## Quick reference

| Symptom | Problem | Fix |
|---------|---------|-----|
| `MutableStateFlow<X>(FakeDomainValue)` | Invalid placeholder default | Model absence explicitly or phase initialization |
| `MutableSharedFlow<Event>` for single-consumer nav/snackbar | Lossy default event stream | `Channel(BUFFERED).receiveAsFlow()` |
| `fun foo() = flow.stateIn(...)` | Per-call sharing coroutine | Make it a `val` / shared instance |
| `_state.value = _state.value.copy(...)` | Non-atomic read/modify/write | `_state.update { it.copy(...) }` |

## When NOT to apply

- Multi-consumer broadcast where every observer really must see every event (see the relay-issue example below) — `SharedFlow` with deliberate replay/buffer sizing is correct there, don't reflexively convert it to a `Channel`.

## In Umbra

Two real examples worth knowing before touching either:

- **`FeedViewModel.notesFlow`** (`ui/feed/FeedViewModel.kt`) is exactly the "shared instance, not per-call" pattern this skill recommends: `combine(...).flatMapLatest { it }.flowOn(Dispatchers.IO).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)`, assigned once as a property. `WhileSubscribed(5_000)` is the right choice here specifically because the feed is only ever collected asynchronously by the screen's `collectAsStateWithLifecycle()` — nothing reads `.value` synchronously off it — so a 5s grace window against config-change/navigation churn is a legitimate use of the "staleness is fine" carve-out, not a bug.
- **`UmbraNostrClient`'s `_relayIssueFlow`** (`data/nostr/UmbraNostrClient.kt`) is `MutableSharedFlow<RelayIssue>(replay = 3000, extraBufferCapacity = 128)` — deliberately *not* a `Channel`, because relay issues have multiple real consumers (any screen showing relay health/diagnostics), and a `Channel` would only deliver each issue to one of them. The large replay/buffer is sized for burst tolerance across reconnect storms, not a copy-paste default — don't "fix" this into a `Channel` on the assumption that fan-out is always cheaper; check whether a call site actually needs single-consumer semantics first.
- **`_eventFlow`** (same file) is a plain `MutableSharedFlow<Event>()` with no replay — matches the "single/transient consumer, loss-tolerant" shape this skill describes for default `SharedFlow` usage.

## Related

- [`kotlin-coroutines-structured-concurrency`](../kotlin-coroutines-structured-concurrency/SKILL.md) — scope ownership (read its "In Umbra" section — Umbra's singleton scopes are a deliberate exception to that skill's default guidance).
- [`compose-side-effects`](../compose-side-effects/SKILL.md) — collecting event flows and wiring side effects in Compose.
- [`umbra-coroutines`](../umbra-coroutines/SKILL.md) — Umbra's WebSocket-to-Flow bridge and debounce/conflate/flowOn usage.
