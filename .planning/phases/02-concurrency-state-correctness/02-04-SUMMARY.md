---
phase: 02-concurrency-state-correctness
plan: 04
subsystem: concurrency
tags: [atomic-reference, coroutines, job-scheduling, nostr-session, unit-testing]

# Dependency graph
requires:
  - phase: 02-concurrency-state-correctness
    provides: "EventIngestCache.insertDebounceJob's AtomicReference<Job?> check-and-cancel pattern (existing codebase precedent this plan's two new helpers generalize into reusable, non-blocking extension functions)"
provides:
  - "AtomicReference<Job?>.launchIfIdle/.launchReplacing (data/nostr/AtomicJobScheduling.kt) -- non-blocking skip-if-active and cancel-and-replace scheduling primitives, proven safe under genuine multi-threaded contention by a 200-iteration-per-scenario unit test"
  - "NostrSessionManager.retryJob/userBackfillJob/ownProfileBootstrapWatcherJob are now AtomicReference<Job?> holders scheduled through those helpers, closing the check-then-act race reconcile() had across its two concurrently-reachable call paths (LOG-30/BUG-13)"
affects: [02-concurrency-state-correctness]

# Actuals (#2632)
actuals:
  tokens: 5911
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "AtomicReference<Job?>.launchIfIdle/.launchReplacing -- two internal, non-blocking scheduling extension functions in data/nostr/AtomicJobScheduling.kt, now the shared seam for skip-if-active and cancel-and-replace job bookkeeping in this package"

key-files:
  created:
    - app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt
    - app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt
  modified:
    - app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt
    - docs/KNOWN_ISSUES.md

key-decisions:
  - "launchIfIdle's idle check compares against Job.isCompleted rather than Job.isActive as the plan's action text literally specified -- an isActive-keyed check left a window between a winning compareAndSet and that winner's own start() call where a second concurrent caller could read the freshly-installed, not-yet-started candidate, see it as neither active nor completed, and win its own compareAndSet against it, orphaning the first candidate and running two bodies. Caught by the 200-iteration real-thread test itself (successCount reached 2 in one run before the fix); documented as a Rule 1 auto-fixed bug rather than following the plan's literal wording."
  - "Scenario B's concurrency test captures each racer's own candidate Job reference by serializing only the 'call launchReplacing, then immediately read the resulting slot value' pair behind a Mutex, rather than snapshotting AtomicReference<Job?>.children after the fact -- a cancelled-before-first-resume candidate can finish and be dropped from the parent's children before any post-hoc snapshot would see it, which an earlier draft of the test hit directly (children.size read 1, not 8, in one run). The eight callers still race for that Mutex in genuinely unpredictable order on real Dispatchers.Default threads; only the observation step is serialized, not the production scheduling logic under test."
  - "The audit table in 02-04-PLAN.md's objective held exactly as written against the live code: retryJob, userBackfillJob, and ownProfileBootstrapWatcherJob converted; bootstrapJob, autoDisableRelayJob, and torCircuitRecoveryJob stay plain, each now carrying one inline comment stating the start()/stop()-serialized-by-started-flag invariant that makes conversion unnecessary."

patterns-established:
  - "AtomicReference<Job?>.launchIfIdle/.launchReplacing -- reusable non-blocking scheduling helpers for this package; not migrated onto any other file's job fields in this plan per its explicit scope prohibition."

requirements-completed: [BUG-13]

coverage:
  - id: D1
    description: "Two concurrent callers observing an idle retry slot schedule exactly ONE retry job between them -- the read-then-assign pair is a single compare-and-set, not two independent steps"
    requirement: "BUG-13"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt#given eight genuinely parallel schedulers on real threads when racing launchIfIdle then exactly one executes"
        status: pass
    human_judgment: false
  - id: D2
    description: "A caller that loses the compare-and-set never starts its candidate job and cancels it, so a concurrent burst leaves zero orphaned coroutines on the session scope"
    requirement: "BUG-13"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt#given eight genuinely parallel schedulers on real threads when racing launchIfIdle then exactly one executes"
        status: pass
    human_judgment: false
  - id: D3
    description: "Replacing scheduling always runs the new block and cancels the job it displaced, including under genuine eight-way concurrent contention (exactly one survives, the other seven are cancelled)"
    requirement: "BUG-13"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt#given eight genuinely parallel replacing schedulers on real threads when racing then exactly one survives"
        status: pass
    human_judgment: false
  - id: D4
    description: "After stop(), every one of the six job fields is null/cancelled -- no converted field survives a logout still fetching the logged-out identity's history over Tor"
    requirement: "BUG-13"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/nostr/*Test.kt (full data.nostr.* suite, unchanged pre-existing coverage of neighbouring session behavior)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Exactly three fields are converted and exactly three stay plain, each retained field carrying a comment stating the single-call-path invariant"
    requirement: "BUG-13"
    verification:
      - kind: unit
        ref: "grep-verified: AtomicReference<Job?>(null) count == 3, var retryJob|userBackfillJob|ownProfileBootstrapWatcherJob count == 0 in NostrSessionManager.kt"
        status: pass
    human_judgment: false

duration: ~24 minutes (commit-timestamp span; excludes required-reading and verification time not reflected in commit timestamps)
completed: 2026-09-04
status: complete
---

# Phase 2 Plan 4: Atomic Job Scheduling for NostrSessionManager Summary

**Two new non-blocking AtomicReference<Job?> extension functions (launchIfIdle, launchReplacing) close LOG-30/BUG-13 by converting NostrSessionManager's three demonstrably-racy job fields off plain check-then-act vars, proven safe under genuine multi-threaded contention by a 200-iteration-per-scenario real-thread test that itself caught and forced the fix of a compareAndSet-ordering race the plan's literal wording would have shipped.**

## Performance

- **Task commit timestamps (from git):** `1211062` at 2026-09-04T06:36:13Z (Task 1), `5f7be1c` at 06:39:32Z (Task 2), `e943f29` at 06:39:54Z (Task 3) -- roughly 24 minutes from session start (06:16, per STATE.md) to final commit; excludes required-reading and iterative Gradle verification time not reflected in commit timestamps alone.
- **Tasks:** 3 (all executed this session)
- **Files modified:** 4 (`AtomicJobScheduling.kt` created, `AtomicJobSchedulingTest.kt` created, `NostrSessionManager.kt` modified, `docs/KNOWN_ISSUES.md` modified)

## Accomplishments

- Created `app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt`: exactly two `internal` extension functions on `AtomicReference<Job?>`, no class/object/interface. `launchIfIdle` provides skip-if-already-active semantics via a single `compareAndSet` (candidate created with `CoroutineStart.LAZY`, started only on the winning path, cancelled on the losing path before it can execute a statement). `launchReplacing` provides cancel-and-replace semantics via `getAndSet` + cancel-then-start, preserving the existing cancel-the-old-before-the-new-runs ordering every call site depends on.
- Created `app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt`: five deterministic `runTest` scenarios (idle-schedules-once, active-slot-skips-second-call, completed-job-allows-reschedule, replacing-cancels-displaced-job, empty-slot-take-and-cancel-is-a-no-op) plus two genuinely-concurrent scenarios that race eight coroutines on a real `Dispatchers.Default` thread pool, 200 iterations each, asserting exactly one execution/survivor and zero lost or double-active jobs per iteration.
- **Bug found and fixed during Task 1 (Rule 1 -- see Deviations):** the plan's literal `launchIfIdle` algorithm (idle check keyed on `Job.isActive`) has a real race window between a winning `compareAndSet` and that candidate's own `start()` call. The 200-iteration real-thread test caught it directly (`successCount` reached 2 in one run, meaning two bodies executed and the first was silently orphaned). Fixed by keying the idle check on `Job.isCompleted` instead, which is false for the whole span from creation through actual completion regardless of started-vs-not-yet-started state, closing the window with no retry loop and no change to the single-compare-and-set-attempt design.
- Task 2 audited all six `NostrSessionManager` job fields against their live call sites (re-verifying the plan's own objective table) and converted `retryJob`, `userBackfillJob`, and `ownProfileBootstrapWatcherJob` to `AtomicReference<Job?>` holders scheduled through the new helpers: `scheduleRetry()` now a single `retryJob.launchIfIdle(scope) { ... }` call with its original body untouched; `startUserHistoryBackfill()`'s compound guard reads through `userBackfillJob.get()`, then launches via `launchReplacing`; `maybeBootstrapOwnProfile()`'s watcher launch goes through `launchReplacing` too. Every teardown site for the three converted fields (five in `reconcile()`, two in the bootstrap completion callback, one each in `stop()`, `stopOwnProfileBootstrap()`, and `startUserHistoryBackfill`'s null-pubkey early return) collapsed from a cancel-then-null-assign pair into a single `getAndSet(null)?.cancel()` statement. `bootstrapJob`, `autoDisableRelayJob`, and `torCircuitRecoveryJob` stay plain nullable fields, now carrying one inline comment above the three declarations stating the start()/stop()-serialized-by-the-volatile-started-flag invariant that makes conversion unnecessary.
- `git diff` on `NostrSessionManager.kt` confirms no job body, delay constant, or fetch call changed -- only how the three fields are stored, replaced, and cancelled.
- Task 3 updated the `LOG-30` entry in `docs/KNOWN_ISSUES.md` in place: status to `fix applied -- needs on-device validation`, plus a `**Fix:**` line naming the three converted fields, the three retained ones and their invariant, and the multi-threaded test coverage. Entry body paragraph kept verbatim.
- Full verification: `./gradlew compileDebugKotlin`, `./gradlew lintDebug` (no new warnings), and `./gradlew testDebugUnitTest` (full suite, ~900 test cases, zero failures) all pass; `./gradlew testDebugUnitTest --tests "com.umbra.app.data.nostr.*"` passes including the pre-existing `SessionReconnectPolicyTest`, `RelayBackoffPolicyTest`, and `RelaySubscriptionRegistryTest`.

## Task Commits

1. **Task 1: Non-blocking atomic job-scheduling helpers, proven by a real multi-threaded concurrency test (D-07)** - `1211062` (feat)
2. **Task 2: Audit all six job fields; convert the three with demonstrable races, document the three without (D-05)** - `5f7be1c` (fix)
3. **Task 3: Record the LOG-30 fix in the bug tracker** - `e943f29` (docs)

**Plan metadata:** committed alongside this SUMMARY (see final close-out commit).

## Files Created/Modified

- `app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt` (created) - `launchIfIdle`/`launchReplacing` extension functions on `AtomicReference<Job?>`
- `app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt` (created) - deterministic and genuinely-concurrent (200-iteration, real-thread) coverage for both helpers
- `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt` - three fields converted to `AtomicReference<Job?>`, scheduled through the new helpers; all their teardown sites collapsed to single take-and-cancel statements; three retained fields documented with an inline invariant comment
- `docs/KNOWN_ISSUES.md` - LOG-30 marked "fix applied -- needs on-device validation" with a `**Fix:**` note

## Decisions Made

- Kept the plan's exact three-converted/three-retained field split -- re-auditing against the live code confirmed the objective table's verdicts held with no divergence.
- Deviated from the plan's literal `launchIfIdle` check (`observed?.isActive == true`) to an `isCompleted`-based check after the concurrency test itself demonstrated the literal version's race window; documented below and in the function's own KDoc rather than silently matching the plan's prose over the plan's own behavioral requirements.
- Used a Mutex-serialized capture technique for Scenario B's job-reference collection (test-only instrumentation) rather than snapshotting `AtomicReference<Job?>.children`, after the latter proved unreliable against real thread-pool completion timing.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] launchIfIdle's isActive-keyed idle check had a compareAndSet-then-start race window**
- **Found during:** Task 1, while running the newly-written 200-iteration concurrency test against the plan's literally-specified implementation
- **Issue:** The plan's action text specifies: read `observed`, if `observed?.isActive == true` return false, otherwise create the candidate, `compareAndSet(observed, candidate)`, and only call `candidate.start()` after that CAS succeeds. This leaves a window, after a winning CAS installs the candidate into the slot but before that candidate's own `start()` call returns, where a second concurrent caller reads the slot, sees the freshly-installed candidate's `isActive` as `false` (it hasn't been started yet), and wins its own `compareAndSet` against that same value -- orphaning the first candidate (which still gets `start()`ed and runs) while also starting a second one. The test caught this directly: `successCount` reached 2 in one of 200 iterations.
- **Fix:** Changed the idle check from `observed?.isActive == true` to `observed != null && !observed.isCompleted`. `isCompleted` is false for a job's entire lifespan from creation through actual completion (whether started or not), so no window exists where a genuinely-still-outstanding job can be mistaken for idle. No retry loop or additional CAS attempt was introduced -- the single-compare-and-set-attempt design in the plan's behavior spec is preserved exactly.
- **Files modified:** `app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt`
- **Commit:** `1211062`

Or otherwise: no other deviations. Task 2 and Task 3 executed exactly as planned.

## Issues Encountered

- The concurrency test's Scenario B (racing `launchReplacing`) initially snapshotted `AtomicReference<Job?>.children` after all eight racers returned, expecting to see all eight created jobs. In practice, a candidate cancelled before its first resume can complete and be removed from its parent's children sequence faster than the test's own post-hoc snapshot, given eight real threads contending for a limited `Dispatchers.Default` pool -- one run observed `children.size == 1` instead of 8. Resolved by serializing only the "call `launchReplacing`, then immediately read the resulting slot value" pair behind a `Mutex` in the test, so each racer's own candidate is captured deterministically the instant it's created, while the racers still contend for that lock in genuinely unpredictable order on real threads.

## Known Stubs

None.

## Threat Flags

None -- no new network surface, signing surface, or log line was introduced. This plan changes only how three already-existing, already-Tor-routed background jobs are stored, replaced, and cancelled, exactly as scoped by `02-04-PLAN.md`'s `<threat_model>` (T-02-04-01 through T-02-04-05, all closed or accepted by this change; T-02-04-03's "no lock, no deadlock potential" claim is directly satisfied since neither `launchIfIdle` nor `launchReplacing` introduces a `Mutex`, `synchronized` block, or blocking wait, confirmed via grep in Task 1's acceptance criteria).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LOG-30 now sits in `docs/KNOWN_ISSUES.md` as "fix applied -- needs on-device validation," consistent with this project's opt-in on-device-validation convention -- no blocker for downstream plans.
- `AtomicJobScheduling.kt`'s two helpers are scoped to `data/nostr` only per this plan's explicit prohibition -- not migrated onto `EventIngestCache`, `EventRepositoryImpl`, `TorRuntimeManager`, or `TrackReferencedAuthorUseCase` in this plan.
- No blockers for the remaining Phase 2 work.

## Self-Check: PASSED

- `app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt`: FOUND, contains `launchIfIdle`/`launchReplacing`, `isCompleted`-based idle check.
- `app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt`: FOUND, contains both 200-iteration real-thread concurrency scenarios.
- `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt`: FOUND, three `AtomicReference<Job?>(null)` declarations, zero `var retryJob|userBackfillJob|ownProfileBootstrapWatcherJob` declarations.
- `docs/KNOWN_ISSUES.md`: FOUND, LOG-30 updated in place, not duplicated, not moved to DONE.md.
- Commit `1211062`: FOUND in `git log`.
- Commit `5f7be1c`: FOUND in `git log`.
- Commit `e943f29`: FOUND in `git log`.
- Test run: `./gradlew testDebugUnitTest` (full suite) BUILD SUCCESSFUL, zero failures across ~900 test cases; re-ran `AtomicJobSchedulingTest` 8 additional times with `--rerun` with no flakes observed.
- `compileDebugKotlin` and `lintDebug`: both BUILD SUCCESSFUL.

---
*Phase: 02-concurrency-state-correctness*
*Completed: 2026-09-04*
