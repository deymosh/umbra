---
phase: 03-fix-validation-test-coverage
plan: 07
subsystem: testing
tags: [bug-tracker, requirements-traceability, bookkeeping]

# Dependency graph
requires:
  - phase: 03-fix-validation-test-coverage
    provides: "03-01's move template and VALID-11..VALID-38 requirement ids; 03-CITATIONS.md's 15 executed test citations; 03-DISPOSITIONS.md's 6 source-read rationales; 03-04/03-05/03-06's 20 new test method names for LOG-14/37/42, LOG-12, and LOG-27/7"
provides:
  - "27 audited bug-tracker entries relocated verbatim from docs/KNOWN_ISSUES.md to docs/DONE.md, each with a dated Validated bullet and a re-checkable Evidence bullet"
  - "docs/KNOWN_ISSUES.md reduced to the 11 headings Plan 03-08 scopes: the 10 entries staying open plus LOG-35"
affects: [03-fix-validation-test-coverage, 04-release]

# Actuals (#2632)
actuals:
  tokens: 5700
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Bug-tracker move template applied at scale: cut each `### LOG-N` section verbatim from KNOWN_ISSUES.md, append verbatim to DONE.md, insert exactly two new bullets (Validated, Evidence) at the end of the existing bullet list, immediately before the blank line that begins the prose body."

key-files:
  created: []
  modified:
    - docs/KNOWN_ISSUES.md
    - docs/DONE.md

key-decisions:
  - "Interpreted the plan's 'immediately after its Fix bullet and before the blank line that begins the prose body' instruction as 'at the end of the bullet list, before the blank line' for the several entries (LOG-29, LOG-30-adjacent, LOG-31, LOG-38, LOG-42, LOG-47) whose Fix bullet is not literally the list's last bullet (a Where bullet follows it) — this matches the LOG-5/LOG-55 precedent's actual structure (where Fix is last) and satisfies the must_have truth's own unambiguous condition ('before the blank line that begins the prose body') without contradicting it."
  - "LOG-23's Evidence bullet was written plain, with no caveat clause repeated in DONE.md — the plan's 'four entries need something beyond the plain shape' paragraph names only LOG-40, LOG-46, LOG-7, and the LOG-47/LOG-53 pair; LOG-23's caveat already lives in 03-CITATIONS.md as the ledger's own annotation, not as separate DONE.md text the task instructed adding."
  - "Used full app/src/test/java/... paths for every Evidence bullet's test file, matching the path convention 03-01's LOG-55 entry and this phase's own summaries already established, rather than the bare filenames 03-CITATIONS.md's table column uses."
  - "Wrote a small Node.js script (no Python available in this sandbox) to perform the verbatim cut-and-append mechanically across all 27 entries in one deterministic pass per task, then verified the result against every automated check in the plan before staging — safer for a byte-identical-move requirement at this volume than 27 manual Edit calls."

patterns-established: []

requirements-completed: [VALID-01, VALID-06, VALID-07, VALID-08, VALID-10, VALID-11, VALID-12, VALID-13, VALID-14, VALID-15, VALID-16, VALID-17, VALID-19, VALID-20, VALID-21, VALID-23, VALID-24, VALID-25, VALID-27, VALID-28, VALID-29, VALID-30, VALID-31, VALID-32, VALID-34, VALID-36, VALID-37]

coverage:
  - id: D1
    description: "21 test-backed entries (LOG-1, 7, 11, 12, 14, 19, 21, 22, 23, 24, 27, 29, 31, 34, 37, 40, 41, 42, 46, 47, 53) moved verbatim to docs/DONE.md with a dated Validated bullet and an Evidence bullet naming the exact test file(s) and backtick method name(s) from 03-CITATIONS.md / 03-04/03-05/03-06-SUMMARY.md"
    requirement: "VALID-01, VALID-06, VALID-07, VALID-08, VALID-10, VALID-12, VALID-14, VALID-15, VALID-16, VALID-17, VALID-19, VALID-21, VALID-23, VALID-24, VALID-25, VALID-28, VALID-29, VALID-30, VALID-31, VALID-32, VALID-36"
    verification:
      - kind: other
        ref: "for n in 1 7 11 12 14 19 21 22 23 24 27 29 31 34 37 40 41 42 46 47 53; do grep -c \"^### LOG-$n \" docs/DONE.md == 1 && grep -c \"^### LOG-$n \" docs/KNOWN_ISSUES.md == 0; done"
        status: pass
    human_judgment: false
  - id: D2
    description: "6 source-read-verified entries (LOG-18, 20, 28, 39, 51, 54) moved verbatim to docs/DONE.md with a Validated bullet qualified 'by direct source read, not by a test' and an Evidence bullet reproducing 03-DISPOSITIONS.md's rationale sentence unchanged (D-09)"
    requirement: "VALID-11, VALID-13, VALID-20, VALID-27, VALID-34, VALID-37"
    verification:
      - kind: other
        ref: "grep -c 'by direct source read, not by a test' docs/DONE.md == 6; for n in 18 20 28 39 51 54, heading present once in DONE.md and zero times in KNOWN_ISSUES.md"
        status: pass
    human_judgment: false
  - id: D3
    description: "Cross-file integrity preserved: no LOG heading duplicated or lost, docs/KNOWN_ISSUES.md reduced to exactly 11 remaining headings (the 10 next-plan entries plus still-open LOG-35), no app/src file touched"
    verification:
      - kind: other
        ref: "grep -c '^### LOG-' docs/KNOWN_ISSUES.md docs/DONE.md sums to 51 (unchanged from before this plan); grep -ho '^### LOG-[0-9]*' docs/KNOWN_ISSUES.md docs/DONE.md | sort | uniq -d is empty; git status --porcelain app/src is empty"
        status: pass
    human_judgment: false

duration: ~20min
completed: 2026-09-05
status: complete
---

# Phase 3 Plan 7: Move 27 Audited Entries into DONE.md Summary

**Moved all 27 D-08-scoped entries this phase closed — 21 on executed test evidence, 6 on a direct source read — verbatim from `docs/KNOWN_ISSUES.md` into `docs/DONE.md`, leaving exactly the 11 headings (10 staying entries plus still-open LOG-35) Plan 03-08 is scoped against.**

## Performance

- **Duration:** ~20 min
- **Tasks:** 2
- **Files modified:** 2 (docs only, zero production/test source diff)

## Accomplishments

- Task 1: moved LOG-1, 7, 11, 12, 14, 19, 21, 22, 23, 24, 27, 29, 31, 34, 37, 40, 41, 42, 46, 47, 53 — each with a `**Validated: 2026-09-05**` bullet and an `**Evidence:**` bullet naming the exact test file(s) and backtick method name(s) cited in `03-CITATIONS.md` or minted by Plans 03-04/03-05/03-06. LOG-40 and LOG-46 each add a clause naming the source-read call site confirming their generic-helper migration; LOG-7 adds the two `ProfileViewModel`/`ThreadViewModel` source-read quotes for its unfixtured ticker call sites; LOG-47 and LOG-53 each explicitly name the other two entries (including LOG-55) sharing their single cited test, so a reader doesn't count one regression test as three independent proofs.
- Task 2: moved LOG-18, 20, 28, 39, 51, 54 — each with a `**Validated: 2026-09-05 — by direct source read, not by a test**` bullet and an `**Evidence:**` bullet reproducing `03-DISPOSITIONS.md`'s ready-to-paste rationale sentence unchanged, including every quoted `file:line` reference. LOG-18's Evidence bullet names all three call sites across its two files (`EventRepositoryImpl.kt` x2, `NegentropySyncOrchestrator.kt` x1).
- Zero entries held back as gaps — every one of the 27 rows in the two ledgers had a usable citation or rationale; none was `INSUFFICIENT` or missing its quoted evidence.
- `docs/KNOWN_ISSUES.md` now holds exactly 11 headings: the 10 entries Plan 03-08 annotates (LOG-2, 3, 4, 6, 13, 26, 30, 38, 49, 52) plus the still-`open` LOG-35, untouched.
- `docs/DONE.md` now holds 40 headings (13 pre-existing + LOG-55 from Plan 03-01 + these 27); combined with `docs/KNOWN_ISSUES.md`'s 11, the total (51) matches the pre-plan combined count exactly — no entry duplicated or lost.

## Task Commits

Each task was committed atomically:

1. **Task 1: Move the 21 test-backed entries into DONE.md with executed-test citations** - `8403046` (docs)
2. **Task 2: Move the 6 source-read-verified entries into DONE.md with their no-test rationale (D-09)** - `a12df91` (docs)

_No TDD tasks in this plan — both tasks are documentation/bookkeeping moves with no production or test code touched._

## Files Created/Modified

- `docs/KNOWN_ISSUES.md` — 27 entries removed (sections cut verbatim); the 10 next-plan entries and LOG-35 left untouched, unmodified, and correctly separated by single blank lines.
- `docs/DONE.md` — 27 entries appended verbatim, each carrying exactly two new bullets (Validated, Evidence) inserted at the end of its existing bullet list, immediately before the blank line that begins its prose body.

## Decisions Made

- Built a small Node.js script (this sandbox has no `python3`) to perform the cut-and-append mechanically for all 27 entries per task in one deterministic pass, rather than 27 manual edits — chosen specifically because the plan's core risk is an accidental non-verbatim move at this volume, and a script's output can be diffed and verified against every automated check before staging, the same way a human would spot-check a smaller move by hand.
- For entries whose Fix bullet is not the last bullet in the list (a Where bullet follows it — LOG-29, LOG-31, LOG-42, LOG-47, and others), inserted the two new bullets at the very end of the bullet list rather than literally between Fix and Where — this satisfies the must_have truth's own unambiguous "before the blank line that begins the prose body" condition and matches the actual structure of the LOG-5/LOG-55 precedent entries (both cases where Fix already was last).
- LOG-23 was moved with a plain Evidence bullet — no caveat clause was duplicated into DONE.md, since the plan's "four entries need something beyond the plain shape" instruction names only LOG-40, LOG-46, LOG-7, and the shared LOG-47/LOG-53 pair; LOG-23's fix-pinning caveat already lives in `03-CITATIONS.md`'s own ledger row.
- Every Evidence bullet's test-file path uses the full `app/src/test/java/...` form (matching 03-01's LOG-55 precedent and this phase's own plan summaries), not the bare filename `03-CITATIONS.md`'s table column shortens to.

## Deviations from Plan

None — plan executed exactly as written. All 27 entries moved on the citations/rationales provided by 03-CITATIONS.md and 03-DISPOSITIONS.md; no entry required the "unplanned gap" fallback since every ledger row had complete evidence.

## Issues Encountered

An early draft of the move script left a stray double-newline at the true end of `docs/KNOWN_ISSUES.md`/`docs/DONE.md` when the file's original absolute-last entry happened to fall in the set being kept (Task 1) or moved (Task 2, LOG-54) — the split boundary logic only strips the separator *between* entries, not the file's own trailing EOF newline on the final chunk. Caught by inspecting `tail -c 20 | od -c` on both files before staging; fixed by normalizing every chunk's trailing whitespace at load time so reconstruction is independent of which chunk ends up last. No entry text was affected — this was purely a file-ending byte, verified with `od -c` before and after the fix, well before either commit.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `docs/KNOWN_ISSUES.md` now contains exactly the 11 headings Plan 03-08 is scoped to annotate: LOG-2, 3, 4, 6, 13, 26, 30, 38, 49, 52 (the 10 staying entries) plus LOG-35 (still open, out of this phase's scope entirely).
- All 27 of this plan's requirement ids (`VALID-01, 06, 07, 08, 10, 11, 12, 13, 14, 15, 16, 17, 19, 20, 21, 23, 24, 25, 27, 28, 29, 30, 31, 32, 34, 36, 37`) now have their closing evidence permanently recorded in `docs/DONE.md`.
- No blockers for Plan 03-08.

---
*Phase: 03-fix-validation-test-coverage*
*Completed: 2026-09-05*

## Self-Check: PASSED

- FOUND: `.planning/phases/03-fix-validation-test-coverage/03-07-SUMMARY.md`
- FOUND: commit `8403046` (Task 1)
- FOUND: commit `a12df91` (Task 2)
