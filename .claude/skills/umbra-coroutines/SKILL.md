---
name: umbra-coroutines
description: Coroutine scopes and structured concurrency (stored scopes, init launches, runBlocking, swallowed cancellation), the WebSocket-to-Flow bridge, debounce/conflate/flowOn in data-layer code.
---

# Coroutines in Umbra

## Scope ownership

- **ViewModels**: use `viewModelScope.launch { }` for UI events. This is the correct UI ↔ state-holder boundary, not fire-and-forget.
- **`@Singleton` repositories/managers own their scope by convention**: `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO /* or Default */)`.
  - Examples: `EventRepositoryImpl`, `FeedRepositoryImpl`, `UserRepositoryImpl`, `RelayRepositoryImpl`, the Mute/Pin/ContactList repositories, `BroadcastRepositoryImpl`, `NostrSessionManager`, `TorRuntimeManager`, `UmbraNostrClient.clientScope`, `TrackReferencedAuthorUseCase`, `UrlPrefetcher`, `ImagePrefetcher`.
  - Their lifecycle is the process lifecycle. New singletons follow the same shape.
  - Don't "fix" existing ones into `suspend` APIs as a drive-by.
- **Any non-singleton (screen- or request-scoped) class storing its own scope is a real bug.** It must be `suspend`-only or take an externally owned scope. Once a stored scope is cancelled, every later `launch` silently does nothing.
- `UmbraApp.onCreate` launches a one-shot prewarm on `CoroutineScope(Dispatchers.Default)`. That is deliberate. Adding `SupervisorJob()` there is optional.

## Always bugs, anywhere

- **Swallowed cancellation**: `catch (e: Exception)` / `Throwable` around a suspend call without rethrowing. Add `catch (e: CancellationException) { throw e }` first. The only carve-out is a narrow catch of your own `withTimeout`.
- **`runBlocking`** in app code: make the caller `suspend`. Tests use `runTest`.
- **`init { scope.launch { } }`** in a non-singleton: use an explicit `suspend fun` the caller awaits.

## WebSocket → Flow bridge

`UmbraNostrClient` uses a plain `okhttp3.WebSocketListener` (`WebSocketListenerImpl`). Its callbacks feed class-scoped `MutableSharedFlow`s (`_eventFlow`, and `_relayIssueFlow` with its deliberate replay 3000 for multiple consumers). New WebSocket-driven streams extend that shape. No `callbackFlow` exists in the codebase, so don't introduce one without a reason.

## Operators in real use

- **`debounce` + `distinctUntilChanged`**: `NostrSessionManager` coalesces relay-set bursts into one reconcile pass.
- **`conflate()`**: `FeedViewModel`'s event subscription. It's safe *only* because `EventRepositoryImpl` already persisted upstream. Some `EventRepositoryImpl` stages deliberately don't conflate. Check that dropped emissions don't matter before adding it.
- **`flowOn(Dispatchers.IO)`**: goes on Room-backed flows that decode JSON per row in `.map {}`. Leaving it out is a past bug shape (main-thread decode scaling with row count).
- **Buffering**: done with `MutableSharedFlow(extraBufferCapacity = ...)`. For a single-consumer one-shot event, a `Channel` can fit better; see `umbra-kotlin-patterns`.
