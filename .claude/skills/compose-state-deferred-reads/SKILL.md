---
name: compose-state-deferred-reads
description: Use when Jetpack Compose code reads scroll, animation, gesture, or other frame-rate State in composition, passes changing values across composable boundaries, or uses value-form layout/draw modifiers. Technique-layer skill, framework-generic — grounded in Umbra's FeedScreen scroll-aware animation gating.
---

# Compose state deferred reads

## Core principle

State reads invalidate the phase that reads them. If a `State<T>` is read in a composable body, changes invalidate composition. If it is read in layout or draw, changes can invalidate only layout or draw. Frame-rate state such as scroll offsets, animations, and drag positions usually belongs in layout/draw, not composition.

The fix is structural: keep the `State<T>` or a provider lambda, and read the value inside a layout/draw callback.

## When to use this skill

- `val x by animate*AsState(...)` is passed to `Modifier.offset(x = ...)`, `Modifier.size(...)`, `Modifier.graphicsLayer(...)`, or another value-form modifier.
- `LazyListState.firstVisibleItemScrollOffset`, `ScrollState.value`, `Animatable.value`, or gesture state is read in a composable body.
- A composable takes `scrollOffset: Int`, `progress: Float`, `dragOffset: Offset`, or similar frame-rate values as a plain parameter.
- Recomposition counters climb during scroll, animation, or gestures even when data is stable.

## 1. Prefer block-form modifiers

```kotlin
// Before: animated value read in composition by the `by` delegate
@Composable
fun SelectionPill(selectedIndex: Int) {
    val offsetX by animateDpAsState(120.dp * selectedIndex)
    Box(Modifier.offset(x = offsetX))
}

// After: State is kept, value is read in the layout-phase offset block
@Composable
fun SelectionPill(selectedIndex: Int) {
    val offsetX = animateDpAsState(120.dp * selectedIndex)
    Box(Modifier.offset { IntOffset(offsetX.value.roundToPx(), 0) })
}
```

| Composition read | Deferred read |
|---|---|
| `Modifier.offset(x = animatedX)` | `Modifier.offset { IntOffset(animatedX.value.roundToPx(), 0) }` |
| `Modifier.graphicsLayer(translationY = y)` | `Modifier.graphicsLayer { translationY = yProvider() }` |

## 2. Pass providers across composable boundaries

If the fast-changing value crosses a composable boundary, pass a provider lambda instead of a snapshot value:

```kotlin
@Composable
fun HeroImage(scrollOffsetProvider: () -> Int, modifier: Modifier = Modifier) {
    AsyncImage(
        model = "...",
        modifier = modifier.graphicsLayer { translationY = -scrollOffsetProvider() / 2f },
    )
}
```

Suffix provider parameters with `Provider` when that clarifies the deferred-read contract.

## 3. Other layout/draw read sites

`Modifier.layout { … }`, custom `Alignment.align(...)`, `drawWithContent`/`drawBehind`, and block-form layer/layout modifiers (`graphicsLayer { }`, `offset { }`). Use these when the state changes *where* something is placed or painted. If the state decides *which composables exist*, it belongs in composition.

## Quick reference

| Symptom | Diagnosis | Fix |
|---|---|---|
| `val x by animateFloatAsState(...)` then `Modifier.offset(...)` | `by` reads in composition | Keep `State<Float>`, read `.value` in `offset {}` |
| `Child(scrollOffset = listState.firstVisibleItemScrollOffset)` | Fast-changing value crosses boundary | `Child(scrollOffsetProvider = { ... })` |
| Draw block still recomposes every frame | Value read before draw block | Move the `State.value` read inside the draw block |

## When NOT to apply

- The state controls which composables are emitted (a composition-time decision).
- The animation is one-shot, cheap, and clarity wins.
- Runtime evidence shows recomposition isn't the bottleneck.

## In Umbra

`ui/feed/FeedScreen.kt`'s `animateAvatars = shouldAnimateAvatar(listState.isScrollInProgress)` (documented in [`nostr-performance-review`](../nostr-performance-review/SKILL.md)) is Umbra's existing example of gating expensive per-item work off a fast-changing scroll signal — related to this skill's concern (frame-rate state shouldn't drive unnecessary composition-phase work) even though it's a boolean gate rather than a positional offset read. If a future animation/offset feature reads `listState.firstVisibleItemScrollOffset` or an `Animatable` directly inside a composable body (rather than inside a block-form modifier), that's this skill's pattern to apply — check `FeedScreen.kt`/`ThreadScreen.kt` first since that's where Umbra's scroll-driven UI already lives.

## Related

- [`compose-state-holder-ui-split`](../compose-state-holder-ui-split/SKILL.md) — where state-holder vs UI split applies when passing providers/lambdas across boundaries.
- [`compose-stability-diagnostics`](../compose-stability-diagnostics/SKILL.md) — parameter stability and compiler reports.
- [`compose-modifier-and-layout-style`](../compose-modifier-and-layout-style/SKILL.md) — child composables need a normal `modifier` parameter before callers can move visual reads into modifiers.
