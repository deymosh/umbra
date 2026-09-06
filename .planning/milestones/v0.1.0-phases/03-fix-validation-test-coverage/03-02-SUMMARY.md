---
phase: 03-fix-validation-test-coverage
plan: 02
subsystem: testing
tags: [bug-tracker, test-citation, junit, relay-crud, event-ingestion]

# Dependency graph
requires:
  - phase: 03-fix-validation-test-coverage
    provides: "Plan 03-01's LOG-55 citation string (`given a relay already in the repository...`), reused verbatim for LOG-47/LOG-53"
provides:
  - "03-CITATIONS.md — the citation ledger Plan 03-07 reads when writing docs/DONE.md Evidence bullets for LOG-1, 11, 19, 21, 22, 23, 24, 29, 31, 34, 40, 41, 46, 47, 53"
affects: [03-fix-validation-test-coverage]

# Actuals (#2632)
actuals:
  tokens: 5600
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Citation ledger row shape: LOG-N | Requirement | Verdict | Test file | exact backtick method name(s) | would-fail-if-reverted reasoning | run result — with INSUFFICIENT as an explicit escape hatch never silently upgraded to TEST."

key-files:
  created:
    - .planning/phases/03-fix-validation-test-coverage/03-CITATIONS.md
  modified: []

key-decisions:
  - "LOG-1 explicitly documents rejecting EventLruCacheTest.kt as a citation (verified by grep: zero references to ReplaceableEventKey/winsReplaceableRace in that file) in favor of EventRepositoryIngestionIntegrationTest's two ordering cases."
  - "LOG-47 and LOG-53 cite the identical test method Plan 03-01 already cited for LOG-55, with an explicit note that one test proves three related things together rather than three independent proofs — and a further caveat that LOG-47's specific within-lock re-read is exercised but not independently isolated by this single-coroutine test scenario."
  - "LOG-23's row explicitly caveats that FeedFilterTest.kt proves the invariant that made the old by-id lookup permanently dead, not a direct regression test of FeedViewModel.muteUser's resolver itself (no FeedViewModel-level test calling muteUser exists in this repo)."
  - "LOG-24's citation reasoning treats a full diff revert (which would delete FeedViewModel's two new internal functions entirely, not just their call sites) as the correct 'would fail if reverted' scenario, since the functions and their wiring landed together in one commit."

patterns-established: []

requirements-completed: [VALID-01, VALID-07, VALID-12, VALID-14, VALID-15, VALID-16, VALID-17, VALID-21, VALID-23, VALID-24, VALID-28, VALID-29, VALID-31, VALID-32, VALID-36]

coverage:
  - id: D1
    description: "All 15 in-scope entries (LOG-1, 11, 19, 21, 22, 23, 24, 29, 31, 34, 40, 41, 46, 47, 53) have a row in 03-CITATIONS.md naming an executed test file, exact backtick method name(s), and a would-fail-if-reverted reason"
    requirement: "VALID-01, VALID-07, VALID-12, VALID-14, VALID-15, VALID-16, VALID-17, VALID-21, VALID-23, VALID-24, VALID-28, VALID-29, VALID-31, VALID-32, VALID-36"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt, EventRepositoryIngestionIntegrationTest.kt, AtomicJobSchedulingTest.kt, RelayCrudCoordinatorTest.kt, FutureEventRecheckTickerTest.kt, InteractionActionsCoordinatorTest.kt, FeedFilterTest.kt, FeedViewModelStateTest.kt, LogScrubberTest.kt, CancellableRunCatchingTest.kt — all ten classes executed via ./gradlew testDebugUnitTest, all green"
        status: pass
    human_judgment: false
  - id: D2
    description: "No source or test file under app/src modified in this plan — read-and-run only"
    verification:
      - kind: other
        ref: "git status --porcelain app/src empty after every task's commit; git diff --stat across all three commits touches only .planning/"
        status: pass
    human_judgment: false

duration: ~30min
completed: 2026-09-05
status: complete
---

# Phase 3 Plan 2: Existing-Test Citation Audit for 15 Fix-Applied Entries Summary

**Audited all 15 already-fixed bug-tracker entries research identified as having existing test coverage, verified each candidate citation against the fix's actual mechanism (not just a plausibly-named file), ran all ten cited test classes green, and recorded every citation — including two explicit false-citation rejections and two acknowledged partial-pinning caveats — in a new 03-CITATIONS.md ledger for Plan 03-07 to consume.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-09-05T03:15:01Z
- **Completed:** 2026-09-05T03:23:09Z
- **Tasks:** 3
- **Files modified:** 1 (created)

## Accomplishments

- Created `.planning/phases/03-fix-validation-test-coverage/03-CITATIONS.md` with 15 entry rows across three clusters (cache/ingestion, relay coordinator, ViewModel/filter/logging-utility), each naming a test file, exact backtick method name(s), and a concrete would-fail-if-reverted reason.
- Ran all ten distinct test classes cited in the ledger via `./gradlew testDebugUnitTest --tests ...`, per task — all green, no failures:
  - `EventIngestCacheTest` (33/33), `EventRepositoryIngestionIntegrationTest` (11/11), `AtomicJobSchedulingTest` (7/7)
  - `RelayCrudCoordinatorTest` (6/6)
  - `FutureEventRecheckTickerTest` (2/2), `InteractionActionsCoordinatorTest` (13/13), `FeedFilterTest` (6/6), `FeedViewModelStateTest` (9/9), `LogScrubberTest` (7/7), `CancellableRunCatchingTest` (3/3)
- Rejected `EventLruCacheTest.kt` as LOG-1's citation in writing — confirmed by grep that the file never references `ReplaceableEventKey`/`winsReplaceableRace` — in favor of `EventRepositoryIngestionIntegrationTest`'s two replaceable-slot ordering cases.
- Confirmed by source read (`EventIngestCache.kt:545`, `InteractionActionsCoordinator.kt:141`) that LOG-40's and LOG-46's one-line helper migrations are actually wired at their call sites, since no existing test exercises either specific call site directly — both marked `TEST + SOURCE-READ`.
- Identified and documented, rather than silently citing, two partial-pinning cases: LOG-23's `FeedFilterTest` citation proves the invariant that made the bug's root cause structurally dead code, not a direct regression test of `FeedViewModel.muteUser`'s resolver (no such test exists in the repo); LOG-47's shared citation with LOG-53/LOG-55 is exercised by, but not independently isolated in, the one cited test (a revert of LOG-47's specific increment alone would not fail this particular single-coroutine scenario).
- Confirmed byte-identical reuse of Plan 03-01's LOG-55 citation string for both LOG-47 and LOG-53, plus an explicit table-adjacent note that all three entries share one test rather than three independent proofs.
- Verdict tally: 13 `TEST`, 2 `TEST + SOURCE-READ` (LOG-40, LOG-46), 0 `INSUFFICIENT` — all 15 entries have sufficient, executed evidence; none needed to stay in `docs/KNOWN_ISSUES.md` for lack of coverage.

## Task Commits

Each task was committed atomically:

1. **Task 1: Cache and ingestion cluster — LOG-1, LOG-19, LOG-21, LOG-40, LOG-41** - `5c9ade6` (docs)
2. **Task 2: Relay coordinator cluster — LOG-29, LOG-31, LOG-47, LOG-53** - `11c84db` (docs)
3. **Task 3: ViewModel, filter, and logging-utility cluster — LOG-11, LOG-22, LOG-23, LOG-24, LOG-34, LOG-46** - `c2a532c` (docs)

_No TDD tasks in this plan — all three tasks are read-and-run audit/documentation work with zero production or test code touched._

## Files Created/Modified

- `.planning/phases/03-fix-validation-test-coverage/03-CITATIONS.md` (new) — the 15-row citation ledger, in three cluster sections plus a closing summary tally.

No file under `app/src` or `docs/` was created, modified, or moved by this plan.

## Decisions Made

- Followed the plan's explicit instruction to cite the LOG-47/LOG-53/LOG-55 shared test even though a strict "would this row's own fix fail in isolation" reading shows LOG-47's specific within-lock re-read isn't independently distinguished by this particular single-coroutine test scenario — documented that nuance in both the row and the shared note rather than hiding it, so a future reader of the ledger (or `docs/DONE.md`) isn't misled into thinking three unrelated bugs each have their own dedicated regression test.
- For LOG-23, kept verdict `TEST` (per the plan's explicit citation instruction) rather than downgrading to `INSUFFICIENT`, since `FeedFilterTest`'s invariant genuinely explains why the pre-fix code was permanently dead — but added an explicit caveat that this is indirect (invariant) evidence, not a direct test of the reverted call site, since no `FeedViewModel`-level test exercising `muteUser` exists in this repository to check against.
- For LOG-24, treated a full-diff revert (which deletes `muteWriteResultMessage`/`pinWriteResultMessage` themselves, not just their call sites, since both landed in the same fix) as the correct "would fail if reverted" scenario — a compile failure counts as failing.

## Deviations from Plan

None — plan executed exactly as written. All three tasks' acceptance criteria were met without needing any Rule 1-4 auto-fixes, since this plan touches no production or test code.

## Issues Encountered

None. All ten cited test classes passed on the first run with no flakiness.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- `03-CITATIONS.md` now has all 15 entries this plan was scoped to audit, each with an executed, passing citation ready for Plan 03-07 to consume verbatim when writing `docs/DONE.md`'s Evidence bullets.
- The two explicit caveat rows (LOG-23, LOG-47) should be read by Plan 03-07 before drafting those two entries' Evidence bullets, so the permanent bug-tracker record doesn't overclaim direct regression coverage where the ledger itself documents a narrower form of evidence.
- `RelayCrudCoordinatorTest.kt` was read and run but not modified in this plan, as required — Plan 03-04 (same wave) extends it independently with zero file-content overlap with this plan's commits.

---
*Phase: 03-fix-validation-test-coverage*
*Completed: 2026-09-05*

## Self-Check: PASSED

- FOUND: `.planning/phases/03-fix-validation-test-coverage/03-CITATIONS.md`
- FOUND: commit `5c9ade6` (Task 1)
- FOUND: commit `11c84db` (Task 2)
- FOUND: commit `c2a532c` (Task 3)
