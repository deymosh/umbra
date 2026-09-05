---
phase: 03-fix-validation-test-coverage
plan: 04
subsystem: testing
tags: [kotlin, coroutines, junit4, relay, concurrency]

requires:
  - phase: 02-concurrency-state-correctness
    provides: "RelayCrudCoordinator's per-relay Mutex (updateRelayRole chokepoint) and the discovered-flag-clearing sanitizedRelay copy in saveRelay, both already shipped"
provides:
  - "Five new RelayCrudCoordinatorTest cases pinning LOG-14/VALID-10 (discovered-flag clearing on both saveRelay branches), LOG-37/VALID-25 (removeRelayRole serializing against a role setter), and LOG-42/VALID-30 (saveRelay/deleteRelay each serializing against a role setter)"
  - "Exact new test method names for Plan 03-07 to cite when moving LOG-14/37/42 from KNOWN_ISSUES.md to DONE.md"
affects: [03-07]

actuals:
  tokens: 1407
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Reused existing RecordingRelayRepository gateInvocation/callLog machinery for two more chokepoints (removeRelayRole, deleteRelay) beyond the two set*Enabled cases it already covered"

key-files:
  created: []
  modified:
    - app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt

key-decisions:
  - "No production file touched -- all three already-shipped fixes (LOG-14/37/42) get coverage-only additions, verified by git status --porcelain app/src/main being empty after every commit"

patterns-established: []

requirements-completed: [VALID-10, VALID-25, VALID-30]

coverage:
  - id: D1
    description: "saveRelay clears the discovered flag on the existing-id edit branch"
    requirement: VALID-10
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given a discovered relay when saveRelay assigns it an owned role then the persisted relay is no longer discovered"
        status: pass
    human_judgment: false
  - id: D2
    description: "saveRelay clears the discovered flag on the blank-id merge-onto-existing-row branch, without creating a duplicate row"
    requirement: VALID-10
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given a discovered relay already in the repository when saveRelay is called with a blank id and the same url then the merged row is no longer discovered"
        status: pass
    human_judgment: false
  - id: D3
    description: "removeRelayRole serializes against a role setter on the same relay id -- neither the removal nor the setter's flag flip is lost"
    requirement: VALID-25
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given removeRelayRole overlapping a role setter on the same relay when both resolve then neither the removal nor the flag flip is lost"
        status: pass
    human_judgment: false
  - id: D4
    description: "saveRelay serializes against a role setter on the same relay id (second write waits for the first)"
    requirement: VALID-30
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given saveRelay overlapping a role setter on the same relay id when both resolve then the second write waits for the first"
        status: pass
    human_judgment: false
  - id: D5
    description: "deleteRelay serializes against a role setter on the same relay id (removal waits for the role write)"
    requirement: VALID-30
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given deleteRelay overlapping a role setter on the same relay id when both resolve then the removal waits for the role write"
        status: pass
    human_judgment: false

duration: ~15min
completed: 2026-09-05
status: complete
---

# Phase 3 Plan 4: Relay CRUD Coordinator Concurrency/Discovered-Flag Coverage Summary

**Five new `RelayCrudCoordinatorTest` cases, using the existing gated `RecordingRelayRepository`, pin LOG-14's discovered-flag clearing and LOG-37/LOG-42's per-relay lock coverage — all against already-shipped fixes, zero production diff.**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-09-05T12:55:37Z
- **Completed:** 2026-09-05T12:59:02Z
- **Tasks:** 3
- **Files modified:** 1

## Accomplishments
- LOG-14/VALID-10: two new cases prove `saveRelay` forces `isDiscovered = false` on both the existing-id edit branch and the blank-id merge-onto-existing-row branch, the latter also confirming the merge still produces exactly one repository row.
- LOG-37/VALID-25: one new case forces a genuine overlap (via `gateInvocation`) between `removeRelayRole` and a role setter on the same relay id, asserting the removal is blocked by the per-relay `Mutex` (a single `enter:relayA` marker while the gate is held) rather than merely queued behind the test's own gate, and that neither write is lost once both resolve.
- LOG-42/VALID-30: two new cases force the same overlap pattern against `saveRelay` (asserting write ordering only, since the non-empty-id branch legitimately overwrites the setter's flag) and `deleteRelay` (asserting the row still exists while the setter's write is held, and is gone once it completes).

## Task Commits

Each task was committed atomically:

1. **Task 1: The manual save path clears the discovered flag on both branches (LOG-14 / VALID-10)** - `f24230c` (test)
2. **Task 2: Role removal serializes against a role setter on the same relay (LOG-37 / VALID-25)** - `25d6876` (test)
3. **Task 3: Save and delete serialize against a role setter on the same relay (LOG-42 / VALID-30)** - `75ea093` (test)

**Plan metadata:** (this commit)

_Note: All three tasks are plain `type="auto" tdd="true"` — the plan's `tdd="true"` was a style instruction (write the failing-shape assertion first, confirm it would fail against pre-fix behavior), not an MVP+TDD gate requiring separate RED/GREEN commits. Each task landed as a single `test(...)` commit with the passing case already in place, matching the project's own convention of one commit per coverage-only task._

## Files Created/Modified
- `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` - Extended with the five new cases; no existing case renamed, deleted, or reordered.

## Decisions Made
- No production file touched in any task — verified via `git status --porcelain app/src/main` (empty) after every commit, per the plan's explicit prohibition.
- Used the search role (not inbox/DM) for the LOG-37 removal case, since inbox/DM are rejected outright for an anonymous session and this test's subject is signed-in — keeping the case independent of that unrelated guard, exactly as the plan specified.
- The plan's acceptance criteria text assumed 5 pre-existing test cases (predicting 7/8/10 total across the three tasks); the file actually had 6 pre-existing cases before this plan (confirmed by reading the file in full), so the true final counts are 8/9/11. This is a documentation-arithmetic mismatch in the plan text, not a deviation in behavior — all specified method names, assertions, and the zero-production-diff constraint were followed exactly, and every acceptance criterion's *substance* (single enter marker while gated, two non-overlapping pairs after release, correct final flag state) passed as written.

## Deviations from Plan

None - plan executed exactly as written. (See the pre-existing-count note above under Decisions Made — it is an observation about the plan's own predicted totals, not a change to any instructed behavior, method name, or assertion.)

## Issues Encountered
None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- The five new method names above are ready for Plan 03-07 to cite verbatim when moving LOG-14, LOG-37, and LOG-42 from `docs/KNOWN_ISSUES.md` to `docs/DONE.md`.
- `RelayCrudCoordinatorTest.kt` now has 11 passing cases (`./gradlew testDebugUnitTest --tests "com.umbra.app.ui.relay.RelayCrudCoordinatorTest"`); `compileDebugKotlin` and `lintDebug` both succeed with no new warnings.
- No blockers for downstream plans.

---
*Phase: 03-fix-validation-test-coverage*
*Completed: 2026-09-05*

## Self-Check: PASSED

- FOUND: app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt
- FOUND: .planning/phases/03-fix-validation-test-coverage/03-04-SUMMARY.md
- FOUND: f24230c (Task 1 commit)
- FOUND: 25d6876 (Task 2 commit)
- FOUND: 75ea093 (Task 3 commit)
- FOUND: a55f805 (docs commit)
