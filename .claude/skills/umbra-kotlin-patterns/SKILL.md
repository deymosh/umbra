---
name: umbra-kotlin-patterns
description: Use when deciding StateFlow vs SharedFlow, sealed class vs sealed interface, or applying @Immutable to a new UI state class in Umbra. Adapted from a broader kotlin-expert skill — its DSL-builder and inline/reified sections don't apply, Umbra has neither pattern anywhere in the codebase today.
---

# Kotlin patterns in Umbra

## StateFlow vs SharedFlow

CLAUDE.md is direct here: **`StateFlow<UiState>` exclusively for state, no `LiveData`, updated via `_state.update { it.copy(...) }`.** SharedFlow is for one-shot side effects (navigation, Amber sign correlation), never for anything a screen needs a synchronous "current value" from.

```kotlin
// Private mutable, public read-only — the pattern every ViewModel in Umbra follows
private val _state = MutableStateFlow(FeedState())
val state: StateFlow<FeedState> = _state.asStateFlow()
```

For the deeper decision tree (when a `Channel` beats `SharedFlow`, `WhileSubscribed` vs `Eagerly`, `stateIn`/`shareIn` placement), see [`kotlin-flow-state-event-modeling`](../kotlin-flow-state-event-modeling/SKILL.md) — its "In Umbra" section covers `FeedViewModel.notesFlow`'s real `shareIn` config and `UmbraNostrClient`'s relay-issue `SharedFlow` sizing.

## Sealed classes vs sealed interfaces

Umbra uses sealed classes for state-variant modeling — `TorState` (`ui/tor/TorState.kt`), `TorSideEffect`, `NostrUriEntity` (`domain/nip21/NostrUri.kt`), `CommentPointer` (`domain/nip22/Comment.kt`), `RelayMessage` (`domain/nip01/Event.kt`), `UiMessage` (`ui/common/UiMessage.kt`), `Screen` (`ui/NavHost.kt`, navigation routes — see [`umbra-android-platform`](../umbra-android-platform/SKILL.md)). One sealed interface exists — `BlossomUploadResult` (`domain/usecase/UploadBlossomBlobUseCase.kt`) — for a generic-shaped result type.

The decision: sealed **class** when variants share common constructor data or the hierarchy doesn't need multiple inheritance/variance (Umbra's dominant shape); sealed **interface** when the result type needs variance (`out T`) or a variant needs to implement something else too. Match the existing shape for the kind of thing you're adding — a new "UI state variant with data" is a sealed class like `TorState`/`UiMessage`, a new generic result type is a sealed interface like `BlossomUploadResult`.

## `@Immutable` UI state — already policy, not optional

CLAUDE.md: "UI state data classes are `@Immutable`." Real examples: `FeedState` (`ui/feed/FeedViewModel.kt`), `RelayConfigState` (`ui/relay/RelayConfigViewModel.kt`). For a collection-typed field inside one of these, don't reach for `kotlinx.collections.immutable` (not a Umbra dependency) — wrap it in `ImmutableListSnapshot<T>`/`ImmutableMapSnapshot<K,V>` from `ui/common/ImmutableCollections.kt`, which exist specifically to keep `List`/`Map`-shaped state parameters skippable without a new external dependency. See [`compose-stability-diagnostics`](../compose-stability-diagnostics/SKILL.md) for the full mechanism.

## DSL builders and inline/reified — not an established pattern here

A `TagArrayBuilder`-style fluent DSL (`inline fun tagArray { add(...); remove(...) }` with method chaining) has no equivalent in Umbra: `NostrEventBuilder` (`domain/nip01/NostrEventBuilder.kt`) is a plain `object` of functions building tag arrays directly, not a chained builder. There are also **no `inline fun <reified T>` usages anywhere in the app module** (the one `inline fun` that exists, `ImmutableCollections.kt:60`'s `forEach`, is a non-reified perf-only inline on a wrapper type, not a JSON/type-erasure workaround).

**Don't introduce a new fluent DSL builder or a `reified`-based utility as a "nice to have" while implementing an unrelated feature.** If a new NIP's tag-building genuinely gets complex enough to want one, that's a deliberate, scoped decision (and should follow `nostr-nip-implementation`'s existing `NostrEventBuilder` function-per-kind shape first, per that skill's own guidance) — not something to add opportunistically because another client has the pattern.

## Don't

- Don't use `LiveData` anywhere — CLAUDE.md forbids it outright, `StateFlow` only.
- Don't add a `kotlinx.collections.immutable` dependency for a stability fix — use `ImmutableListSnapshot`/`ImmutableMapSnapshot`.
- Don't add a DSL builder or `reified` inline function just because another client has one — neither pattern exists in Umbra today; introduce one only if a specific, scoped need justifies it.
