---
name: compose-performance
description: Compose recomposition/jank — parameter stability and skipping, compiler reports, unstable collection params, and frame-rate state (scroll/animation/gesture) read in composition instead of layout/draw.
---

# Compose performance

Before diagnosing anything as new, check `nostr-performance-review`, which lists fixes already shipped:
- stable `key`/`contentType` in lists
- scroll-gated avatar animation
- lambda stabilization in `ProfileScreen`

Two independent axes:

## 1. Stability and skipping

Kotlin 2.4 means **strong skipping is on**.
- Unstable params compare by `===`, stable ones by `equals`, and lambdas are auto-remembered.
- The real question is whether callers create new instances every recomposition.

Fixes, in order:
- `List`/`Set`/`Map` in UI state → `ImmutableListSnapshot`/`ImmutableMapSnapshot` (`ui/common/ImmutableCollections.kt`). **Not** `kotlinx.collections.immutable`, which isn't a dependency.
- UI state classes are `@Immutable` (policy).
- `@Stable` has zero uses. It's only for a class exposing `MutableState` that changes over its lifetime.
- A single-field wrapper → a value class is Stable by default.
- Never annotate just to silence a report. A false promise means stale UI.
- `stabilityConfigurationFiles` isn't configured. Add it only for verified-immutable third-party types.

Compiler reports aren't wired in by default. Add this locally, uncommitted, and build the `benchmark` variant:

```kotlin
if (providers.gradleProperty("composeReports").orNull == "true") {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}
// ./gradlew :app:assembleBenchmark -PcomposeReports=true
// → *-composables.txt (skippability), *-classes.txt (stability)
```

## 2. Where fast state is read

A read invalidates the phase that performs it. Scroll offsets, animations and drags belong in layout/draw:

| Composition read (bad for frame-rate state) | Deferred read |
|---|---|
| `val x by animateDpAsState(...)`, then `Modifier.offset(x = x)` | keep the `State`, then `Modifier.offset { IntOffset(x.value.roundToPx(), 0) }` |
| `Modifier.graphicsLayer(translationY = y)` | `Modifier.graphicsLayer { translationY = yProvider() }` |
| `Child(scrollOffset = listState.firstVisibleItemScrollOffset)` | `Child(scrollOffsetProvider = { ... })` |

- Other deferred sites: `Modifier.layout {}`, `drawBehind`/`drawWithContent`, custom `Alignment`.
- State that decides *which* composables exist stays in composition.
- For booleans derived from scroll, use `derivedStateOf`.
- The profile's collapsing header (`ProfileScreen`) and `FeedScreen`'s scroll-gated work are the existing scroll-driven code to check first.

## Not a performance problem when

- The recomposition count matches real data changes.
- The bug is wrong data rather than excess work.
- There is no profiler or report evidence.
