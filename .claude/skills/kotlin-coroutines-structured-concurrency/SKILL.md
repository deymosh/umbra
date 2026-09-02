---
name: kotlin-coroutines-structured-concurrency
description: Use when writing or reviewing Kotlin code that stores CoroutineScope, launches from init/non-suspending APIs, calls runBlocking, or catches broad exceptions around suspend calls. Technique-layer skill — but read the "In Umbra" section FIRST: Umbra's @Singleton repositories all store a CoroutineScope by established convention, which looks like this skill's central anti-pattern but is a deliberate, consistent, existing architecture — don't flag it as a bug without reading that section.
---

# Kotlin coroutines: structured concurrency

## Core principle

A well-structured coroutine is a self-contained unit of asynchronous work — single entry, single exit, scoped to a lifecycle known at the call site.

**Scopes should usually be tied to the caller's lifecycle, not stored as a property on the callee.** A stored `CoroutineScope` is a strong review signal: the class must prove it owns cancellation, error reporting, restart behavior, and lifecycle. Most repositories, managers, use cases, and data sources cannot prove that, so the generic guidance is: expose `suspend` APIs instead, and let the caller own the scope.

**Read "In Umbra" below before applying this generically** — Umbra's actual architecture takes a different, but still deliberate, position on this exact question for `@Singleton` classes.

## The silent-cancellation bug

The reason an unowned `CoroutineScope` property is dangerous in the general case: once a scope is cancelled, every future `launch` on it silently completes as cancelled — no exception, no log, nothing. This is one of the hardest coroutine bugs to diagnose, and it shows up when a class holds a long-lived reference to a lifecycle it doesn't own — e.g., a request-scoped or screen-scoped class holding a scope that outlives its own usefulness, or a scope nobody is responsible for cancelling at all.

## Anti-patterns and fixes (generic guidance)

### `init`-block launches

```kotlin
// ❌ construction-time side effect, unbounded work, caller can't await/observe errors
class UserSession(private val scope: CoroutineScope, private val api: Api) {
    init { scope.launch { _user.value = api.load() } }
}
```

### Fire-and-forget from a non-UI, non-owning class

```kotlin
// ❌ caller has no idea what happened, no cancellation, no error path
class AnalyticsClient(private val scope: CoroutineScope, private val api: Api) {
    fun track(event: Event) { scope.launch { api.send(event) } }
}
```

### Swallowing `CancellationException`

A `catch` around a `suspend` call that matches `CancellationException` — directly, or through `Exception`/`Throwable` — and doesn't rethrow, turns cancellation into silent success.

```kotlin
// ❌ matches CancellationException too, never rethrows
try { api.load() } catch (e: Exception) { logger.warn("load failed", e) }

// ✅ separate catch first
try { api.load() }
catch (e: CancellationException) { throw e }
catch (e: Exception) { logger.warn("load failed", e) }
```

The common carve-out: an intentionally local timeout catching your own `withTimeout`'s `TimeoutCancellationException` and converting it to a domain result — keep that catch narrow and close to the `withTimeout` call.

### `runBlocking`

Parks the current thread until the lambda finishes — wrong inside suspend-capable or lifecycle-scoped application paths. Fixes: make the function `suspend`; in tests use `runTest`; Android's `ContentProvider` members (no way to suspend them) are a genuine carve-out.

## The UI ↔ state-holder boundary (this one IS fine)

UI frameworks are non-suspending. A ViewModel's job is to absorb non-suspending UI events and translate them into scoped async work. This is **not** the fire-and-forget anti-pattern:

```kotlin
// ✅ state holder absorbs a non-suspending UI event onto its own lifecycle-bound scope
class FavouritesViewModel(private val repo: FavouritesRepository) : ViewModel() {
    fun onToggleFavourite(item: Item) {
        viewModelScope.launch { repo.toggleFavourite(item) }
    }
}
```

All three must hold: (1) it's a ViewModel/state holder for a UI surface, not a repository/manager/use case; (2) the scope is lifecycle-bound (`viewModelScope`), not an injected long-lived scope; (3) the caller really is a UI event.

## Quick reference

| Symptom | Anti-pattern | Fix |
|---|---|---|
| `init { scope.launch { ... } }` | Construction-time launch | `suspend fun init()`/`login()`, caller awaits |
| `fun foo() { scope.launch { ... } }` on a repository/manager/use case | Fire-and-forget from non-UI class | `suspend fun foo()` (generic guidance — see In Umbra for the actual established pattern here) |
| `fun onClick() { viewModelScope.launch { ... } }` on a ViewModel | UI ↔ state-holder boundary — fine | Keep as-is |
| `try { suspendCall() } catch (e: Exception) { … }` with no rethrow | Swallowed cancellation | `catch (e: CancellationException) { throw e }` first |
| `runBlocking { … }` inside suspend-capable app code | Thread-blocking bridge | Make caller `suspend`, or use a lifecycle scope at the boundary |
| `runBlocking { … }` in a test | Real-time bridging | Use `runTest { … }` |

## When NOT to apply

- UI state holders absorbing UI events (ViewModels) — see above.
- Already-suspending APIs.
- Tests using `TestScope` deliberately.

## In Umbra — read this before flagging a stored scope as a bug

Every `@Singleton` repository/manager in Umbra stores its own `CoroutineScope`, by consistent, established convention — this is the dominant shape in the data layer, not an isolated smell:

```
ContactListRepositoryImpl.kt   : repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
BroadcastRepositoryImpl.kt     : repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
EventRepositoryImpl.kt         : repoScope        = CoroutineScope(SupervisorJob() + Dispatchers.Default)
FeedRepositoryImpl.kt          : repoScope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
MuteListRepositoryImpl.kt      : repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
PinListRepositoryImpl.kt       : repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
RelayRepositoryImpl.kt         : repoScope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
UserRepositoryImpl.kt          : repoScope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
TrackReferencedAuthorUseCase.kt: scope            = CoroutineScope(SupervisorJob() + Dispatchers.IO)
NostrSessionManager.kt         : scope            = CoroutineScope(SupervisorJob() + Dispatchers.IO)
TorRuntimeManager.kt           : scope            = CoroutineScope(SupervisorJob() + Dispatchers.IO)
UmbraNostrClient.kt            : clientScope      = CoroutineScope(SupervisorJob() + Dispatchers.Default)
UrlPrefetcher.kt / ImagePrefetcher.kt: scope       = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

Why this is a deliberate architecture choice, not the anti-pattern: every one of these is a Hilt `@Singleton` — its lifecycle **is** the process lifecycle, matching the standard carve-out for "application-scoped singletons that map directly to an application lifecycle." The scope is never cancelled during normal operation (there's no `applicationScope` injected from a DI module to point at instead — each class builds its own, which is the one place this diverges from the "inject `Application.applicationScope` explicitly" refinement the generic guidance suggests). Every instance consistently pairs `SupervisorJob()` with a real dispatcher (`IO` for I/O-bound repositories, `Default` for CPU/relay-frame-processing classes) — no bare `CoroutineScope(Dispatchers.X)` without a `SupervisorJob` in the singleton layer.

**What to actually do with this knowledge:**
- **Don't propose converting an existing singleton's `scope.launch { }` calls to `suspend fun` as a drive-by fix.** That's a cross-cutting architecture change affecting 13+ classes and every call site — out of scope for anything short of a deliberate, discussed refactor, and it would fight the codebase's established, consistent pattern (CLAUDE.md: "don't design for hypothetical future requirements... three similar lines is better than a premature abstraction" cuts the other way here too — consistency with 13 existing instances beats introducing a 14th shape).
- **New `@Singleton` repositories/managers should follow the same convention** (`private val scope/repoScope/repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO_or_Default)`) for consistency with the rest of the data layer, not invent a `suspend`-API-only shape that stands alone.
- **This carve-out is for `@Singleton` classes only.** A non-singleton class (screen-scoped, request-scoped, or anything with a shorter lifecycle than the process) storing its own scope is still the real anti-pattern this skill describes — that class needs to either become `suspend`-only or take an explicit externally-owned scope.
- **§6 (swallowing `CancellationException`) and §7 (`runBlocking`) still apply at full strength** — those aren't touched by the singleton-scope convention above; they're about individual `catch`/`runBlocking` call sites, not scope ownership.
- **The one outlier worth knowing about:** `UmbraApp.kt:47` fires `CoroutineScope(Dispatchers.Default).launch { entryPoint.nostrSessionManager().start() }` from `Application.onCreate()` — a genuine one-shot Hilt-graph prewarm (documented in-file), not a stored/reused scope, and *not* paired with `SupervisorJob()` like every singleton's own scope is. It's a deliberate, explicit, named launch site (not hidden in an `init{}`), so it's closer to this skill's "Pattern 3" carve-out than a bug — but if you're touching that file, matching the `SupervisorJob()` convention used everywhere else in the codebase for consistency is a reasonable opportunistic fix, not a required one.

## Related

- [`kotlin-flow-state-event-modeling`](../kotlin-flow-state-event-modeling/SKILL.md) — `StateFlow`, `SharedFlow`, `stateIn`/`shareIn`, one-shot events.
- [`umbra-coroutines`](../umbra-coroutines/SKILL.md) — Umbra's actual WebSocket-to-Flow bridge, debounce/conflate/flowOn usage, and where each operator lives.
