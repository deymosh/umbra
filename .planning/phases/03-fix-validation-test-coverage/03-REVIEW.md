---
phase: 03-fix-validation-test-coverage
reviewed: 2026-09-05T17:48:37Z
depth: standard
files_reviewed: 8
files_reviewed_list:
  - app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt
  - app/src/test/java/com/umbra/app/domain/model/EventModelBehaviorTest.kt
  - app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt
  - app/src/test/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCaseTest.kt
  - app/src/test/java/com/umbra/app/ui/feed/FeedStateMergeCoordinatorTest.kt
  - app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt
  - docs/DONE.md
  - docs/KNOWN_ISSUES.md
findings:
  critical: 0
  warning: 2
  info: 1
  total: 3
status: issues_found
---

# Phase 03: Code Review Report

**Reviewed:** 2026-09-05T17:48:37Z
**Depth:** standard
**Files Reviewed:** 8
**Status:** issues_found

## Summary

This phase adds/extends six JUnit test files (validation coverage for LOG-4/6/7/12/27/29/31/37/42/47/53/55, etc.) plus append-only edits to `docs/DONE.md`/`docs/KNOWN_ISSUES.md`. No `app/src/main` production files were touched.

I traced each new/changed test against the production code it claims to pin:

- `UmbraNostrClientTest` (`onWebSocketOpen` superseded-socket identity check, `connect()`'s per-relay `dialingRelays` guard) — verified against `UmbraNostrClient.kt`/`RelayMessageHandling.kt`. The dial-in-flight test genuinely forces two real OS threads to overlap inside the guarded region via a `CountDownLatch` pair; removing the `dialingRelays.add()` guard would make this test fail (both `connect()` calls would proceed and the assertion on `relayIssueFlow.replayCache` / `dialThread` behavior would break). Sound.
- `EventModelBehaviorTest` (`isFromFuture`/`isTimestampFromFuture` default-tolerance pinning) — verified against `Event.kt`. Correct, would fail if the zero-default tolerance regressed.
- `BackfillDeleteLogoutUseCaseTest` / `TrimMemoryCachesUseCaseTest` (per-step `logger.e(throwable)` on each `LogoutUseCase`/`TrimMemoryCachesUseCase` cleanup step) — verified against both use cases' source. Every "…RecordsErrorWithSameThrowable" test would fail against the pre-fix silent `catch (_: Exception) {}` shape (zero logger calls recorded). Sound, and `assertSame` (not `assertEquals`) correctly pins throwable identity, not just message equality.
- `FeedStateMergeCoordinatorTest` (`stateIn`-not-`shareIn` cold-start fix, future-dated note exclusion, `mergeFilters` fallback) — verified against `FeedStateMergeCoordinator.kt`. Filtering/fallback logic and callback wiring are correctly exercised.
- `RelayCrudCoordinatorTest` (per-relay-id `Mutex` serialization for `updateRelayRole`/`saveRelay`/`deleteRelay`/`removeRelayRole`, DM dirty-flag fix, `saveRelay` merge-vs-duplicate fix) — verified against `RelayCrudCoordinator.kt`. The `CompletableDeferred`-gated interleaving tests are deterministic under `runTest`'s single-threaded `StandardTestDispatcher` (no flakiness from scheduling order), and correctly model production concurrency since `RelayCrudCoordinator` is only ever driven from `viewModelScope` (`Dispatchers.Main.immediate`, itself single-threaded) — so a virtual-time coroutine interleaving is a faithful reproduction of the real race, not an approximation of a genuine multi-thread race the way `UmbraNostrClient.connect()` (dialed from `Dispatchers.IO`) needed real threads for.
- `docs/DONE.md` / `docs/KNOWN_ISSUES.md` — every evidence citation naming one of the six reviewed test files (LOG-1/7/11/12/27/29/31/37/42/46/47/50/53/55 in DONE.md) was cross-checked against the actual test method names in the cited files: all match verbatim, no stale/renamed test references found.

No BLOCKER-level defects found. Two WARNING-level robustness gaps and one INFO-level note below.

## Warnings

### WR-01: `awaitRealDispatch()`'s fixed real-clock delay is a timing heuristic, not a deterministic synchronization primitive

**File:** `app/src/test/java/com/umbra/app/ui/feed/FeedStateMergeCoordinatorTest.kt:138-141` (definition), used at lines 167, 175, 179, 226, 261
**Issue:** `notesFlow`/`computedFeedFlow` intentionally stay on real dispatchers (`Dispatchers.IO`/`Dispatchers.Default`) outside `runTest`'s virtual-time scheduler, so the test bridges that gap with a fixed `delay(300)` on each real dispatcher before calling `advanceUntilIdle()`. This is correct in principle (the doc comment explains why virtual time can't fast-forward a real dispatcher hop) but it is a wall-clock guess, not a signal tied to the actual work completing. Under CPU-contended CI (parallel test forks, a loaded shared runner), 300ms may not be enough time for the `Dispatchers.IO`/`Dispatchers.Default` hop to actually execute and post its continuation back, producing an intermittent, non-reproducible failure (e.g. `calls` missing the last emission, or `feedState.value` still showing the `stateIn` placeholder) that has nothing to do with the behavior under test. This is exactly the "flaky-by-luck" pattern the review brief asked to watch for — the four tests using this helper (all of `FeedStateMergeCoordinatorTest` except the `mergeFilters` unit test) inherit the risk.
**Fix:** Prefer a deterministic signal over a fixed sleep, e.g. have the `ObservableFeedEventRepository`/`FakeFeedRepository` fakes complete a `CompletableDeferred`/`Channel` handshake once their real-dispatcher hop has actually run, and await that instead of `delay(300)`; or at minimum increase the margin and retry once on failure to reduce (not eliminate) CI flakiness. This is a pre-existing pattern in this codebase (`EventIngestCacheTest.awaitInsertDebounce()`, per this file's own doc comment) rather than something newly introduced here, but it is still a real flakiness risk being propagated into this phase's new coverage.

### WR-02: `LatchBlockingWebSocket`'s background thread isn't guaranteed to be cleaned up if an assertion between `entered.await()` and `release.countDown()` fails

**File:** `app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt:72-86` (class), `132-174` (test using it)
**Issue:** The dial-in-flight test spawns a real, non-daemon `Thread` that blocks inside `WebSocket.close()` on `release.await(5, TimeUnit.SECONDS)` until the test explicitly calls `release.countDown()` (line 163). If any assertion between the `entered.await()` success check (line 148-149) and that `release.countDown()` call throws — e.g. the `relayIssueFlow.replayCache.none { ... }` assertion at line 159-161 — the test method returns via the thrown `AssertionError` without ever releasing the latch. The background thread is left blocked for up to its own 5-second timeout before it times out on its own and throws `MarkerAbortException` (uncaught, printed to stderr, harmless to other tests since each `subject()` client instance is independent) — but this means a failing run of this specific test can add up to 5 extra seconds of hung, non-daemon-thread teardown time to the JVM before the test process can fully exit, and the failure's actual root cause can be obscured by an unrelated `MarkerAbortException` stack trace printed moments later on a different thread.
**Fix:** Wrap the body from `entered.await(...)` through the final assertions in a `try`/finally` (or use `runCatching`) that unconditionally calls `release.countDown()` before rethrowing, so a mid-test assertion failure doesn't leave the spawned thread blocked for the full timeout window.

## Info

### IN-01: `RelayCrudCoordinatorTest`'s deterministic interleaving tests are correctly *not* real-thread tests, unlike `UmbraNostrClientTest`'s — worth keeping distinct in future additions

**File:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` (concurrency tests, e.g. lines 157, 184, 269, 298, 325)
**Issue:** Not a defect — noted only because the review brief specifically asked to check whether concurrency assertions "genuinely force an overlap rather than being flaky-by-luck." These tests use `kotlinx.coroutines.test.runTest` (single-threaded `StandardTestDispatcher`) plus `CompletableDeferred` gates rather than real `Thread`s. That is the right choice here (deterministic, and faithful to production since `RelayCrudCoordinator` only ever runs on `viewModelScope`'s single-threaded `Dispatchers.Main.immediate`), in contrast to `UmbraNostrClientTest`'s dial-guard test, which correctly *does* use real threads because `UmbraNostrClient.connect()` is dialed from `Dispatchers.IO`-backed callers and has no suspension point for virtual time to interleave. `docs/DONE.md`'s LOG-29 entry (`docs/DONE.md:573`) describes the two `RelayCrudCoordinatorTest` cases as "genuinely-concurrent," which is accurate in the sense of "truly overlapping/interleaved execution" but could be misread as "real-thread parallelism proven" the way `AtomicJobSchedulingTest`'s cited real-thread case is. No action needed against the test itself; flagging only so a future reader doesn't conflate the two different (and both individually correct) concurrency-testing strategies in this codebase.
**Fix:** None required. Optional: a one-line clarifying phrase in `docs/DONE.md`'s LOG-29 entry distinguishing "deterministic coroutine interleaving" from "real-thread parallelism" would remove any ambiguity for a future reader who isn't already aware `viewModelScope` is Main-confined.

---

_Reviewed: 2026-09-05T17:48:37Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
