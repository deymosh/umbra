---
phase: 03-fix-validation-test-coverage
plan: 06
subsystem: testing
tags: [junit4, kotlin, logout, cache-trim, feed-merge, future-timestamp, coroutines-test]

# Dependency graph
requires:
  - phase: 03-fix-validation-test-coverage
    provides: "03-01's requirement-id minting (VALID-06/VALID-19) and the phase-wide audit-and-cite methodology (D-01/D-02/D-03/D-08/D-09)"
provides:
  - "Ten logger-identity cases (six in BackfillDeleteLogoutUseCaseTest.kt, four in TrimMemoryCachesUseCaseTest.kt) closing every remaining per-step cleanup catch in LogoutUseCase and TrimMemoryCachesUseCase"
  - "One domain-model case pinning isFromFuture()/isTimestampFromFuture()'s zero-tolerance default"
  - "One feed-merge-stage case proving the future-dated-events check is actually wired into the home feed's computed visible set, not just the domain model"
  - "Twelve exact new test method names for Plan 03-07 to cite as evidence for LOG-27/VALID-19 and LOG-7/VALID-06"
affects: [03-07]

# Actuals (#2632)
actuals:
  tokens: 4800
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Throwing*/Recording* wrapper via Kotlin interface delegation (`: Interface by delegate`), overriding exactly one member to throw a caller-supplied instance — extended from the one existing example per file to cover every remaining per-step catch."
    - "Identity assertion (assertSame) against a FakeUmbraLogger's recorded error call, distinguishing a fixed catch (logs before continuing) from the pre-fix silent-swallow behavior that the file's older 'later steps still ran' cases cannot distinguish."

key-files:
  created: []
  modified:
    - app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt
    - app/src/test/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCaseTest.kt
    - app/src/test/java/com/umbra/app/domain/model/EventModelBehaviorTest.kt
    - app/src/test/java/com/umbra/app/ui/feed/FeedStateMergeCoordinatorTest.kt

key-decisions:
  - "No production source file touched — every fix under test already shipped; this plan only closes coverage gaps (git status --porcelain app/src/main empty across all three tasks)."
  - "Every new wrapper/double keeps the identity (assertSame) assertion, not equals, since a catch that logs a fresh exception instead of the caught one would otherwise still pass."
  - "The two remaining futureEventRecheckTicker() call sites (ProfileViewModel, ThreadViewModel) are recorded as source-read verified rather than fixtured — their constructors are too heavy for this phase, and the feed-merge-stage case covers the same combine-with-ticker idiom structurally."

requirements-completed: [VALID-06, VALID-19]

coverage:
  - id: D1
    description: "LogoutUseCase's six remaining per-step cleanup catches (session stop, user clear, contact/mute/pin-list clear, backfill-anchor wipe) each prove the caught throwable reaches the injected logger by identity."
    requirement: "VALID-19"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt#given_nostrSessionControllerStopThrows_when_logging_then_loggerRecordsErrorWithSameThrowable (+5 sibling cases)"
        status: pass
    human_judgment: false
  - id: D2
    description: "TrimMemoryCachesUseCase's four remaining per-step catches (user prune, contact/mute/pin-list trim) each prove the caught throwable reaches the injected logger by identity."
    requirement: "VALID-19"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCaseTest.kt#given_userRepositoryPruneStaleDataThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable (+3 sibling cases)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The future-dated-events check's no-argument form is pinned to its shipped zero-tolerance default, and the feed's merge stage is proven to actually exclude future-dated notes and future repost timestamps from the computed visible set."
    requirement: "VALID-06"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/domain/model/EventModelBehaviorTest.kt#given_noExplicitTolerance_when_checkingFuture_then_theDefaultBehavesAsZeroTolerance"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/feed/FeedStateMergeCoordinatorTest.kt#given a future dated note in notesFlow when computedFeedFlow computes the visible set then it is excluded while a past dated note is kept"
        status: pass
    human_judgment: false

duration: ~25min
completed: 2026-09-05
status: complete
---

# Phase 03 Plan 06: Logout/Cache-Trim Logger Identity and Future-Dated-Events Wiring Summary

**Ten new logger-identity cases close every remaining LogoutUseCase/TrimMemoryCachesUseCase cleanup catch, and two new cases pin the future-dated-events zero-tolerance default plus prove the feed's merge stage actually applies it.**

## Performance

- **Duration:** ~25 min
- **Tasks:** 3
- **Files modified:** 4 (all test files; zero production diff)

## Accomplishments

- `BackfillDeleteLogoutUseCaseTest.kt`: six new file-private throwing wrappers (`ThrowingStopNostrSessionController`, `ThrowingClearAllUserRepository`, `ThrowingClearAllContactListRepository`, `ThrowingClearAllMuteListRepository`, `ThrowingClearAllPinListRepository`, `ThrowingClearBackfillAnchorsEventRepository`) and six cases, each asserting the injected `FakeUmbraLogger` recorded exactly one error call carrying the same throwable instance the wrapper threw. File now executes 21 cases (15 pre-existing + 6 new).
- `TrimMemoryCachesUseCaseTest.kt`: extended the four existing `Recording*Repository` doubles with a second, null-defaulting throw parameter (mirroring `RecordingEventRepository`'s existing shape) and added four cases with the identical identity assertion. File now executes 7 cases (3 pre-existing + 4 new).
- `EventModelBehaviorTest.kt`: one new case asserting `isFromFuture()`'s and `isTimestampFromFuture()`'s no-argument forms agree with their explicit-zero-argument forms across four offsets (-60s, 0s, +5s, +60s), plus four direct true/false pins at a full minute's offset on both the `Event` method and the bare-timestamp free function — pinning the shipped zero-tolerance default itself, which the pre-existing parametric cases (always passing an explicit tolerance) never covered.
- `FeedStateMergeCoordinatorTest.kt`: extended the private `noteView()` helper with optional `createdAt`/`repostedAt` parameters (both defaulting to the prior fixed values) and added one case proving `FeedStateMergeCoordinator.computedFeedFlow`'s three-source combine (notes flow, active filters flow, recheck ticker) actually excludes a future-dated note and a past-dated note carrying a future repost timestamp from the computed visible set, while a genuinely past-dated note in the same emission survives — both via the `onVisibleNotesComputed` callback and via the flow's own current snapshot.

## Task Commits

Each task was committed atomically:

1. **Task 1: LogoutUseCase's six remaining cleanup catches** - `0c5ebca` (test)
2. **Task 2: TrimMemoryCachesUseCase's four remaining cleanup catches** - `9cfbf40` (test)
3. **Task 3: Future-dated-events default pin + feed wiring proof** - `eb82f22` (test)

## Files Created/Modified

- `app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt` - six new throwing wrappers + six logger-identity cases
- `app/src/test/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCaseTest.kt` - four recording doubles extended with an optional throw parameter + four logger-identity cases
- `app/src/test/java/com/umbra/app/domain/model/EventModelBehaviorTest.kt` - one zero-tolerance-default case
- `app/src/test/java/com/umbra/app/ui/feed/FeedStateMergeCoordinatorTest.kt` - `noteView()` helper extended with optional timestamp params + one future-dated-note wiring case

## Twelve New Test Method Names (for Plan 03-07 citation)

**LOG-27 / VALID-19 — LogoutUseCase half** (`BackfillDeleteLogoutUseCaseTest.kt`):
1. `given_nostrSessionControllerStopThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`
2. `given_userRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`
3. `given_contactListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`
4. `given_muteListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`
5. `given_pinListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`
6. `given_clearBackfillAnchorsThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`

**LOG-27 / VALID-19 — TrimMemoryCachesUseCase half** (`TrimMemoryCachesUseCaseTest.kt`):
7. `given_userRepositoryPruneStaleDataThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`
8. `given_contactListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`
9. `given_muteListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`
10. `given_pinListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`

**LOG-7 / VALID-06** (`EventModelBehaviorTest.kt`, `FeedStateMergeCoordinatorTest.kt`):
11. `given_noExplicitTolerance_when_checkingFuture_then_theDefaultBehavesAsZeroTolerance`
12. `given a future dated note in notesFlow when computedFeedFlow computes the visible set then it is excluded while a past dated note is kept`

## Source-Read Note — Two Unfixtured Ticker Call Sites (LOG-7 / VALID-06)

Per this plan's prohibition against building a fixture for the profile or thread view models, the two remaining `futureEventRecheckTicker()` call sites are verified by direct source read rather than by a test — their constructors carry dependency weight out of scope for this phase. The `FeedStateMergeCoordinatorTest` case above covers the same combine-with-ticker idiom structurally.

- `app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt:295` — `.combine(futureEventRecheckTicker()) { result, _ -> result }`, feeding into the filter at `ProfileViewModel.kt:298-299`: `n.event.isFromFuture() || (n.repostedAt != null && isTimestampFromFuture(n.repostedAt))`.
- `app/src/main/java/com/umbra/app/ui/feed/ThreadViewModel.kt:287` — `futureEventRecheckTicker()` inside the same four-source `combine(...)` that feeds `processThreadGraph`, whose own future check is at `ThreadViewModel.kt:443`: `val descendants = collectDescendants(anchor.id, allEvents).filterNot { it.isFromFuture() }`.

## Decisions Made

- No production source file modified in this plan — every fix under test already shipped; `git status --porcelain app/src/main` was confirmed empty after each task.
- Every new case uses `assertSame` (instance identity) against the recorded error call, not `assertEquals`, so a catch that logs a fresh exception instead of rethrowing/logging the caught one would still fail the case — this is the exact gap the pre-fix silent catches left, and what the file's older "later steps still ran" cases cannot distinguish (research Pitfall 3).
- The four `Recording*Repository` doubles in `TrimMemoryCachesUseCaseTest.kt` gained a null-defaulting throw parameter rather than four new wrapper classes, keeping the two pre-existing cases that construct them unmodified in the diff.
- Future-offset assertions in both new cases use a full minute (not a second) so a scheduling pause between reading the clock and asserting can never flip the expected result, per the plan's flakiness-edge truth.

## Deviations from Plan

None - plan executed exactly as written. All twelve method names match the plan's specified strings character for character; all four extended/new files show only additive diffs against their pre-existing case bodies.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 03-07 can cite all twelve method names above verbatim as evidence for moving LOG-27 (VALID-19) and LOG-7 (VALID-06) from `docs/KNOWN_ISSUES.md` to `docs/DONE.md`, plus the two source-read-verified ticker call sites for the parts of LOG-7 this plan didn't fixture directly.

---
*Phase: 03-fix-validation-test-coverage*
*Completed: 2026-09-05*

## Self-Check: PASSED

All four modified test files and this SUMMARY.md verified present on disk; all three task commit hashes (`0c5ebca`, `9cfbf40`, `eb82f22`) verified present in `git log`.
