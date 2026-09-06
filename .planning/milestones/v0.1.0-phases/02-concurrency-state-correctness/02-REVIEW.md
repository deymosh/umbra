---
phase: 02-concurrency-state-correctness
reviewed: 2026-09-04T12:00:00Z
depth: standard
files_reviewed: 16
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
  - app/src/test/java/com/umbra/app/util/coroutines/CancellableRunCatchingTest.kt
findings:
  critical: 0
  warning: 2
  info: 2
  total: 4
status: issues_found
---

# Phase 02: Code Review Report (re-review, iteration 3 — final pass)

**Reviewed:** 2026-09-04T12:00:00Z
**Depth:** standard
**Files Reviewed:** 16
**Status:** issues_found

## Summary

This is the third and final `--auto` review pass for Phase 2. Iteration 2's fixes were checked against
the current source (not against the stale `02-REVIEW.iter3.md`/`02-REVIEW-FIX.iter3.md` artifacts
already sitting in this phase directory, which describe the pre-iteration-2 state and were
superseded by commits `34cddc5`, `2992ec6`, `3a11a13`, `d0d71c2`): `InteractionActionsCoordinator.
mirrorMuteIntoActiveFilter` now correctly uses `runCatchingCancellable`; `RelayCrudCoordinator.
saveRelay`'s merge branch now re-reads `relayRepository.getRelayById` fresh *inside* the per-relay
mutex before computing the OR-merge, closing the specific stale-base bug WR-02 flagged;
`AtomicJobScheduling.launchReplacing`'s docstring was corrected to stop overclaiming ordering under
genuinely concurrent unsynchronized callers, which is an accurate, honest resolution of the prior
WR-03 concern rather than a code change; `NostrSessionManager`'s two `onFailure` handlers now log via
`logger.e(throwable)`, preserving the stack trace; `CancellableRunCatchingTest.kt` exists and correctly
asserts both of `runCatchingCancellable`'s two behaviors (`CancellationException` propagates via
`@Test(expected = ...)`, a plain exception is captured into `Result.failure` with the exact exception
instance preserved) plus a success-path case — this is real, correct assertion coverage, not a
tautological test.

The one fix worth the closest scrutiny — `ownProfileBootstrapMutex` guarding
`maybeBootstrapOwnProfile`'s check-then-act sequence — does not introduce a deadlock and does not hold
the lock across a suspension point it shouldn't (`bootstrapOwnProfileUseCase.start()`/`stop()` are both
synchronous fire-and-forget calls; the only suspend call made while holding the lock is
`userRepository.getRelayList`). However, it only guards *calls into* `maybeBootstrapOwnProfile` itself
— it does not close the race the class's own doc comment implies is closed, because
`stopOwnProfileBootstrap()` (which mutates the exact same fields the mutex protects) is still invoked
from two places that never acquire `ownProfileBootstrapMutex`. See WR-01 below. A second, unrelated
gap in the same file (`saveRelay`'s new-relay/URL-collision branch) was found by tracing the merge
branch's surrounding control flow rather than the merge computation WR-02 already fixed. See WR-02.

## Warnings

### WR-01: `ownProfileBootstrapMutex` only guards `maybeBootstrapOwnProfile`'s own body — `stopOwnProfileBootstrap()` is still called unguarded from two other places that mutate the same fields

**File:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt:296-312, 437-468`
**Issue:** The mutex added in commit `3a11a13` correctly serializes the two documented concurrent
`reconcile()` entry points *when both go through `maybeBootstrapOwnProfile`*. But `stopOwnProfileBootstrap()`
— which unconditionally mutates `ownProfileBootstrapPubkey` (a plain, non-atomic `@Volatile var`) and
`ownProfileBootstrapWatcherJob`, and calls `bootstrapOwnProfileUseCase.stop()` — is also called from two
places that never take `ownProfileBootstrapMutex`:

1. `NostrSessionManager.stop()` (line 308), a completely unguarded direct call, reachable any time the
   session is stopped (e.g. logout) while a `reconcile()` call is still mid-flight inside
   `maybeBootstrapOwnProfile`'s locked block on a different thread (`stop()` calls `bootstrapJob?.cancel()`
   just before this, but that cancellation is cooperative and does not synchronously halt an
   already-running `reconcile()` invocation).
2. The trailing `stopOwnProfileBootstrap()` at the end of the watcher job's own lambda (line 458),
   which runs *after* `ownProfileBootstrapWatcherJob.launchReplacing(scope) { ... }` returns from inside
   the mutex — i.e. on its own separately-scheduled coroutine, entirely outside the `withLock` block that
   launched it. If that job reaches this final statement in the narrow synchronous window between its last
   suspension point (`delay`/`getRelayList`) and completion, it runs concurrently with (or immediately
   after) a different `maybeBootstrapOwnProfile` invocation that is, under the same mutex, actively
   assigning `ownProfileBootstrapPubkey` to a *new* pubkey and starting a *new* channel/watcher for it.

Concretely: session bootstraps for pubkey X (`ownProfileBootstrapPubkey = X`, watcher `W1` running).
Identity switches to Y; a `reconcile()` call takes the mutex, cancels `W1` via `stopOwnProfileBootstrap()`,
sets `ownProfileBootstrapPubkey = Y`, calls `bootstrapOwnProfileUseCase.start(Y)`, and installs watcher
`W2` — all correctly serialized. But if `W1` was between its last `getRelayList()` suspend call and its own
trailing `stopOwnProfileBootstrap()` line at the moment `.cancel()` was requested, cooperative cancellation
does not stop that already-in-flight synchronous tail: `W1` can still execute `stopOwnProfileBootstrap()`
*after* the locked path already finished, reading `ownProfileBootstrapPubkey == Y` (non-null, so it
proceeds), cancelling the just-installed `W2` via the shared `AtomicReference`, setting
`ownProfileBootstrapPubkey = null`, and calling `bootstrapOwnProfileUseCase.stop()` — tearing down the
brand-new channel for Y that was supposed to survive. The same field (`ownProfileBootstrapPubkey`) is also
directly exposed to `stop()`'s unguarded call: since `start()` never resets it, a race that leaves it
non-null after an unguarded `stop()` (instead of the intended `null`) means the *next* login as the same
pubkey can see `ownProfileBootstrapPubkey == pubkey` on the first `maybeBootstrapOwnProfile` call for the
new session and skip starting the bootstrap channel entirely, even though `stop()`'s own
`eventRepository.disconnectFromAll()` already tore down every real subscription — i.e. cold-start hydration
for that re-login silently never happens until the process restarts.
**Fix:** Route all three mutators of this state through the same lock. The watcher-job tail call is the
easier fix — move it inside a fresh `ownProfileBootstrapMutex.withLock { }` (it's already running in a
suspend context via `launchReplacing`'s lambda, so this doesn't change its threading model):
```kotlin
ownProfileBootstrapWatcherJob.launchReplacing(scope) {
    val deadline = System.currentTimeMillis() + OWN_PROFILE_BOOTSTRAP_MAX_MS
    while (isActive && System.currentTimeMillis() < deadline) {
        delay(OWN_PROFILE_BOOTSTRAP_POLL_MS)
        if (userRepository.getRelayList(pubkey)?.getOutboxRelays()?.isNotEmpty() == true) break
    }
    ownProfileBootstrapMutex.withLock { stopOwnProfileBootstrap() }
}
```
For `stop()`, either make `stop()` itself suspend (it already isn't, per `NostrSessionController`'s
interface — a bigger change) or give `stopOwnProfileBootstrap()`'s mutations a non-suspending
`Mutex.tryLock`-guarded fast path, or simplest: since `stop()` already calls
`eventRepository.disconnectFromAll()` unconditionally right after, have `stop()` reset
`ownProfileBootstrapPubkey`/`ownProfileBootstrapWatcherJob` via the same atomic/volatile primitives it
already uses for `retryJob`/`userBackfillJob` (`getAndSet(null)?.cancel()`), and treat a stale non-null
`ownProfileBootstrapPubkey` left behind by a lost race as tolerable only if `start()` is also changed to
unconditionally reset it to `null` at the top of a fresh session — closing the "re-login as the same
pubkey silently skips bootstrap" half of this bug even if the narrower double-teardown race isn't fully
eliminated.

### WR-02: `RelayCrudCoordinator.saveRelay`'s new-relay branch still decides "does this relay already exist" from the stale `state.value.relays` mirror, independent of the merge-computation fix

**File:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt:82-130`
**Issue:** The fix applied for the prior WR-02 finding correctly re-reads the *merge base* from
`relayRepository.getRelayById` inside the lock once `existingRelay != null` is already known. But the
`existingRelay != null` decision itself — whether this relay URL is treated as new-vs-existing at all — is
still made from the throttled UI mirror, before the lock is even considered:
```kotlin
val existingRelay = state.value.relays.firstOrNull {
    it.url.equals(sanitizedRelay.url, ignoreCase = true)
}
if (existingRelay != null) {
    relayRoleMutexes.computeIfAbsent(existingRelay.id) { Mutex() }.withLock { /* correctly re-reads fresh now */ }
} else {
    // No lock at all — a freshly generated id "can't race an in-flight role toggle," per the comment,
    // but this branch is reachable even when the relay DOES already exist in the repository, just not
    // yet in state.value.relays.
    val newRelay = sanitizedRelay.copy(id = RelayIdGenerator.create())
    addRelayUseCase(newRelay)
}
```
`state.value.relays` is populated by `RelayConfigViewModel.observeRelays()`'s 300ms-throttled collector —
the same staleness this file's own `updateRelayRole` doc comment (line ~355) warns about for the merge
case. If a relay with this URL was added moments earlier (e.g. a second `saveRelay(relay.copy(id = ""))`
call for the same URL fired in quick succession — a double-tap on the add-relay dialog's save button, or a
concurrent NIP-65 sync path adding the same URL via `RelayRepositoryImpl`) and hasn't yet been reflected in
`state.value.relays`, `existingRelay` is `null` even though the repository already has a row for that URL.
This routes into the `else` branch, which has no mutex and no repository-level existence check —
`AddRelayUseCase`/`RelayRepository.addRelay` (see `RelayUseCases.kt`) perform no URL-uniqueness check, only
a plain insert keyed by the generated id — producing two `Relay` rows with the same normalized URL and
different ids. Every URL-keyed lookup this codebase relies on elsewhere (`RelayCrudCoordinator.saveRelay`'s
own merge path next time, connection-state/issue matching by URL, etc.) would then have two candidates to
choose between.
**Fix:** Re-check existence against the repository, not just `state.value.relays`, before committing to the
"add new" branch — e.g. resolve `relayRepository.getAllRelays().first()` (or a URL-indexed lookup, if one
exists) inside the same per-URL/per-id critical section rather than trusting the throttled mirror as the
sole source of truth for "is this a genuinely new relay":
```kotlin
val freshRelays = relayRepository.getAllRelays().first()
val existingRelay = freshRelays.firstOrNull { it.url.equals(sanitizedRelay.url, ignoreCase = true) }
    ?: state.value.relays.firstOrNull { it.url.equals(sanitizedRelay.url, ignoreCase = true) }
```

## Info

### IN-01: The same throwable-discarding log pattern iteration 2 just fixed in `NostrSessionManager` (IN-02) is still present, unfixed, in `InteractionActionsCoordinator.kt`

**File:** `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt:97, 116`
**Issue:** Commit `d0d71c2` fixed exactly this pattern (`logger.d { "...${scrubThrowableMessageForLogs(e)}" }`
discarding the stack trace instead of `logger.e(e) { }`) in two places in `NostrSessionManager.kt`. The
identical pattern is present, unaddressed, in the file this phase also touched for the
`runCatchingCancellable` migration:
```kotlin
} catch (e: Exception) {
    logger.d { "Error requesting signed event: ${scrubThrowableMessageForLogs(e)}" }
    null
}
...
publishSignedEventUseCase(signedEventJson).onFailure { e ->
    logger.d { "Error publishing event: ${scrubThrowableMessageForLogs(e)}" }
    onFailure(e)
}
```
This is pre-existing (from the initial commit, not introduced this phase), so it's an info-level
completeness gap rather than a regression, but it's the exact same bug class this phase's own fixer
already spent a commit correcting elsewhere in a file it was actively editing.
**Fix:** `logger.e(e) { "Error requesting signed event: ${scrubThrowableMessageForLogs(e)}" }` and
`logger.e(e) { "Error publishing event: ${scrubThrowableMessageForLogs(e)}" }`, matching
`RelayConfigViewModel:370`'s and `NostrSessionManager`'s now-corrected pattern.

### IN-02: No regression test exercises `RelayCrudCoordinator.saveRelay`'s merge-branch fresh-read fix

**File:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt`
**Issue:** `RelayCrudCoordinatorTest` has strong concurrency coverage for the `updateRelayRole` chokepoint
(`setOutboxEnabled`/`setSearchEnabled` racing on the same vs. different relay ids), but nothing calls
`saveRelay` at all — the WR-02-fixed merge branch (freshly re-reading `relayRepository.getRelayById`
inside the lock before computing the OR-merge) has zero test coverage, positive or negative. This mirrors
the already-accepted, deliberately-deferred `LOG-44` gap for `NostrSessionManager`/`RelayConfigViewModel`
dedicated coverage, but is scoped narrowly enough (one coordinator method, one existing test file) that it
wasn't obviously part of that same deferral.
**Fix:** Add a `RecordingRelayRepository`-based test that seeds an existing relay, gates `updateRelay` (or
`getRelayById`) so a concurrent `setOutboxEnabled(false)` can land between `saveRelay`'s
`state.value.relays` snapshot and its lock acquisition, then asserts the merge result reflects the
concurrent disable rather than reverting it via the stale OR-merge base.

---

_Reviewed: 2026-09-04T12:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
