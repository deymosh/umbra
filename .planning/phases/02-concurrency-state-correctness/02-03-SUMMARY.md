---
phase: 02-concurrency-state-correctness
plan: 03
subsystem: concurrency
tags: [amber-signing, nip-09, delete, commit-after-confirm, unit-testing]

# Dependency graph
requires:
  - phase: 02-concurrency-state-correctness
    provides: "InteractionActionsCoordinator.requestSignAndPublish's onSigned/onRejected callback shape (02-01's established sign-then-publish primitive), reused unchanged as the mechanism deleteEvent now routes through"
provides:
  - "InteractionActionsCoordinator.deleteEvent commits its caller's state-removal callback and its local cache/archive removal only from requestSignAndPublish's onSigned callback — a rejected or failed Amber delete signature leaves state.notes, the in-memory EventIngestCache, and the encrypted Room archive all untouched (LOG-22/BUG-06)"
affects: [02-concurrency-state-correctness]

# Actuals (#2632)
actuals:
  tokens: 2971
  tasks: 3
  commits: 4

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Commit-only-after-Amber-confirms applied to the one remaining outlier (deleteEvent) that still fired before signature confirmation — now every mutation InteractionActionsCoordinator performs, for both callers, commits only from requestSignAndPublish's onSigned callback"

key-files:
  created: []
  modified:
    - app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt
    - app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt
    - app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt
    - docs/KNOWN_ISSUES.md

key-decisions:
  - "No pending-action or rollback machinery was introduced (per D-01) — nothing is applied ahead of confirmation, so there is nothing to roll back. The fix is purely a reordering: two statements that fired unconditionally before requestSignAndPublish now fire from inside its onSigned lambda instead."
  - "The cache/archive removal (removeDeletedNoteFromCacheUseCase) is awaited inline inside onSigned rather than launched as a separate scope.launch, so the local cache/archive is consistent before the signed delete is broadcast to relays."
  - "FeedViewModel.kt was not modified — it only ever passed onCacheRemoveFailure (never onOptimisticApply), so its call site needed no signature change; its cache-removal timing now follows the same confirmation gate purely through the shared coordinator change. Confirmed via git diff (empty) and a repo-wide grep for other deleteEvent call sites (only two: ProfileViewModel and FeedViewModel)."

patterns-established: []

requirements-completed: [BUG-06]

coverage:
  - id: D1
    description: "When Amber rejects or fails a delete signature, the note stays visible, the in-memory cache and encrypted archive are untouched, and zero publishes are tracked (BUG-06/LOG-22)"
    requirement: "BUG-06"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt#given amber rejects the delete when deleteEvent runs then onDeleteConfirmed never fires and the cache and archive are untouched"
        status: pass
    human_judgment: false
  - id: D2
    description: "When Amber confirms the delete signature, both the caller's state-removal callback and the cache/archive removal run only after that confirmation, not before"
    requirement: "BUG-06"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt#given amber signs the delete when deleteEvent runs then onDeleteConfirmed and the cache removal fire only after the sign resolves"
        status: pass
    human_judgment: false
  - id: D3
    description: "deleteEvent still returns early without signing, state mutation, or cache removal when the owner check fails"
    requirement: "BUG-06"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt#given deleteEvent's owner check fails then neither the sign round trip nor onDeleteConfirmed fire"
        status: pass
    human_judgment: false

duration: ~6 minutes (commit-timestamp span; excludes required-reading and verification time not reflected in commit timestamps)
completed: 2026-09-04
status: complete
---

# Phase 2 Plan 3: Confirmation-Gated NIP-09 Delete Summary

**InteractionActionsCoordinator.deleteEvent no longer removes a note or its cache/archive entry before Amber confirms the delete signature — both now fire only from requestSignAndPublish's onSigned callback, closing BUG-06/LOG-22 with no rollback machinery, pinned by new confirmed- and rejected-path tests.**

## Performance

- **Task commit timestamps (from git):** `100a16f` at 2026-09-04T06:13:54Z (Task 1), `33e54fb` at 06:14:24Z (Task 2), `cc1a509` at 06:17:10Z (Task 3), `3463eb7` at 06:17:43Z (docs close-out) — roughly 6 minutes span across the four commits; excludes required-reading and per-task Gradle verification time, which is not reflected in commit timestamps alone.
- **Tasks:** 3 (all executed this session)
- **Files modified:** 4 (`InteractionActionsCoordinator.kt`, `ProfileViewModel.kt`, `InteractionActionsCoordinatorTest.kt`, `docs/KNOWN_ISSUES.md`)

## Accomplishments

- `InteractionActionsCoordinator.deleteEvent`'s `onOptimisticApply` parameter renamed to `onDeleteConfirmed`; its body and `removeDeletedNoteFromCacheUseCase`'s cache/archive removal both now run only from `requestSignAndPublish`'s `onSigned` lambda. The two statements that previously fired unconditionally before the sign round trip (`onOptimisticApply()` and a separate `scope.launch { removeDeletedNoteFromCacheUseCase(...) }`) are gone.
- Class-level KDoc corrected: it no longer claims one caller commits optimistically and rolls back on failure — every mutation this coordinator performs now commits only after Amber confirms the signature.
- `ProfileViewModel.deleteEvent`'s named argument renamed to `onDeleteConfirmed` (callback body unchanged — still filters `state.notes` by event id). The four-line comment describing the prior unconditional, never-rolled-back removal as a tracked known bug was deleted, since that behavior no longer exists.
- Confirmed `FeedViewModel.kt` needed no change (`git diff` empty) and that the coordinator's `deleteEvent` has exactly two call sites (`ProfileViewModel`, `FeedViewModel`) via a repo-wide grep.
- Rewrote `InteractionActionsCoordinatorTest`'s delete coverage: added an `eventRepository` parameter to `subject()` (backing both `PublishSignedEventUseCase` and `RemoveDeletedNoteFromCacheUseCase` with a single `FakeEventRepository` instance so `deletedEventId` is observable), replaced the test asserting the old synchronous-optimistic-apply ordering with one asserting the opposite (unfired before `advanceUntilIdle()`, fired and `deletedEventId` set after), and added a rejected-sign test asserting the callback never fires, `deletedEventId` stays null, and zero publishes are tracked. The owner-check test was retained with only its parameter name updated.
- `docs/KNOWN_ISSUES.md`'s LOG-22 entry updated to `fix applied — needs on-device validation` with a `**Fix:**` note describing the change.
- Full verification passed: `./gradlew compileDebugKotlin` succeeds; `./gradlew testDebugUnitTest --tests "com.umbra.app.ui.common.InteractionActionsCoordinatorTest"` and the broader `--tests "com.umbra.app.ui.common.*" --tests "com.umbra.app.ui.profile.*"` both pass; `./gradlew lintDebug` succeeds with no new warnings; `grep -rn "onOptimisticApply" app/src/main/java app/src/test/java` returns no matches.

## Task Commits

1. **Task 1: deleteEvent commits only from requestSignAndPublish's onSigned callback (D-01, D-02)** - `100a16f` (fix)
2. **Task 2: ProfileViewModel's delete call site adopts the renamed callback and loses its stale bug comment** - `33e54fb` (fix)
3. **Task 3: Coordinator tests pin the confirmation gate for both the signed and the rejected path** - `cc1a509` (test)

**Bug tracker update:** `3463eb7` (docs: mark LOG-22 fix applied)

**Plan metadata:** committed alongside this SUMMARY (see final close-out commit).

## Files Created/Modified

- `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt` - `deleteEvent`'s `onOptimisticApply` renamed to `onDeleteConfirmed` and moved inside `onSigned`; cache/archive removal moved inside the same `onSigned` lambda; class KDoc and `deleteEvent`'s own KDoc corrected
- `app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt` - `deleteEvent`'s named argument renamed to `onDeleteConfirmed`; stale known-bug comment removed
- `app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt` - `subject()` factory gained an `eventRepository` parameter; delete tests rewritten (1 replaced, 1 added, 1 parameter-renamed); class KDoc's stale "genuinely different optimistic-update timing" sentence corrected
- `docs/KNOWN_ISSUES.md` - LOG-22 marked "fix applied — needs on-device validation" with a `**Fix:**` note

## Decisions Made

- Followed the plan's task order exactly: coordinator change first (deliberately leaving `ProfileViewModel` uncompiled, verified via the expected `compileDebugKotlin` failure named in Task 1's acceptance criteria), then the call-site update, then tests.
- Kept the cache/archive removal as an inline suspend call inside `onSigned` (not a separate `scope.launch`), so it completes before `publishSignedEvent` runs — matching the plan action's explicit instruction and keeping local state consistent before the signed delete reaches relays.

## Deviations from Plan

None — plan executed exactly as written. No Rule 1-4 auto-fixes were needed; the only compile failure encountered (`ProfileViewModel`'s stale named argument after Task 1) was explicitly predicted and required by the plan's own acceptance criteria, not an unplanned deviation.

## Issues Encountered

None.

## Known Stubs

None.

## Threat Flags

None — no new network surface, signing surface, or log line was introduced. The same `AmberSignerGateway.signEvent` round trip runs; only the ordering of local effects around it changed, exactly as scoped by `02-03-PLAN.md`'s `<threat_model>` (T-02-03-01 through T-02-03-04, all closed by this change).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LOG-22 now sits in `docs/KNOWN_ISSUES.md` as "fix applied — needs on-device validation," consistent with this project's opt-in on-device-validation convention — no blocker for downstream plans.
- `FeedViewModel.kt` remains untouched, as required by this plan's prohibition — Plan 02-05 (which owns that file) is unaffected.
- No blockers for the remaining Phase 2 plans (02-04, 02-05).

## Self-Check: PASSED

- `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt`: FOUND, contains `onDeleteConfirmed` parameter and `onSigned`-gated removal.
- `app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt`: FOUND, `deleteEvent` uses `onDeleteConfirmed`, stale comment removed.
- `app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt`: FOUND, contains the new confirmed-path and rejected-path delete tests.
- `docs/KNOWN_ISSUES.md`: FOUND, LOG-22 updated.
- Commit `100a16f`: FOUND in `git log`.
- Commit `33e54fb`: FOUND in `git log`.
- Commit `cc1a509`: FOUND in `git log`.
- Commit `3463eb7`: FOUND in `git log`.
- Test run: `InteractionActionsCoordinatorTest` passed with no failures (`./gradlew testDebugUnitTest --tests "com.umbra.app.ui.common.*" --tests "com.umbra.app.ui.profile.*"` BUILD SUCCESSFUL).
- `compileDebugKotlin` and `lintDebug`: both BUILD SUCCESSFUL.

---
*Phase: 02-concurrency-state-correctness*
*Completed: 2026-09-04*
