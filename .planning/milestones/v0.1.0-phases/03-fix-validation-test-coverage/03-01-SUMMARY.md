---
phase: 03-fix-validation-test-coverage
plan: 01
subsystem: testing
tags: [bug-tracker, requirements-traceability, relay-crud, junit]

# Dependency graph
requires:
  - phase: 02-concurrency-state-correctness
    provides: RelayCrudCoordinator's LOG-47/LOG-53/LOG-55 fixes and the RelayCrudCoordinatorTest harness this plan cites as evidence
provides:
  - LOG-55 moved verbatim from docs/KNOWN_ISSUES.md to docs/DONE.md with a dated Validated bullet and a quoted test-method Evidence bullet
  - VALID-38 requirement id (LOG-55's traceability entry)
  - VALID-11..VALID-37 requirement ids for the 27 other expanded-scope (D-08) entries, all mapped to Phase 3 in REQUIREMENTS.md's traceability table
  - A proven end-to-end template (cite test -> run it -> move verbatim -> record date) for the remaining 27 moves Plan 03-07 performs
affects: [03-fix-validation-test-coverage]

# Actuals (#2632)
actuals:
  tokens: 2800
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Bug-tracker move template: cut the `### LOG-N` section verbatim from KNOWN_ISSUES.md, append verbatim to DONE.md, then add exactly two new bullets (Validated, Evidence) directly after the existing Fix bullet — never touching the entry's original text."

key-files:
  created: []
  modified:
    - docs/KNOWN_ISSUES.md
    - docs/DONE.md
    - .planning/REQUIREMENTS.md

key-decisions:
  - "Used single backticks (not double) around the quoted backtick test-method name in DONE.md's Evidence bullet, matching the file's existing markdown convention rather than escaping for a literal-backtick edge case that doesn't apply here."

patterns-established:
  - "Pattern 1: A validated bug-tracker entry gets exactly two new bullets appended (Validated, Evidence) after its existing Fix bullet, with zero other characters changed — verified via `git diff` showing DONE.md as additions-only and KNOWN_ISSUES.md as deletions confined to the moved block."

requirements-completed: [VALID-38]

coverage:
  - id: D1
    description: "LOG-55 cited, executed, and moved verbatim from KNOWN_ISSUES.md to DONE.md with a dated Validated bullet and an Evidence bullet naming the exact test file and backtick method name"
    requirement: VALID-38
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given a relay already in the repository but not yet reflected in the throttled state mirror when saveRelay is called with a blank id then it merges into the existing row instead of creating a duplicate"
        status: pass
    human_judgment: false
  - id: D2
    description: "VALID-11..VALID-37 minted in REQUIREMENTS.md, one per expanded-scope LOG entry, with matching Phase 3 traceability rows and recomputed coverage totals (58 total, Phase 3 = 38)"
    verification:
      - kind: other
        ref: "grep -c '^- \\[ \\] \\*\\*VALID-' .planning/REQUIREMENTS.md == 38; grep -c '| Phase 3 | Pending |' == 38; grep -q 'Phase 3 = 38'"
        status: pass
    human_judgment: false

duration: ~10min
completed: 2026-09-05
status: complete
---

# Phase 3 Plan 1: Tracer — LOG-55 Move & VALID-11..VALID-38 Requirement IDs Summary

**Proved the bug-tracker move template end to end on LOG-55 (existing RelayCrudCoordinatorTest citation, executed, moved to DONE.md with dated Validated/Evidence bullets) and minted all 28 new VALID-11..VALID-38 requirement ids, bringing REQUIREMENTS.md's Phase 3 traceability to the full 38-entry D-08 scope.**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-09-05T03:10:09Z
- **Completed:** 2026-09-05T03:13:52Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Ran `RelayCrudCoordinatorTest` (6 tests, 0 failures) and confirmed the blank-id merge case is the exact test LOG-55's Fix line describes adding.
- Moved LOG-55 verbatim from `docs/KNOWN_ISSUES.md` to `docs/DONE.md`, appending a `**Validated: 2026-09-05**` bullet and an `**Evidence:**` bullet naming the test file and its full backtick method name.
- Added `VALID-38` (LOG-55) to `.planning/REQUIREMENTS.md`'s Fix Validation checkbox block and Traceability table.
- Minted `VALID-11`..`VALID-37` for the remaining 27 expanded-scope entries (LOG-18/19/20/21/22/23/24/26/27/28/29/30/31/34/37/38/39/40/41/42/46/47/49/51/52/53/54) per the plan's fixed mapping, each phrased from the LOG entry's own heading text.
- Recomputed REQUIREMENTS.md's coverage totals (30 → 58 total, Phase 3 count 10 → 38) and updated the footer date.
- Confirmed LOG-35 and LOG-44 remain unminted — LOG-35 appears exactly once in REQUIREMENTS.md, inside the new exclusion sentence, with no `VALID-` line referencing either.

## Task Commits

Each task was committed atomically:

1. **Task 1: One entry end to end — LOG-55 cited, executed, and moved to DONE.md (VALID-38)** - `6abe29b` (docs)
2. **Task 2: Mint VALID-11..VALID-37 for the 27 remaining expanded-scope entries (D-08 traceability)** - `b51622c` (docs)

_No TDD tasks in this plan — both tasks are documentation/bookkeeping moves with no production code touched._

## Files Created/Modified
- `docs/KNOWN_ISSUES.md` - LOG-55 section removed (37 other entries untouched)
- `docs/DONE.md` - LOG-55 appended verbatim plus two new bullets (Validated, Evidence)
- `.planning/REQUIREMENTS.md` - VALID-11..VALID-38 checkbox lines, matching traceability rows, an exclusion-rationale sentence, and recomputed coverage totals

## Decisions Made
- Quoted LOG-55's evidence test-method name with single backticks in DONE.md, consistent with the rest of the file's markdown style, rather than double-escaping — the method name itself contains no literal backtick character so no escaping was needed.
- Left Task 1's `VALID-38` insertion positioned directly after `VALID-10` (ahead of Task 2's 27 new ids) exactly as the plan specified, so Task 2's edit cleanly inserted between them without needing to relocate `VALID-38`.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- The move template (cite → run → move verbatim → append Validated/Evidence bullets) is proven end to end on a real entry and ready for Plan 03-07 to apply to the remaining 27 entries.
- All 38 Phase 3 requirement ids (`VALID-01`..`VALID-38`) now exist, uniquely defined, and mapped to Phase 3 in the traceability table — no gaps for later plans in this phase to fill in retroactively.
- `docs/KNOWN_ISSUES.md` now has 37 remaining `fix applied` entries (LOG-1..LOG-54 minus LOG-55) still awaiting their own classification/move in this phase's later plans; LOG-35 (open) is unaffected and stays out of scope.

---
*Phase: 03-fix-validation-test-coverage*
*Completed: 2026-09-05*

## Self-Check: PASSED

- FOUND: `.planning/phases/03-fix-validation-test-coverage/03-01-SUMMARY.md`
- FOUND: commit `6abe29b` (Task 1)
- FOUND: commit `b51622c` (Task 2)
