---
gsd_state_version: 1.0
milestone: v0.1.0
milestone_name: Hardening & First Public Release
current_phase: 02
current_phase_name: Concurrency & State Correctness
status: verifying
stopped_at: Completed 02-05-PLAN.md (Phase 2 complete, all 5 plans)
last_updated: "2026-09-04T06:50:34.962Z"
last_activity: 2026-09-04
last_activity_desc: Phase 02 Plan 4 (NostrSessionManager job-field atomicity, BUG-13) completed
state_head: 05e678e025daf4d40815d355df4444d2751e1932
progress:
  total_phases: 4
  completed_phases: 1
  total_plans: 8
  completed_plans: 8
  percent: 25
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-03)

**Core value:** A trustworthy, stable first public release that upholds Umbra's TOR-only and Amber-only guarantees without regressions
**Current focus:** Phase 02 — Concurrency & State Correctness

## Current Position

Phase: 02 (Concurrency & State Correctness) — EXECUTING
Plan: 5 of 5
Status: Phase complete — ready for verification
Last activity: 2026-09-04 — Phase 02 Plan 4 (NostrSessionManager job-field atomicity, BUG-13) completed

Progress: [███░░░░░░░] 25%

## Performance Metrics

**Velocity:**

- Total plans completed: 4
- Average duration: —
- Total execution time: —

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 | 3 | - | - |

**Recent Trend:**

- Last 5 plans: —
- Trend: —

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 01 P01 | 14min | 3 tasks | 10 files |
| Phase 01 P02 | 12min | 3 tasks | 7 files |
| Phase 01 P03 | ~20min | 3 tasks | 5 files |
| Phase 02 P01 | 14min | 2 tasks | 4 files |
| Phase 02 P02 | N/A (resumed from interrupted run) | 2 tasks | 3 files |
| Phase 02 P03 | ~6min | 3 tasks | 4 files |
| Phase 02 P04 | ~24min | 3 tasks | 4 files |
| Phase 02 P05 | ~3min | 3 tasks | 5 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: Horizontal layering (visibility → correctness → validation → release), not vertical slices — this is hardening on a mature codebase, not feature work
- [Roadmap]: Error-visibility fixes ordered before state fixes so Phase 2's concurrency bugs fail loudly rather than silently
- [Roadmap]: Validation of the 10 `fix applied` entries deferred to Phase 3 — several sit in code Phases 1-2 touch
- [PROJECT.md]: v0.1.0 tag is prepared locally only; pushing it requires the user's explicit go-ahead (irreversible, publicly visible)
- [PROJECT.md]: On-device/emulator validation stays opt-in — genuinely visual fixes remain in KNOWN_ISSUES.md for the user
- [Phase 01]: Tracer feedback gate (Plan 01-01 Task 1): proceeded through Tasks 2-3 without pausing despite auto_advance=false, since the plan's own frontmatter is autonomous:true with zero checkpoint tasks and the tracer's verify was fully automated
- [Phase 01]: LOG-27 (BUG-10, all 12 sites) fully resolved by Plan 01-01; LOG-17 (BUG-01) only 2 of 8 sites resolved, moved to in-progress
- [Phase 01]: [Phase 01] BUG-01 (LOG-17, all 8 sites) fully resolved across Plans 01-01/01-02; REQUIREMENTS.md's BUG-01 checkbox/traceability corrected manually since the automated mark-complete verb didn't match its prior partial-progress annotation
- [Phase 01]: [Phase 01] BUG-09 (LOG-26, SettingsScreen's independent logout entry point) fixed, mirroring FeedScreen's already-shipped LOG-25 fix; phase-wide find-non-lambda-logs audit sweep and full build gate both clean across all ten touched files
- [Phase 01]: [Phase 01] Bug tracker closed out: LOG-18/20/26/28 advanced to applied-fix status (LOG-27 already advanced by Plan 01-01), LOG-17 moved to DONE.md, LOG-32/LOG-33 filed for two out-of-scope gaps found during implementation. All six phase-1 requirements now Complete in REQUIREMENTS.md
- [Phase 01]: Post-execution code review found a Critical gap (LOG-34): `Logger.e()` passed the raw `Throwable` to `Log.e()`, whose own stack-trace formatting bypasses `LogScrubber` entirely — widening the exposure this phase's whole point was to close. Fixed same-session, out of the three plans' original file scope: `LogScrubber.scrubThrowableForLogs()` (new) returns a replacement throwable with a scrubbed message and no cause; `LogScrubber.kt` also moved into `util/logging/` alongside `Logger`/`UmbraLog`. LOG-35 (untracked swallowed throwable + raw `e.message` in UI, `LoginViewModel`) and LOG-36 (dead-code catch, `SettingsScreen.kt`) filed but left open — out of this phase's ROADMAP success criteria
- [Phase 02]: [Phase 2 Plan 1] Per-relay-id Mutex (D-06) plus fresh RelayRepository.getRelayById read closes RelayCrudCoordinator's lost-update race (LOG-29/BUG-12) -- the lock alone wasn't sufficient since state.relays only resyncs via a 300ms-throttled collector
- [Phase 02]: [Phase 2 Plan 1] setDmEnabled's dmRelayListDirty set moved into the mapper, after the transport-rejection branch, so a rejected/no-op DM enable never claims the published DM list changed (LOG-31/BUG-14)
- [Phase 02]: [Phase 02] Plan 02-02 (LOG-21/BUG-05 snapshotEmitJob CAS-scheduled AtomicReference; LOG-19/BUG-03 a-tag deletions resolve against the in-memory cache too) closed out after an interrupted prior executor run left both task commits done but doc/state work unfinished -- verified against acceptance_criteria (fresh 31/31 test pass, compileDebugKotlin/lintDebug clean) rather than re-implemented
- [Phase 02]: [Phase 02] Plan 02-03 (LOG-22/BUG-06): deleteEvent's onOptimisticApply renamed onDeleteConfirmed and moved inside requestSignAndPublish's onSigned callback, alongside the cache/archive removal -- no pending-action/rollback machinery added since nothing is applied before confirmation; FeedViewModel.kt untouched (only ever passed onCacheRemoveFailure)
- [Phase 02]: [Phase 02] Plan 02-04 (LOG-30/BUG-13): audited all six NostrSessionManager job fields; retryJob/userBackfillJob/ownProfileBootstrapWatcherJob converted to AtomicReference<Job?> scheduled through two new non-blocking helpers (launchIfIdle/launchReplacing in new AtomicJobScheduling.kt); bootstrapJob/autoDisableRelayJob/torCircuitRecoveryJob stay plain, documented with an inline start()/stop()-serialization invariant. The 200-iteration real-thread concurrency test itself caught a compareAndSet-ordering race in the plan's literal launchIfIdle spec (isActive-keyed check left a window before start() landed) -- fixed by keying the idle check on isCompleted instead (Rule 1 auto-fix)
- [Phase 02]: [Phase 02] Plan 02-05 (LOG-23/BUG-07, LOG-24/BUG-08): FeedViewModel.muteUser's dead mute mirror now resolves via feedRepository.getActiveFilters().first().firstOrNull() (matching ProfileViewModel.toggleMute) instead of a by-id lookup against mergeActiveFeedFilters's fixed synthetic id; muteUser/togglePin's onSigned callbacks now capture and map their write's Result via two new top-level muteWriteResultMessage/pinWriteResultMessage functions reusing ProfileViewModel's exact error vocabulary (D-04). TDD RED/GREEN followed for Task 2. Phase 2 (all 5 plans) now complete.

### Pending Todos

None yet. (Project bug/backlog tracking lives in docs/KNOWN_ISSUES.md, docs/TODO.md, docs/DONE.md under the shared LOG-N counter, currently at LOG-36.)

### Blockers/Concerns

- Phase 4 REL-02: `assembleRelease` may not be runnable locally if release signing keys are absent — the R8-shaped `assembleBenchmark` is the documented fallback and the substitution must be recorded explicitly.

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-09-04T06:50:34.939Z
Stopped at: Completed 02-05-PLAN.md (Phase 2 complete, all 5 plans)
Resume file: None
