---
name: umbra-kotlin-patterns
description: Kotlin modeling choices — StateFlow/SharedFlow/Channel, stateIn/shareIn, sentinel defaults, sealed class vs interface, Immutable UI state, value class vs data class.
---

# Kotlin patterns in Umbra

## Flow primitives

| Need | Use |
|---|---|
| Screen state, always has a value | `StateFlow`, private `MutableStateFlow` + `asStateFlow()`, mutated with `_state.update { it.copy(...) }` (never `_state.value = _state.value.copy(...)`) |
| Hot stream, many subscribers, no `.value` needed | `SharedFlow` |
| One-shot event for one consumer (nav, snackbar) | `Channel(BUFFERED).receiveAsFlow()` — a replay-less `SharedFlow` drops events with no collector |
| One consumer per collection | cold `Flow` |

- No `LiveData`, ever.
- `stateIn`/`shareIn` go on a `val`, never inside a function (a new sharing coroutine per call).
- `WhileSubscribed(t)` goes stale when nothing collects. Use `Eagerly` when code reads `.value` synchronously.
- `.map` on a `StateFlow` loses `.value`, so re-`stateIn` if it's needed.
- Don't invent fake domain sentinels (`NoUser`) for an async initial value. Model absence (`T?`, a sealed state) instead.
- Keep expensive work outside `update {}`, because the block can be retried.

Real cases, so you don't "fix" them:
- `FeedViewModel.notesFlow` is `shareIn(viewModelScope, WhileSubscribed(5_000), replay = 1)` as a property. It is only collected asynchronously, so the 5s grace window is intended.
- `UmbraNostrClient._relayIssueFlow` is a `MutableSharedFlow(replay = 3000, extraBufferCapacity = 128)` on purpose. It has multiple consumers, so a `Channel` (fan-out to one) would be wrong.
- `_eventFlow` is a plain `MutableSharedFlow<Event>()` for loss-tolerant transient consumers.

## Sealed class vs sealed interface

- Sealed **class** is the dominant shape: `TorState`, `UiMessage`, `NostrUriEntity`, `CommentPointer`, `RelayMessage`, `Screen`.
- Sealed **interface** is for generic results that need variance or multiple inheritance: `BlossomUploadResult`.
- Match the existing shape for the kind of thing you add.

## UI state is `@Immutable`

This is policy (e.g. `FeedState`, `RelayConfigState`). For list/map fields, wrap them in `ImmutableListSnapshot`/`ImmutableMapSnapshot` (`ui/common/ImmutableCollections.kt`). Don't add `kotlinx.collections.immutable`.

## Value class vs data class

- A single field with domain meaning → `@JvmInline value class` (Stable for Compose when the underlying type is stable).
- Multiple fields or custom equality → data class.
- No domain meaning → the primitive itself.
- Gotchas:
  - boxing when nullable, generic or vararg
  - no `copy`, `init` state or custom `equals`
  - kotlinx.serialization writes the bare value, so swapping between value class and data class breaks JSON contracts

Umbra has **zero** value classes today. Pubkeys and event ids are bare `String`s everywhere, which is a legitimate future hardening but a huge surface. Only do it if a task asks, and scope it narrowly.

## Don't

- Add a fluent DSL builder or `inline reified` helper opportunistically. `NostrEventBuilder` is a plain `object` of functions, one per kind.
- Retrofit patterns onto unrelated code as a drive-by.
