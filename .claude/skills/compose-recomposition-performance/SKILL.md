---
name: compose-recomposition-performance
description: Use when investigating Jetpack Compose recomposition performance, skippable/restartable composables, composables.txt or compiler reports, Layout Inspector recomposition counts, or frame-rate State reads in composition vs layout/draw, and it's not yet clear whether the cause is parameter stability or deferred reads. Router skill — deep fixes live in compose-stability-diagnostics and compose-state-deferred-reads. See also nostr-performance-review for Umbra-specific findings already catalogued (feed/relay, not generic Compose).
---

# Compose recomposition performance

Router only — deep fixes live in [`compose-stability-diagnostics`](../compose-stability-diagnostics/SKILL.md) and [`compose-state-deferred-reads`](../compose-state-deferred-reads/SKILL.md).

## Two axes

1. **Parameter stability / skipping** — can Compose skip this restartable composable; are arguments stable and comparable?
2. **Where `State` is read** — is frame-rate `State` read during composition vs layout/draw?

Either axis can dominate; they combine independently.

## Route here → focused skill

| Primary suspicion | Next skill |
|---|---|
| Skipping, unstable params, compiler/`composables.txt` churn | [`compose-stability-diagnostics`](../compose-stability-diagnostics/SKILL.md) |
| Frame-rate `State` read phase (composition vs layout/draw) | [`compose-state-deferred-reads`](../compose-state-deferred-reads/SKILL.md) |
| Evidence for both | Apply both skills in parallel |

## Review order

1. Decide which axis fits the evidence; open the matching skill.
2. If unclear, sample both — stability churn vs composition-phase reads of fast `State`.
3. Re-measure after changes.

## When NOT to apply

- Recomposition tracks real data changes, or the bug is correctness not cost.
- No profiler / compiler signal suggests a problem.

## In Umbra

Before assuming a recomposition problem is new, check [`nostr-performance-review`](../nostr-performance-review/SKILL.md) first — it catalogues the concrete recomposition fixes Umbra already shipped (stable `LazyColumn` keys/`contentType` in `NotesFeedSection.kt`/`ThreadScreen.kt`, scroll-aware animation gating in `FeedScreen.kt`, the `ProfileScreen.kt` inline-lambda stabilization sweep) so a review doesn't re-diagnose something already fixed under a different name. This skill and its two children are for the *generic* stability/deferred-read technique; `nostr-performance-review` is for "has Umbra already dealt with this."
