---
phase: 02-concurrency-state-correctness
fixed_at: 2026-09-04T00:00:00Z
review_path: .planning/phases/02-concurrency-state-correctness/02-REVIEW.md
iteration: 2
findings_in_scope: 6
fixed: 6
skipped: 0
status: all_fixed
---

# Phase 02: Code Review Fix Report

**Fixed at:** 2026-09-04T00:00:00Z
**Source review:** `.planning/phases/02-concurrency-state-correctness/02-REVIEW.md`
**Iteration:** 2

**Summary:**
- Findings in scope: 6 (0 critical, 4 warning, 2 info — `fix_scope: all`)
- Fixed: 6
- Skipped: 0

**Verification:** `./gradlew compileDebugKotlin`, `./gradlew lintDebug`, and
`./gradlew testDebugUnitTest` (full suite) all passed clean after every commit below, plus the
seven review-relevant scoped test classes (`InteractionActionsCoordinatorTest`,
`RelayCrudCoordinatorTest`, `AtomicJobSchedulingTest`, `CancellableRunCatchingTest`,
`FeedViewModelStateTest`, `EventIngestCacheTest`, `FeedFilterTest`) were run individually and
confirmed zero failures/errors. Ran in the main checkout — `workflow.use_worktrees` is `false` in
`.planning/config.json`, so no isolated worktree was created for this run; every commit below
landed directly on `gsd/v0.1.0-hardening-first-public-release`.

This is the second fixer pass in the same session, on top of iteration 1's fixes (CR-01/CR-02/
CR-03/WR-01/WR-02/WR-03/WR-04/IN-01 from the original review, now superseded by this file). WR-05
from the original review (missing `NostrSessionManager`/`RelayConfigViewModel` test coverage) was
deliberately left unfixed by iteration 1 and is tracked as LOG-44; the iteration-2 re-review did
not re-flag it, and this pass leaves it untouched.

## Fixed Issues

### WR-01: `InteractionActionsCoordinator.mirrorMuteIntoActiveFilter` still uses stdlib `runCatching`, swallowing `CancellationException`

**Files modified:** `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt`
**Commit:** `34cddc5`
**Applied fix:** Migrated `mirrorMuteIntoActiveFilter`'s `runCatching { ... }` to
`runCatchingCancellable { ... }` (importing the helper iteration 1 introduced in
`util/coroutines/CancellableRunCatching.kt`), including updating the inner `return@runCatching` to
`return@runCatchingCancellable`. This was the one leftover call site iteration 1's systemic
`runCatchingCancellable` migration (WR-04 in the original review) missed — both callers
(`FeedViewModel.muteUser`, `ProfileViewModel.toggleMute`) invoke it from inside
`requestSignAndPublish`'s `scope.launch { ... }`, a genuinely cancellable coroutine.

### WR-02: `RelayCrudCoordinator.saveRelay`'s merge branch bases its write on a stale pre-lock snapshot, not a fresh read

**Files modified:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt`
**Commit:** `2992ec6`
**Applied fix:** The existing-relay merge branch now re-reads the base relay from
`relayRepository.getRelayById(existingRelay.id)` (falling back to `existingRelay` only if that
lookup returns null) *inside* the per-relay-id `withLock` block, before computing the OR-merged
result — the same pattern `updateRelayRole` already uses and documents. Every merged flag
(`freshBase.X || sanitizedRelay.X`) now reads from the fresh in-lock snapshot instead of the
throttled `state.relays` mirror captured before the lock was acquired, closing the window where a
concurrent role-disable landing in between was silently reverted by the OR merge.

### WR-03: `AtomicReference<Job?>.launchReplacing`'s cancel-before-start ordering isn't actually enforced under genuinely concurrent unsynchronized callers

**Files modified:** `app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt`
**Commit:** `5b924e8`
**Applied fix:** Took the review's cheaper suggested option — corrected the docstring rather than
a structural fix, per the additional-context guidance to prefer the lower-risk option in this
second fixer pass given both current call sites already tolerate the gap. The docstring now
states the guarantee accurately: cancel-before-start ordering holds per-call (within one caller's
own three-step `getAndSet` → `cancel` → `start` sequence), not per-reference across genuinely
concurrent unsynchronized callers, since only the `getAndSet` is atomic. Documents that both
current call sites (`EventIngestCache.insertDebounceJob`, `NostrSessionManager.userBackfillJob`/
`ownProfileBootstrapWatcherJob`) tolerate the overlap because the displaced work is itself
idempotent/re-derivable, and that a caller needing the stronger guarantee must serialize its own
calls. No behavior change — logged to `docs/DONE.md` directly as LOG-48 (completed, no on-device
validation needed for a docs-only change) rather than `docs/KNOWN_ISSUES.md`.

### WR-04: `NostrSessionManager.reconcile()`'s check-then-act sequences over `@Volatile` fields aren't atomic under the class's own documented concurrent entry points

**Files modified:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt`
**Commit:** `3a11a13`
**Applied fix:** Chose the "guard with a Mutex" option, scoped to `maybeBootstrapOwnProfile` only
(not the whole `reconcile()` body) per the review's own "at minimum" framing — a contained fix
that closes the specific real-side-effect race (duplicate `bootstrapOwnProfileUseCase.start()`
calls) without touching the lower-severity, self-acknowledged-tolerable
`firstRelayConnectedLogged` duplicate-log case. Added a dedicated `ownProfileBootstrapMutex` and
wrapped `maybeBootstrapOwnProfile`'s full check-then-act body (both the already-has-outbox-relays
early-stop branch and the `ownProfileBootstrapPubkey != pubkey` guard through
`bootstrapOwnProfileUseCase.start(pubkey)`) in `withLock { ... }`, converting the method to
`suspend fun`. `reconcile()` (already `suspend`) calls it unchanged. The two documented concurrent
entry points (the `bootstrapJob` collect loop and `retryJob`'s delayed relaunch) can still
interleave their scheduling, but no longer their compound decisions.

### IN-01: No dedicated unit test for `runCatchingCancellable`

**Files modified:** `app/src/test/java/com/umbra/app/util/coroutines/CancellableRunCatchingTest.kt` (new)
**Commit:** `8689002`
**Applied fix:** Added `CancellableRunCatchingTest.kt` with three cases (one more than the review's
minimum two): a block throwing `CancellationException` asserts it propagates
(`@Test(expected = CancellationException::class)`) rather than being captured; a block throwing a
plain `IllegalStateException` asserts `Result.failure` wraps the same exception instance; and a
normally-completing block asserts `Result.success` wraps its return value (parity check against
the stdlib `runCatching` shape). All three pass. No behavior change — logged to `docs/DONE.md`
directly as LOG-50 (completed, no on-device validation needed for a test-only addition) rather
than `docs/KNOWN_ISSUES.md`.

### IN-02: `Result.onFailure` handlers in `NostrSessionManager` log a scrubbed message instead of the throwable

**Files modified:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt`
**Commit:** `d0d71c2`
**Applied fix:** Both `disableDeadRelay`'s and `reconcile`'s `.onFailure` handlers now call
`logger.e(e) { ... }` / `logger.e(error) { ... }` instead of `logger.d { ... }`, keeping the exact
same scrubbed message text as the log line's content — matching `RelayConfigViewModel`'s existing
correct pattern (`logger.e(e) { "Failed to enforce anonymous-session relay restriction" }`, see
LOG-39's iteration-1 fix).

## Skipped Issues

None — all six in-scope findings were fixed.

## Documentation

A separate `docs(02)` commit (`c401ed6`) logged all six findings per CLAUDE.md's bug-tracking
discipline: LOG-46 (WR-01), LOG-47 (WR-02), LOG-49 (WR-04), and LOG-51 (IN-02) as
`docs/KNOWN_ISSUES.md` entries with status `fix applied — needs on-device validation` (real
runtime-behavior fixes); LOG-48 (WR-03) and LOG-50 (IN-01) written directly to `docs/DONE.md` as
completed items, since neither changes runtime behavior (a docstring correction and a new test
file respectively) and so neither needs on-device validation before being considered done.

---

_Fixed: 2026-09-04T00:00:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 2_
