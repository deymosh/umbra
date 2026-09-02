---
name: compose-side-effects
description: Use when writing or reviewing Jetpack Compose code with LaunchedEffect, DisposableEffect, SideEffect, rememberCoroutineScope, rememberUpdatedState, snapshotFlow, snackbar, navigation, focus requests, analytics, or event Flow collection. Technique-layer skill, framework-generic — grounded in Umbra's AmberSignEffect/AppSessionEffects.
---

# Compose: side effects

## Core principle

Composable bodies describe UI. They can be recomposed, skipped, or abandoned. Work that changes the outside world belongs in an effect API whose lifecycle matches the work.

## Pick the smallest effect

| Need | API |
|---|---|
| Publish Compose state to non-Compose code after every successful recomposition | `SideEffect` |
| Register/unregister a listener, callback, observer, or resource | `DisposableEffect(keys...)` |
| Run suspending, deferred, or keyed one-shot work | `LaunchedEffect(keys...)` |
| Launch suspending work from a user event callback | `rememberCoroutineScope()` |
| Convert Compose snapshot reads into a Flow inside a coroutine | `snapshotFlow { ... }` inside `LaunchedEffect` |

## Effect keys

Keys define restart identity. When any key changes, the old effect is cancelled/disposed and a new one starts.

```kotlin
// ✅ Restart collection when userId changes
LaunchedEffect(userId) {
    repository.events(userId).collect { event -> handle(event) }
}

// ❌ Unit hides a changing input; collection keeps using the first userId
LaunchedEffect(Unit) {
    repository.events(userId).collect { event -> handle(event) }
}
```

Use stable, semantic keys — the thing whose lifecycle the effect follows (`userId`, `screenId`, `lifecycleOwner`, `focusRequester`), not a broad object when only one property matters, and not a changing lambda unless restarts on every lambda change are actually wanted.

## Avoid stale captures

For long-running effects that should not restart but need the latest callback or value, use `rememberUpdatedState`.

```kotlin
@Composable
fun Timeout(onTimeout: () -> Unit) {
    val latestOnTimeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(Unit) {
        delay(1_000)
        latestOnTimeout()
    }
}
```

Do not use `rememberUpdatedState` to avoid choosing proper keys. If a changed value should restart the work, make it a key instead:

```kotlin
// BAD: userId changes should restart the collection, not update a captured value.
val latestUserId by rememberUpdatedState(userId)
LaunchedEffect(Unit) { repository.events(latestUserId).collect { handle(it) } }

// GOOD: the collection lifecycle follows userId.
LaunchedEffect(userId) { repository.events(userId).collect { handle(it) } }
```

`rememberUpdatedState` also does not make render state "non-recomposing." For frame-rate values, see [`compose-state-deferred-reads`](../compose-state-deferred-reads/SKILL.md).

## Collecting Flow

Use `LaunchedEffect` for **side-effect/event flows**: snackbars, navigation events, analytics events, focus commands — streams where each emission triggers imperative work.

```kotlin
LaunchedEffect(events) {
    events.collect { event -> snackbarHostState.showSnackbar(event.message) }
}
```

Do not collect render state imperatively just to mutate local state — that's the state-holder/UI split, covered in [`compose-state-holder-ui-split`](../compose-state-holder-ui-split/SKILL.md). On Android, prefer lifecycle-aware collection (`collectAsStateWithLifecycle()`).

For Compose state reads, use `snapshotFlow`:

```kotlin
LaunchedEffect(listState) {
    snapshotFlow { listState.firstVisibleItemIndex }
        .distinctUntilChanged()
        .collect { index -> analytics.visibleIndex(index) }
}
```

## User events

Use `rememberCoroutineScope()` when a click or gesture starts suspending work:

```kotlin
@Composable
fun SaveButton(snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    Button(onClick = { scope.launch { snackbarHostState.showSnackbar("Saved") } }) { Text("Save") }
}
```

Avoid "event flag" state just to trigger a `LaunchedEffect` — the click already is the event.

## Registration and cleanup

Use `DisposableEffect` for paired setup/teardown; every registration path should have a matching `onDispose` cleanup path.

## Common mistakes

| Mistake | Fix |
|---|---|
| Network request directly in the composable body | Move to a ViewModel/state holder; use `LaunchedEffect` only for UI-owned keyed work |
| `LaunchedEffect(Unit)` captures a changing `id` | Key by `id`, or use `rememberUpdatedState` if it must not restart |
| Long-lived effect invokes an old callback after recomposition | Wrap the callback with `rememberUpdatedState` and call the wrapper inside the effect |
| Listener added in `LaunchedEffect` with no cleanup | Use `DisposableEffect` |
| Launching from click by setting `shouldShowSnackbar = true` | Use `rememberCoroutineScope()` in the click callback |

## Red flags during review

- "This only runs once" about code in a composable body.
- `LaunchedEffect(Unit)` in a function with changing parameters.
- A flow chain inside an effect with no terminal collection.
- Effect keys chosen to silence lint instead of model lifecycle.

## In Umbra

`ui/components/AmberSignEffect.kt` is the concrete instance of "run suspending, keyed, one-shot work and clean it up" — it's the `LaunchedEffect` that awaits an Amber sign result correlated by `AmberRequestCoordinator`'s `CompletableDeferred`, so its key needs to be the pending-request id, not `Unit`, or a stale sign result from a previous request could resolve into the wrong screen's callback. `ui/AppSessionEffects.kt` is where the single `ActivityResultLauncher` for Amber intents is registered/unregistered app-root (via `registerLauncher`/`unregisterLauncher`) — a `DisposableEffect` pairing by construction, since a leaked or double-registered launcher would break every subsequent Amber round-trip. When adding a new screen-scoped subscription (see [`umbra-relay-client`](../umbra-relay-client/SKILL.md)), the same key-discipline applies: key the `LaunchedEffect`/`init{}` collection on the pubkey or filter it actually depends on, not `Unit`.

## Related

- [`compose-state-holder-ui-split`](../compose-state-holder-ui-split/SKILL.md) — where state-holder vs UI split applies when passing state/callbacks across boundaries.
- [`compose-state-deferred-reads`](../compose-state-deferred-reads/SKILL.md) — frame-rate `State` reads that shouldn't happen in composition.
