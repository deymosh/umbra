---
phase: 02-concurrency-state-correctness
reviewed: 2026-09-04T00:00:00Z
depth: standard
files_reviewed: 15
files_reviewed_list:
  - app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt
  - app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt
  - app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt
  - app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt
  - app/src/main/java/com/umbra/app/ui/feed/FeedViewModel.kt
  - app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt
  - app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt
  - app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt
  - app/src/main/java/com/umbra/app/util/coroutines/CancellableRunCatching.kt
  - app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt
  - app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt
  - app/src/test/java/com/umbra/app/domain/feed/FeedFilterTest.kt
  - app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt
  - app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt
  - app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt
findings:
  critical: 0
  warning: 4
  info: 2
  total: 6
status: issues_found
---

# Phase 02: Code Review Report (re-review)

**Reviewed:** 2026-09-04T00:00:00Z
**Depth:** standard
**Files Reviewed:** 15
**Status:** issues_found

## Summary

This is a follow-up review after a fixer pass addressed CR-01, CR-02, CR-03, WR-01, WR-02, WR-03,
WR-04, and IN-01 from the prior 02-REVIEW.md (now overwritten). WR-05 (missing NostrSessionManager/
RelayConfigViewModel test coverage) was deliberately left unfixed and is tracked as LOG-44; it is not
re-flagged here.

The fixes that were applied are correctly implemented as far as they go: `runCatchingCancellable`
(`CancellableRunCatching.kt`) correctly rethrows `CancellationException` and is a valid `inline`
non-suspend wrapper for suspend-lambda call sites; `RelayCrudCoordinator.removeRelayRole` now
correctly routes through the `updateRelayRole` chokepoint (fresh `relayRepository.getRelayById` read
under the per-relay-id mutex) instead of its previous bypass; `saveRelay`/`deleteRelay` now acquire
the same per-relay-id mutex as role toggles; `EventIngestCache.storeEventLocked`'s extraction
preserves the original replaceable-race/eviction/indexing-order invariants and is exercised by both
the pre-existing and new `cacheRepostTarget` regression tests; the `@Volatile` fields in
`NostrSessionManager` are each written via simple, non-compound assignments, so no lost-update in the
classic read-modify-write sense is possible from the annotation itself.

However, digging into what these fixes actually cover versus what they were designed to prevent
surfaced four residual gaps, detailed below — one leftover call site that reintroduces the exact bug
class `runCatchingCancellable` was built to eliminate, one merge path in `RelayCrudCoordinator` that
still bases a write on a stale, pre-lock snapshot despite now holding the "right" lock, one latent
ordering gap in the shared `launchReplacing` helper that the new concurrency test doesn't actually
exercise, and one place where `@Volatile` alone doesn't cover the compound check-then-act sequences
built on top of it in `NostrSessionManager.reconcile()`.

## Warnings

### WR-01: `InteractionActionsCoordinator.mirrorMuteIntoActiveFilter` still uses stdlib `runCatching`, swallowing `CancellationException`

**File:** `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt:140-149`
**Issue:** This phase introduced `runCatchingCancellable` specifically because the stdlib
`runCatching` catches `CancellationException` (a `Throwable` subtype) and turns structured
cancellation into an ordinary `Result.failure`, per `CancellableRunCatching.kt`'s own doc comment:
"Every call site in this codebase that wraps a suspend call in `runCatching` inside a scope-launched
coroutine should use this instead of the stdlib version." `mirrorMuteIntoActiveFilter` is exactly
such a call site and was not migrated:

```kotlin
suspend fun mirrorMuteIntoActiveFilter(
    target: String,
    mute: Boolean,
    resolveActiveFilter: suspend () -> FeedFilter?
) {
    runCatching {
        val currentFilter = resolveActiveFilter() ?: return@runCatching
        ...
        feedRepository.updateMutedAuthors(currentFilter.id, updated)
    }
}
```

Both callers (`FeedViewModel.muteUser`, `ProfileViewModel.toggleMute`) invoke this from inside
`onSigned`, which itself runs inside `requestSignAndPublish`'s `scope.launch { ... }` — a genuinely
cancellable coroutine (cancelled e.g. when the owning ViewModel is cleared while a mute/unmute is
in flight). If cancellation occurs while suspended in `resolveActiveFilter()` or
`feedRepository.updateMutedAuthors(...)`, the plain `runCatching` here catches and discards the
`CancellationException` instead of rethrowing it, silently converting the cancellation into
"finished normally." This is the identical bug class the rest of this phase's fix specifically
targeted, and this file is the one place in the reviewed set it was still needed.
**Fix:**
```kotlin
import com.umbra.app.util.coroutines.runCatchingCancellable
...
suspend fun mirrorMuteIntoActiveFilter(
    target: String,
    mute: Boolean,
    resolveActiveFilter: suspend () -> FeedFilter?
) {
    runCatchingCancellable {
        val currentFilter = resolveActiveFilter() ?: return@runCatchingCancellable
        val updated = if (mute) currentFilter.mutedPubkeys + target else currentFilter.mutedPubkeys - target
        feedRepository.updateMutedAuthors(currentFilter.id, updated)
    }
}
```

### WR-02: `RelayCrudCoordinator.saveRelay`'s merge branch bases its write on a stale pre-lock snapshot, not a fresh read

**File:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt:82-116`
**Issue:** `updateRelayRole` (the chokepoint `removeRelayRole` and the five `set*Enabled` setters all
now correctly route through) explicitly reads its base relay from `relayRepository.getRelayById`
*inside* the per-relay-id mutex, with a doc comment explaining exactly why: `state.relays` is only
"repopulated by RelayConfigViewModel.observeRelays()'s 300ms-throttled collector," so a second
serialized caller reading from `state` instead of the repository "would still map from a pre-write
snapshot and re-lose the first toggle's flag even with the lock in place."

`saveRelay`'s new-relay-that-matches-an-existing-url merge path does not follow this rule it states
elsewhere in the same file. `existingRelay` is captured from `state.value.relays` — the same
throttled UI mirror the chokepoint's own doc comment warns against — *before* the per-relay-id mutex
is acquired, and the merge write inside the lock uses that stale object as its base rather than
re-reading from `relayRepository`:

```kotlin
val existingRelay = state.value.relays.firstOrNull { it.url.equals(sanitizedRelay.url, ignoreCase = true) }
if (existingRelay != null) {
    relayRoleMutexes.computeIfAbsent(existingRelay.id) { Mutex() }.withLock {
        updateRelayUseCase(
            existingRelay.copy(
                ...
                isReadEnabled = existingRelay.isReadEnabled || sanitizedRelay.isReadEnabled,
                ...
            )
        )
    }
}
```

Because every role flag is merged via `existingRelay.X || sanitizedRelay.X` (OR only ever turns a
flag on), a concurrent role-*disable* (e.g. `setInboxEnabled(id, false)`, or the anonymous-session
enforcement in `RelayConfigViewModel.enforceAnonymousRelayPolicyIfNeeded`) that lands between the
stale snapshot being captured and this write acquiring the lock is silently reverted: the OR merge
re-derives `true` from the stale `existingRelay` value even though the concurrent write already set
it to `false`. The mutex here only prevents the two writes from interleaving/corrupting each other —
it does nothing to prevent this write from being computed from data that was already out of date
before the lock was even requested. Confirmed as an untested path: `RelayCrudCoordinatorTest` only
exercises the `updateRelayRole` chokepoint's concurrency guarantee (`setOutboxEnabled`/
`setSearchEnabled`), not `saveRelay`'s merge branch.
**Fix:** Re-read the base relay from `relayRepository.getRelayById(existingRelay.id)` (falling back to
`existingRelay` only if that lookup returns null) *inside* the `withLock` block, the same way
`updateRelayRole` does, before computing the OR-merged result:
```kotlin
relayRoleMutexes.computeIfAbsent(existingRelay.id) { Mutex() }.withLock {
    val freshBase = relayRepository.getRelayById(existingRelay.id) ?: existingRelay
    updateRelayUseCase(freshBase.copy(/* merge against freshBase.X, not existingRelay.X */))
}
```

### WR-03: `AtomicReference<Job?>.launchReplacing`'s cancel-before-start ordering isn't guaranteed under genuinely concurrent, unsynchronized callers

**File:** `app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt:58-66`
**Issue:** The doc comment states the ordering `launchReplacing` exists to preserve: "the candidate
is created lazily and only `start()`ed after the displaced job has been cancelled... A non-lazy
launch would let the replacement begin executing concurrently with its predecessor's cancellation
instead of strictly after it." This holds for a *single* caller's own three-step sequence
(`getAndSet` → `previous?.cancel()` → `candidate.start()`), but the three steps are not themselves
executed as one atomic unit — only the `getAndSet` on the `AtomicReference` is atomic. When two
threads call `launchReplacing` on the same reference with no external synchronization, the following
interleaving is possible:

1. Thread A: `getAndSet(CA)` → ref is now `CA`.
2. Thread A: `previous.cancel()` (cancels whatever was displaced before A).
3. Thread A: `CA.start()` — **CA begins running now**, before B has done anything.
4. Thread B: `getAndSet(CB)` → ref is now `CB`, B's `previous == CA`.
5. Thread B: `CA.cancel()` — CA is cancelled, but it already started in step 3 and only stops at its
   next suspension point (cooperative cancellation), not immediately.
6. Thread B: `CB.start()`.

For this window, both CA's and CB's bodies can execute concurrently — exactly what the docstring
claims cannot happen. This is reachable in this codebase: `insertDebounceJob` in `EventIngestCache`
is driven by `scheduleInsert()`, called synchronously from `EventRepositoryImpl.subscribeToEvents`'s
`flatMapMerge` branches (documented elsewhere in this same file as running "on different real
threads"), and `userBackfillJob`/`ownProfileBootstrapWatcherJob` in `NostrSessionManager` are driven
from `reconcile()`, which the class's own comments acknowledge has two genuinely concurrent entry
points (the `bootstrapJob` collect loop and `retryJob`'s delayed relaunch).

Practical impact for the two current call sites is low — `EventIngestCache`'s `pendingInserts` queue
is a `ConcurrentLinkedQueue` drained via `poll()`, so an overlapping second drain just finds it empty
and no-ops; `NostrSessionManager`'s backfill anchors are read via a min/max-clamped resolver already
designed to tolerate regression from unrelated causes. But the guarantee the docstring asserts does
not actually hold under real concurrent stress, and the new concurrency test added for this helper
doesn't catch it: `AtomicJobSchedulingTest`'s `launchReplacing` race test explicitly wraps each
racer's entire `launchReplacing` call in its own `captureOrder` [`Mutex`], serializing the very
interleaving described above (per the test's own comment, this was a deliberate choice to make
`created.last()` predictable) — unlike the `launchIfIdle` race test just above it, which correctly
lets all 8 racers call the helper with no artificial serialization.
**Fix:** Either accept this as a documented, bounded-impact tradeoff (update the docstring to stop
asserting the ordering is guaranteed under concurrent unsynchronized callers, since it isn't), or
close the gap by having the caller hold the previous job's cancellation-acknowledgement before
starting the candidate — e.g. `previous?.cancelAndJoin()` inside the same suspend context before
`candidate.start()`, if `launchReplacing` is changed to a suspend function, or by serializing calls to
a given reference via its own internal lock rather than relying on `AtomicReference.getAndSet` alone.

### WR-04: `NostrSessionManager.reconcile()`'s check-then-act sequences over `@Volatile` fields aren't atomic under the class's own documented concurrent entry points

**File:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt:172-190, 378-384, 427-445`
**Issue:** The `@Volatile` doc comment (lines 163-171) correctly limits its own claim to visibility,
not atomicity: "it does not make a read-check-write sequence atomic, but every actual mutation here
is a single unconditional assignment." That's true for each field in isolation, but `reconcile()`
itself — which the class's own comments say runs from two genuinely concurrent entry points (the
`bootstrapJob` collect loop and `retryJob`'s delayed relaunch via `scheduleRetry()`) — is not
otherwise synchronized, so its multi-step decisions built on top of these fields are still exposed to
TOCTOU races across a real concurrent invocation:

```kotlin
if (!firstRelayConnectedLogged && state.relays.any { ... }) {
    firstRelayConnectedLogged = true
    ...
    logger.d { "First relay connected over Tor in ${elapsed}ms" }
}
```
Two overlapping `reconcile()` calls can both observe `firstRelayConnectedLogged == false` before
either writes `true`, producing a duplicate log line — self-acknowledged as tolerable elsewhere in
the file, but a concrete symptom of the same gap.

More consequentially, `maybeBootstrapOwnProfile`'s guard has the same shape but with a real side
effect attached:
```kotlin
if (ownProfileBootstrapPubkey == pubkey) return
stopOwnProfileBootstrap()
ownProfileBootstrapPubkey = pubkey
bootstrapOwnProfileUseCase.start(pubkey)
```
Two overlapping `reconcile()` calls that both reach this method for the same `pubkey` before either
has written `ownProfileBootstrapPubkey` can both pass the `!= pubkey` guard and both call
`bootstrapOwnProfileUseCase.start(pubkey)` — a duplicate channel start whose safety depends on that
use case being idempotent under a double `start()`, which is outside this file's scope to guarantee.
`@Volatile` alone does not close this gap; it only ensures each individual read sees the latest
write, not that the read-and-decide-and-write sequence is exclusive.
**Fix:** Either confirm and document that `bootstrapOwnProfileUseCase.start()` is safe to call twice
in a row for the same pubkey (idempotent re-subscribe), or guard `reconcile()`'s body (or at minimum
`maybeBootstrapOwnProfile`) with a `Mutex` so the two documented concurrent entry points can't
actually interleave their compound decisions, only their scheduling.

## Info

### IN-01: No dedicated unit test for `runCatchingCancellable`

**File:** `app/src/main/java/com/umbra/app/util/coroutines/CancellableRunCatching.kt`
**Issue:** This is now shared, load-bearing infrastructure (used by `NostrSessionManager` and
`RelayConfigViewModel`, and per WR-01 above should also be used by `InteractionActionsCoordinator`),
but there's no `CancellableRunCatchingTest.kt` asserting its two behaviors directly: (1) a thrown
`CancellationException` propagates instead of being captured, and (2) any other `Throwable` is
captured into `Result.failure` exactly like the stdlib version.
**Fix:** Add a small dedicated test with two cases — a block that throws `CancellationException`
inside a `runTest`/cancelled scope (asserting the exception propagates and the coroutine is
cancelled) and a block that throws a plain exception (asserting `Result.failure` is returned).

### IN-02: `Result.onFailure` handlers in `NostrSessionManager` log a scrubbed message instead of the throwable

**File:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt:321-323, 409-411`
**Issue:** Both `disableDeadRelay`'s `.onFailure { e -> logger.d { "Failed to auto-disable relay: ${scrubThrowableMessageForLogs(e)} } }` and `reconcile`'s `.onFailure { error -> logger.d { "Relay connect failed ... ${scrubThrowableMessageForLogs(error)}" } }` route the throwable through `.d { }` as a scrubbed message string rather than `.e(e) { }`, discarding the stack trace that would otherwise be available for on-device debugging (matches `RelayConfigViewModel:370`'s correct `logger.e(e) { ... }` pattern, which these two do not follow).
**Fix:** Use `logger.e(e) { "..." }` / `logger.e(error) { "..." }` in both places, keeping the scrubbed message text as the log line's content.

---

_Reviewed: 2026-09-04T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
