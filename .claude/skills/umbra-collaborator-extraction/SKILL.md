---
name: umbra-collaborator-extraction
description: Use when a Umbra ViewModel or repository implementation has grown large enough that it mixes multiple unrelated method clusters, or when asked to decompose/refactor/split a large class in this codebase. Documents the established collaborator-extraction pattern (EventChannelRouting, EventIngestCache, RelayIssueBannerCoordinator, FeedStateMergeCoordinator, FeedEngagementSchedulingCoordinator, ProfileObserversCoordinator, InteractionActionsCoordinator, RelayCrudCoordinator, RelayListPublishingCoordinator) so a new decomposition matches the existing shape instead of inventing a new one.
---

# Decomposing a large ViewModel/repository in Umbra

Umbra has decomposed five large classes this way already (`EventRepositoryImpl`, `FeedViewModel`,
`ProfileViewModel`, `RelayConfigViewModel`, and — via a pure-function extraction, see below —
`NostrTextRenderer`). Follow the established shape below rather than inventing a new one; the
pattern is now consistent enough across the codebase that a new instance should be
indistinguishable in style from the existing ones.

## When to use this skill

- A `@HiltViewModel` or `@Singleton` repository implementation has grown past roughly 700-800+
  lines and contains two or more method clusters that don't actually depend on each other (e.g.
  "relay-issue banner handling" and "engagement scheduling" in the same `FeedViewModel`).
- You're asked to "decompose," "split," "extract," or "refactor" a large class in this codebase.
- A class mixes a genuinely pure derivation/computation (buckets, computed display state,
  aggregated counts) with stateful, side-effecting methods — the pure part is a stronger
  extraction candidate than the stateful part (see "Pure-function extraction" below).

Don't reach for this on a class that's merely long because it has many *independent* small
methods with no natural grouping, or a class already under ~400-500 lines — extraction has a
real cost (more files, more wiring, a facade layer to keep in sync) that needs a real payoff.

## The established shape: manually-constructed collaborator classes

A collaborator is a plain class — **never `@Singleton`, never Hilt-injected, never
constructor-parameter-injected into the owning class** — that the owning ViewModel/repository
constructs itself as a `private val` field, passing it only the narrow slice of dependencies that
cluster actually needs (not the owning class's entire dependency list).

```kotlin
// RelayConfigViewModel.kt — real example
private val _state = MutableStateFlow(RelayConfigState())
val state: StateFlow<RelayConfigState> = _state.asStateFlow()

// Manually constructed facade delegate (not Hilt-injected). Declared right after _state, not
// earlier — see the NPE gotcha below.
private val relayCrudCoordinator = RelayCrudCoordinator(
    addRelayUseCase = addRelayUseCase,
    updateRelayUseCase = updateRelayUseCase,
    removeRelayUseCase = removeRelayUseCase,
    eventRepository = eventRepository,
    userPreferences = userPreferences,
    state = _state,
    scope = viewModelScope
)
```

The owning class becomes a **facade**: its public `StateFlow`/`SharedFlow` contract and public
method signatures stay byte-identical to before the extraction (existing tests should pass
unmodified), while the method bodies delegate to the collaborator.

### The forward-property-reference NPE gotcha

**A collaborator field must be declared *after* the owning class's own `_state`/`_uiState`
property, never before it.** Kotlin initializes properties top-to-bottom; a collaborator declared
above `_state` and constructed with a reference to `_state` sees it as still-uninitialized (`null`
for a nullable type, or an NPE for non-null) at construction time. This bit a real extraction once
(`RelayIssueBannerCoordinator` in `FeedViewModel`) and is now a checked convention in every
extraction since.

### Naming and placement

- Suffix: `XxxCoordinator` for stateful/side-effecting collaborators (`RelayCrudCoordinator`,
  `ProfileObserversCoordinator`, `FeedStateMergeCoordinator`). The repository-layer equivalents
  from `EventRepositoryImpl`'s decomposition dropped the suffix (`EventChannelRouting`,
  `EventIngestCache`) — match whichever sibling pattern already exists in the file you're touching
  rather than picking one convention cold.
- Placement: same package as the sole owner for an owner-specific collaborator (`ui/feed/` for
  `FeedStateMergeCoordinator`, `ui/relay/` for `RelayCrudCoordinator`, `data/repository/` for
  `EventIngestCache`). If two owners genuinely share the same logic — not just similar-looking
  logic — put the shared collaborator in `ui/common/` instead (see below).

### One collaborator instance per owner, even when shared

`InteractionActionsCoordinator` (`ui/common/InteractionActionsCoordinator.kt`) is used by both
`FeedViewModel` and `ProfileViewModel`, but **each ViewModel constructs its own separate
instance** — it is not a Hilt singleton shared across them. Only genuinely identical plumbing
(the Amber sign-then-publish round trip, mute/pin repository calls, JSON/share-URL formatting)
lives in the shared collaborator; each caller keeps its own `canSignWithAmber()` guard and
mutate-timing decision inline, because those two things differ per caller and forcing them into
the shared class would leak caller-specific policy into shared code. Don't default to "shared
logic → singleton" — match this instance-per-owner shape instead.

## Pure-function extraction — the other half of the pattern

Not every cluster is stateful. `RelayConfigViewModel`'s bucket/connection-state/telemetry
derivation was pure computation with no side effects, so it was extracted as a standalone
top-level function instead of a class:

```kotlin
// RelayDerivedState.kt
internal fun computeRelayDerivedState(inputs: RelayDerivedStateInputs): RelayDerivedState { ... }
```

called from the ViewModel's `combine(...)` chain (`.map { inputs -> computeRelayDerivedState(inputs) }`).
This gets tested directly with plain input/output assertions — no mocks, no coroutine test
scaffolding — which is a stronger testability win than wrapping the same logic in a class. If a
cluster you're extracting has no side effects and no mutable state of its own, prefer a pure
function over a collaborator class; `NostrTextRenderer`'s decomposition into
`TextRenderPrimitives.kt` followed the same "extract the pure/structural part first" instinct.

## Extraction process (matches how every past decomposition was actually sequenced)

1. **Tracer plan first.** Extract the lowest-risk, most self-contained cluster before touching
   anything riskier — this establishes the manual-construction/facade-delegation shape for the
   file, so later, riskier extractions in the same class follow an already-proven pattern instead
   of inventing one under pressure. `EventRepositoryImpl`'s channel-routing cluster and
   `FeedViewModel`'s relay-issue/banner cluster were both tracers for their respective files.
2. **Preserve the public contract exactly.** No changes to `StateFlow`/`SharedFlow` property
   names, types, or emission timing as observed by existing callers. Existing tests for the owning
   class should pass unmodified after the extraction — if they don't, the extraction changed
   behavior, which is out of scope for a pure decomposition.
3. **One dedicated test file per extracted collaborator**, covering the behavior that moved —
   don't fold its tests into the owning class's existing test file.
4. **Split further if the highest-risk cluster is still large.** `EventRepositoryImpl`'s
   ingest/cache/persist cluster (the highest-risk code in that file, with prior bug history) was
   itself split across two separate extraction passes (cache core, then persistence) rather than
   one large one.

## Don't

- Don't make a collaborator `@Singleton`/Hilt-injected — every one of these is manually
  constructed by its sole owner (or, for `InteractionActionsCoordinator`, by each of several
  owners separately).
- Don't declare a collaborator field before the owning class's `_state`/`_uiState` property.
- Don't let an extraction change the owning class's public contract — that's a behavior change
  riding along with a refactor, which makes it harder to review and revert independently.
- Don't extract prematurely — a class under ~400-500 lines with no clearly separable clusters
  doesn't need this; three similar-looking methods aren't automatically a cluster.
- Don't invent a third naming/placement convention when an existing sibling in the same file or
  layer already established one — match it.

## Related

- [`kotlin-coroutines-structured-concurrency`](../kotlin-coroutines-structured-concurrency/SKILL.md) — a collaborator that owns async work still follows the `@Singleton`-scope-ownership rules covered there if the owning class is itself a `@Singleton`; a ViewModel-owned collaborator uses `viewModelScope` passed in from the owner instead.
- [`umbra-signer`](../umbra-signer/SKILL.md) — `InteractionActionsCoordinator`'s actual sign/publish contract, the most-reused collaborator in the codebase.
- [`kotlin-flow-state-event-modeling`](../kotlin-flow-state-event-modeling/SKILL.md) — the `StateFlow`/`SharedFlow` contract-preservation concern this skill's step 2 depends on.
