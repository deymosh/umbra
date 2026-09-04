---
phase: 02-concurrency-state-correctness
plan: 02
subsystem: concurrency
tags: [kotlin-coroutines, atomic-reference, nip-09, event-ingest-cache, unit-testing]

# Dependency graph
requires:
  - phase: 02-concurrency-state-correctness
    provides: "Plan 02-01's RelayCrudCoordinatorTest concurrency-test harness pattern (per-invocation gating, ordered call log) as the established shape for genuinely-concurrent kotlinx-coroutines-test coverage in this phase"
provides:
  - "EventIngestCache.snapshotEmitJob is a CAS-scheduled AtomicReference<Job?> — a burst of overlapping scheduleSnapshotEmit() calls from concurrent flatMapMerge branches produces exactly one cachedEventsFlow emission and one cachedEventBundles emission, with no lost or orphaned job (LOG-21/BUG-05)"
  - "EventIngestCache.applyIncomingDeletion resolves NIP-09 a-tag deletions against both the in-memory cache and the own-archive, mirroring EventRepositoryImpl.getLatestAddressableEvent's two-source precedent — a followed author's retracted addressable event now actually disappears from cache (LOG-19/BUG-03)"
affects: [02-concurrency-state-correctness]

# Actuals (#2632)
actuals:
  tokens: 4359
  tasks: 2
  commits: 4

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "CAS-scheduled lazy Job via AtomicReference<Job?>.compareAndSet + CoroutineStart.LAZY, for check-then-launch call sites that must keep skip-relaunch-if-active semantics (distinct from insertDebounceJob's cancel-and-replace shape)"
    - "Two-source addressable-event resolution (in-memory snapshot + archive) taking the newer candidate via listOfNotNull(...).maxByOrNull { it.createdAt }, mirroring EventRepositoryImpl.getLatestAddressableEvent"

key-files:
  created: []
  modified:
    - app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt
    - app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt
    - docs/KNOWN_ISSUES.md

key-decisions:
  - "This plan's two production-code tasks (both commits) were completed by a prior executor run that was interrupted by a session rate-limit error partway through its doc-update step. This close-out session verified the already-committed code against the plan's acceptance_criteria (re-ran the full test suite and compileDebugKotlin/lintDebug fresh, read the full diffs) rather than re-implementing anything, then finished the remaining doc/state work."
  - "snapshotEmitJob's CAS loser cancels its own lazily-started job rather than the winner cancelling anything — preserves the class's existing skip-relaunch-if-active coalescing semantics instead of copying insertDebounceJob's cancel-and-replace shape, per the plan's explicit prohibition"
  - "The in-memory snapshot for a-tag deletion resolution is taken exactly once, before the per-coordinate loop and before entering the IO dispatcher, since snapshot() acquires cachedEventsMutex and must not be re-entered per coordinate"

patterns-established:
  - "CAS-scheduled lazy Job for skip-relaunch-if-active concurrent scheduling (as opposed to insertDebounceJob's cancel-and-replace AtomicReference usage already established in this file)"

requirements-completed: [BUG-03, BUG-05]

coverage:
  - id: D1
    description: "A burst of overlapping scheduleSnapshotEmit() calls from concurrent coroutines produces exactly one cachedEventsFlow emission and exactly one cachedEventBundles emission per coalescing window, with no lost or orphaned emit job; cancelPendingSnapshotEmit is idempotent (BUG-05/LOG-21)"
    requirement: "BUG-05"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt#given eight overlapping coroutines calling scheduleSnapshotEmit concurrently when time advances then exactly one snapshot and one bundle emission occur with no event lost"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt#given a scheduled snapshot emit when cancelPendingSnapshotEmit runs then no emission occurs and a repeated cancel is a harmless no-op"
        status: pass
    human_judgment: false
  - id: D2
    description: "A NIP-09 a-tag deletion for a non-owned author's addressable event resident only in the in-memory cache now removes it; the author-equality check and created_at upper bound both survive intact (BUG-03/LOG-19)"
    requirement: "BUG-03"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt#given a non-owned addressable event resident only in the in-memory cache when an a-tag deletion targets it then it is removed from the cache"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt#given an a-tag deletion signed by a different pubkey than the coordinate's author when applied then the in-memory event is not removed"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt#given an in-memory addressable event newer than the deletion's own created_at when applied then it is not removed"
        status: pass
    human_judgment: false

duration: unknown (see Performance note — closed out from existing commits, not timed end-to-end)
completed: 2026-09-04
status: complete
---

# Phase 2 Plan 2: EventIngestCache Concurrency & Two-Source Deletion Summary

**snapshotEmitJob is now a CAS-scheduled AtomicReference (no lost/duplicate/orphaned snapshot emit under concurrent ingest), and NIP-09 a-tag deletions resolve against the in-memory cache as well as the own-archive (a followed author's retracted addressable event actually disappears) — both pinned by five new EventIngestCacheTest cases.**

## Performance

- **Note on timing:** This plan's two task commits were produced by a prior executor run that was interrupted by a session rate-limit error partway through its `docs/KNOWN_ISSUES.md` update — after both code fixes were already committed and tested. This session (2026-09-04) did not re-execute the tasks; it verified the existing commits against the plan's `<acceptance_criteria>`, then completed the remaining doc/state close-out work. No reliable single-session "duration" exists for the original implementation work, so it is not fabricated here.
- **Task commit timestamps (from git):** `9243420` at 2026-09-03T22:02:21Z, `a2ce787` at 2026-09-03T22:02:40Z (19s apart — both commits from the interrupted run, not a measure of actual working time, which included test/verification cycles between and around them not reflected in commit timestamps alone).
- **This close-out session:** verification (fresh `testDebugUnitTest`/`compileDebugKotlin`/`lintDebug` runs) + doc/state completion, 2026-09-04T06:09–06:10Z.
- **Tasks:** 2 (both already complete on disk/in git at session start)
- **Files modified:** 3 (`EventIngestCache.kt`, `EventIngestCacheTest.kt`, `docs/KNOWN_ISSUES.md`)

## Accomplishments
- Verified `EventIngestCache.snapshotEmitJob` is a `val AtomicReference<Job?>` scheduled via a single `compareAndSet` on a `CoroutineStart.LAZY`-built job; the CAS loser cancels its own unstarted job rather than being orphaned. `cancelPendingSnapshotEmit()` uses `getAndSet(null)?.cancel()`. `scheduleInsert`/`cancelPendingInserts` (the sibling `insertDebounceJob` precedent) are untouched, confirmed via `git show --stat`.
- Verified `applyIncomingDeletion`'s "a"-tag branch takes exactly one in-memory `snapshot()` before the per-coordinate loop (not re-entering `cachedEventsMutex`), resolves each coordinate against both an in-memory candidate and the existing `ownEventArchive` candidate, and takes the newer via `listOfNotNull(...).maxByOrNull { it.createdAt }` — with the author-equality check and `created_at` upper bound applying identically to both sources, and a shared (not per-source-branched) removal body.
- Ran `./gradlew testDebugUnitTest --tests "com.umbra.app.data.repository.EventIngestCacheTest"` fresh (forced re-run, not relying on Gradle's up-to-date cache): 31 tests, 0 failures, 0 errors — including all 5 new tests (2 for the concurrency fix, 3 for the two-source deletion fix) and every pre-existing test unmodified.
- Ran `./gradlew compileDebugKotlin lintDebug`: both succeed with no new warnings.
- Completed `docs/KNOWN_ISSUES.md`'s bug-tracker close-out: LOG-19 (already updated by the interrupted prior run) and LOG-21 (finished this session) both now read "fix applied — needs on-device validation" with `**Fix:**` lines describing each fix.

## Task Commits

Both task commits were made by the interrupted prior executor run (not re-created this session):

1. **Task 1: snapshotEmitJob becomes an AtomicReference with a compare-and-set schedule (LOG-21/BUG-05)** - `9243420` (fix)
2. **Task 2: a-tag deletions resolve against the in-memory cache as well as the own-archive (LOG-19/BUG-03)** - `a2ce787` (fix)

**Bug tracker update:** `3a35c21` (docs: mark LOG-19/LOG-21 fix applied)

**Plan metadata:** committed alongside this SUMMARY (see final close-out commit).

## Files Created/Modified
- `app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt` - `snapshotEmitJob` field type change to `AtomicReference<Job?>`, `scheduleSnapshotEmit`/`cancelPendingSnapshotEmit` rewritten for CAS scheduling; `applyIncomingDeletion`'s "a"-tag branch rewritten for two-source (in-memory + archive) resolution
- `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt` - 5 new tests: 2 for concurrent snapshot-emit scheduling (8-way concurrent burst, cancel-then-idempotent-recancel), 3 for a-tag deletion (regression, ownership guard, recency guard)
- `docs/KNOWN_ISSUES.md` - LOG-19 and LOG-21 both marked "fix applied — needs on-device validation" with `**Fix:**` notes

## Decisions Made
- No production-code decisions were made this session — the code was already committed and matched the plan's `<action>` specs exactly on inspection (field-for-field, function-for-function against the plan's acceptance criteria).
- Verification approach: rather than trusting the prior run's claimed completion, re-read the full plan, re-read the full diffs of both commits, forced a fresh (non-cached) test run, and confirmed `compileDebugKotlin`/`lintDebug` pass, before treating the code as done.

## Deviations from Plan

None — plan executed exactly as written by the prior (interrupted) run. This session found no gaps between the committed code/tests and the plan's `<acceptance_criteria>`, so no Rule 1-4 fixes were needed.

## Issues Encountered
The original executor session hit a session rate-limit error partway through updating `docs/KNOWN_ISSUES.md` (after both code commits landed), leaving `LOG-19` updated but `LOG-21` still `open` and no SUMMARY/STATE/ROADMAP/REQUIREMENTS updates done. This session resumed from that exact interruption point per its explicit instructions, verifying rather than redoing the code.

## Known Stubs

None.

## Threat Flags

None — both fixes stay inside the threat model already documented in `02-02-PLAN.md`'s `<threat_model>` (T-02-02-01 through T-02-02-05): the a-tag author-equality check and `created_at` upper bound both apply identically to the new in-memory source (confirmed by the ownership-guard and recency-guard tests), and no new log line, network surface, or persistence path was introduced.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LOG-19/LOG-21 both sit in `docs/KNOWN_ISSUES.md` as "fix applied — needs on-device validation," consistent with this project's opt-in on-device-validation convention — no blocker for downstream plans.
- No blockers for the remaining Phase 2 plans (02-03, 02-04, 02-05).

## Self-Check: PASSED

- `app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt`: FOUND, contains `AtomicReference<Job?>` snapshotEmitJob and the two-source `applyIncomingDeletion` resolution.
- `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt`: FOUND, contains all 5 new test names verified via `grep`.
- `docs/KNOWN_ISSUES.md`: FOUND, LOG-19 and LOG-21 both updated.
- Commit `9243420`: FOUND in `git log`.
- Commit `a2ce787`: FOUND in `git log`.
- Commit `3a35c21`: FOUND in `git log`.
- Test run: 31 tests, 0 failures, 0 errors (`app/build/test-results/testDebugUnitTest/TEST-com.umbra.app.data.repository.EventIngestCacheTest.xml`).
- `compileDebugKotlin` and `lintDebug`: both BUILD SUCCESSFUL.

---
*Phase: 02-concurrency-state-correctness*
*Completed: 2026-09-04*
