---
name: compose-stability-diagnostics
description: Use when writing or reviewing Jetpack Compose parameter stability, compiler reports, skippability, unstable UI state classes, collection parameters, or Kotlin 2.0+ strong skipping behavior. Technique-layer skill, framework-generic — grounded in Umbra's @Immutable UI states and hand-rolled ImmutableCollections.kt (Umbra does not depend on kotlinx.collections.immutable).
---

# Compose stability diagnostics

## Core principle

Compose performance problems from parameters are about **whether inputs compare cheaply and predictably across recompositions**. With Kotlin 2.0.20+ strong skipping is enabled by default, so unstable parameters no longer automatically make restartable composables non-skippable. That does not make stability irrelevant: unstable parameters are compared by instance identity (`===`), stable parameters by equality (`equals`), and churny instances can still defeat skipping.

Umbra is on Kotlin 2.0.21, so strong skipping is the active mode — read reports in that context, not against pre-2.0 guidance.

## When to use this skill

- A composable or screen recomposes more than expected and parameter churn is suspected.
- A UI-state/model class is passed to composables and contains `List`, `Set`, `Map`, ranges, or third-party types.
- `composables.txt`/`classes.txt` shows unstable parameters or non-skippable composables.

## 1. Start with strong skipping

- Restartable composables are skippable even when parameters are unstable, unless explicitly opted out.
- Stable parameters compare with `equals`; unstable parameters compare with instance equality (`===`).
- Lambdas inside composables are automatically remembered based on captures.

The question is "will these parameters compare the way I expect, and are callers creating new unstable instances every frame?" — not "is this composable skippable at all?"

## 2. Generate compiler reports

Umbra doesn't currently configure Compose compiler report/metrics output in `app/build.gradle.kts` — there's no `composeCompiler { reportsDestination = … }` block wired to a `composeReports` Gradle property. To generate one for a specific investigation, add it locally (don't commit it without checking with the team, since it changes every release build's compiler invocation):

```kotlin
if (providers.gradleProperty("composeReports").orNull == "true") {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}
```

```bash
./gradlew :app:assembleRelease -PcomposeReports=true   # .\gradlew.bat on Windows
```

Use a non-debuggable (release-shaped) build for this — Umbra's `benchmark` build type (see [`umbra-gradle`](../umbra-gradle/SKILL.md)) is R8-minified like `release` but still `adb install`-able, so it's the natural variant to point compiler reports at without needing a signed release build.

| File | What it tells you |
|---|---|
| `<module>-classes.txt` | Stability of classes and properties |
| `<module>-composables.txt` | Restartable/skippable status and parameter stability |
| `<module>-module.json` | Aggregate metrics |

## 3. Fix stability where semantics need it

### Immutable collections — Umbra's actual mechanism

`kotlin.collections.List`/`Set`/`Map` are unstable to the Compose compiler regardless of runtime immutability. The generic fix most Compose codebases reach for is `kotlinx.collections.immutable` (`ImmutableList<T>`, `.toImmutableList()`) — **Umbra does not depend on that library**, and adding it is a new-dependency decision, not a drop-in. Umbra's existing mechanism is `app/src/main/java/com/umbra/app/ui/common/ImmutableCollections.kt`: `ImmutableListSnapshot<T>` and `ImmutableMapSnapshot<K,V>`, both `@Immutable`-annotated wrapper value types around a plain `List`/`Map`, documented in-file as existing specifically to restore skippability for collection-shaped UI-state parameters. Use those, not a new `kotlinx.collections.immutable` dependency, unless a real case for the library comes up.

### `@Immutable` / `@Stable`

Umbra already annotates its UI state data classes `@Immutable` — e.g. `FeedState` (`ui/feed/FeedViewModel.kt`), `RelayConfigState` (`ui/relay/RelayConfigViewModel.kt`) — matching CLAUDE.md's rule that "UI state data classes are `@Immutable`." `@Stable` currently has **zero usages** anywhere in `ui/` — Umbra hasn't needed it yet (no Compose-observable mutable-state-holder classes outside `MutableState`/StateFlow), but it's the right tool if one shows up (a plain class exposing `MutableState` properties that mutate over the object's lifetime, as opposed to `@Immutable`'s "every property is fixed at construction" contract).

Do not annotate to silence a report. A false stability promise produces stale UI.

### Third-party immutable types

For types you cannot annotate directly, `stabilityConfigurationFiles` (a Gradle-level allow-list of FQNs to treat as immutable) is the mechanism — not currently configured in Umbra, add only for a verified case (only list types you're willing to promise are immutable; never a mutable type like `java.util.Date`).

## Quick reference

| Symptom | Diagnosis | Fix |
|---|---|---|
| `unstable val items: List<Item>` in a UI state class | Interface collection | Wrap in `ImmutableListSnapshot<Item>` (`ui/common/ImmutableCollections.kt`), not a new dependency |
| Composable skips poorly despite strong skipping | New unstable instance each recomposition | Remember, hoist, or make the type stable/equality-based |
| Reports not generated | No compiler-report Gradle wiring in Umbra by default | Add the `composeReports` block locally, run against `benchmark` |

## When NOT to apply

- The issue is a fast-changing `State` read in composition, such as scroll or animation — see [`compose-state-deferred-reads`](../compose-state-deferred-reads/SKILL.md).
- Recomposition count matches real data changes.
- The bug is wrong/stale data, not excess work.

## Related

- [`compose-state-deferred-reads`](../compose-state-deferred-reads/SKILL.md) — frame-rate state should often be read in layout/draw rather than composition.
- [`compose-recomposition-performance`](../compose-recomposition-performance/SKILL.md) — entry point when the axis isn't yet clear.
- [`kotlin-types-value-class`](../kotlin-types-value-class/SKILL.md) — `@JvmInline value class` is `Stable` by default and can remove the need for `@Immutable` on single-field wrappers.
