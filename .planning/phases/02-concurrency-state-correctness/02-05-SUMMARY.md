---
phase: 02-concurrency-state-correctness
plan: 05
subsystem: ui-feed
tags: [nostr, nip-51, mute-list, pin-list, feed-filter, unit-testing, tdd]

# Dependency graph
requires:
  - phase: 02-concurrency-state-correctness
    provides: "InteractionActionsCoordinator.requestSignAndPublish's onSigned callback shape (established in 02-01/02-03) and applyMuteChange/applyPinChange/mirrorMuteIntoActiveFilter (pre-existing coordinator primitives this plan reads the Result from, unchanged)"
provides:
  - "FeedViewModel.muteUser's local-filter mute mirror resolves the active filter via the live active-filters list, matching ProfileViewModel.toggleMute, so a feed-side mute actually lands in the active filter's local mutedPubkeys (LOG-23/BUG-07)"
  - "FeedViewModel.muteUser/togglePin surface a failed local write with the existing error vocabulary (error_mute_author/error_pin_note/error_unpin_note) instead of an unconditional success message (LOG-24/BUG-08)"
affects: [02-concurrency-state-correctness]

# Actuals (#2632)
actuals:
  tokens: 3114
  tasks: 3
  commits: 4

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Top-level internal pure-function extraction for testable decisions inside a 27-dependency, unconstructible-in-JVM-tests ViewModel — muteWriteResultMessage/pinWriteResultMessage placed beside the pre-existing shouldShowFeedInitialLoading"
    - "Result-checking + ResWithArgs error surfacing, converged from ProfileViewModel onto FeedViewModel for the same failure class (D-04)"

key-files:
  created: []
  modified:
    - app/src/main/java/com/umbra/app/ui/feed/FeedViewModel.kt
    - app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt
    - app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt
    - app/src/test/java/com/umbra/app/domain/feed/FeedFilterTest.kt
    - docs/KNOWN_ISSUES.md

key-decisions:
  - "muteUser's resolver lambda now calls feedRepository.getActiveFilters().first().firstOrNull() instead of feedRepository.getFilterById(activeFeedFilter.id) — the latter could never match because mergeActiveFeedFilters always stamps a fixed synthetic id ('merged_active') onto its result, never a persisted filter's id."
  - "InteractionActionsCoordinator.mirrorMuteIntoActiveFilter's resolveActiveFilter parameter was kept exactly as-is (caller-supplied) per the plan's explicit prohibition — only the KDoc's now-false claim that the two callers diverge was corrected, not the signature."
  - "muteWriteResultMessage has no unmute branch and never references error_unmute_author — FeedViewModel.muteUser only ever mutes, and that string stays reachable only from ProfileViewModel, confirmed by a zero-match grep over non-comment lines."
  - "Ordering was preserved exactly: applyMuteChange's result is captured into a local val at the same call-site position it already occupied, with mirrorMuteIntoActiveFilter still running immediately after it — the fix only changes what happens with the write's Result, not when either call runs."

patterns-established: []

requirements-completed: [BUG-07, BUG-08]

coverage:
  - id: D1
    description: "A feed-side mute reaches the active filter's local muted set via the same resolution ProfileViewModel.toggleMute uses (LOG-23/BUG-07)"
    requirement: "BUG-07"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/domain/feed/FeedFilterTest.kt#given a filter with a persisted-looking id when merging then the merged id is the fixed synthetic id, never the input's"
        status: pass
    human_judgment: false
  - id: D2
    description: "A failed mute write surfaces error_mute_author with the failure's message instead of the success message, and a null exception message formats as an empty string"
    requirement: "BUG-08"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt#given a failed mute write when mapping the result then returns the mute error message with the failure text"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt#given a failed mute write with a null exception message when mapping the result then the formatted argument is empty"
        status: pass
    human_judgment: false
  - id: D3
    description: "A failed pin/unpin write surfaces error_pin_note/error_unpin_note with the failure's message; successful pin/unpin still show note_pinned_success/note_unpinned_success unchanged"
    requirement: "BUG-08"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt#given a failed pin write when the note was previously unpinned then returns the pin error message with the failure text"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt#given a failed pin write when the note was previously pinned then returns the unpin error message with the failure text"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt#given a successful pin write when the note was previously unpinned then returns the pinned success message"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt#given a successful pin write when the note was previously pinned then returns the unpinned success message"
        status: pass
    human_judgment: false
  - id: D4
    description: "docs/KNOWN_ISSUES.md records LOG-23 and LOG-24 as fixed and awaiting on-device validation"
    requirement: ""
    verification:
      - kind: other
        ref: "grep -A 2 '^### LOG-23' docs/KNOWN_ISSUES.md and '^### LOG-24' both contain 'fix applied'"
        status: pass
    human_judgment: false

duration: ~3 minutes (commit-timestamp span 06:45:27Z-06:48:24Z; excludes required-reading and verification time not reflected in commit timestamps)
completed: 2026-09-04
status: complete
---

# Phase 2 Plan 5: Feed Mute Mirror Resolution and Write-Result Surfacing Summary

**FeedViewModel.muteUser's dead local-filter mute mirror now resolves via the live active-filters list (matching ProfileViewModel), and both muteUser/togglePin surface a failed local write with ProfileViewModel's exact error vocabulary instead of an unconditional success message — closing LOG-23/BUG-07 and LOG-24/BUG-08.**

## Performance

- **Duration:** ~3 min (commit-timestamp span; excludes required-reading and Gradle verification time)
- **Started:** 2026-09-04T06:45:27Z
- **Completed:** 2026-09-04T06:48:24Z
- **Tasks:** 3 (Task 2 followed RED/GREEN TDD)
- **Files modified:** 5

## Accomplishments

- `FeedViewModel.muteUser`'s `mirrorMuteIntoActiveFilter` resolver lambda now calls `feedRepository.getActiveFilters().first().firstOrNull()` instead of `feedRepository.getFilterById(activeFeedFilter.id)` — the by-id lookup could never match because `mergeActiveFeedFilters` always stamps its result with the fixed synthetic id `"merged_active"`, never a persisted filter's own id. A feed-side mute now actually lands in the active filter's local `mutedPubkeys` (LOG-23/BUG-07).
- `InteractionActionsCoordinator.mirrorMuteIntoActiveFilter`'s KDoc corrected: it no longer claims the two callers resolve the active filter by different strategies (no longer true), and now states the resolver parameter stays caller-supplied so the coordinator never decides what "active" means for a given screen. No code in that file changed.
- Added a `FeedFilterTest` case pinning the invariant that made the old lookup permanently dead: merging a filter with a persisted-looking id returns a filter whose id is the fixed synthetic id and never equal to the input's id, while still carrying the input's name through in the single-filter case.
- Added two top-level `internal` functions to `FeedViewModel.kt` (`muteWriteResultMessage`, `pinWriteResultMessage`), placed beside the file's existing `shouldShowFeedInitialLoading` extraction, since the ViewModel's 27 injected dependencies make it unconstructible in a JVM unit test. Both reuse `ProfileViewModel`'s exact error vocabulary and `result.exceptionOrNull()?.message ?: ""` expression (D-04) — no new copy invented.
- Wired both `muteUser`'s and `togglePin`'s `onSigned` callbacks to capture the local write's `Result<Unit>` and map it through the new functions, without reordering either write relative to the active-filter mirror or `requestSignAndPublish`.
- Followed RED → GREEN for Task 2 exactly: committed eight failing tests first (compile failure on the two unresolved function references), then added the two functions and wired the call sites, confirming all eight tests pass. No REFACTOR commit was needed — the GREEN implementation needed no cleanup.
- `docs/KNOWN_ISSUES.md`'s LOG-23 and LOG-24 entries updated to `fix applied — needs on-device validation` with `**Fix:**` lines describing each change; original body paragraphs (the historical record) left verbatim.
- Full verification passed: `./gradlew testDebugUnitTest --tests "com.umbra.app.ui.feed.*" --tests "com.umbra.app.domain.feed.*" --tests "com.umbra.app.ui.common.*"`, the full `./gradlew testDebugUnitTest`, `./gradlew compileDebugKotlin`, and `./gradlew lintDebug` all succeeded with no new warnings.

## Task Commits

1. **Task 1: The feed's mute mirror resolves the active filter the way that actually works (LOG-23/BUG-07)** - `077eebf` (fix)
2. **Task 2 (RED): Failing tests for mute/pin write-result message mapping** - `4ce2bd2` (test)
2. **Task 2 (GREEN): muteWriteResultMessage/pinWriteResultMessage implemented and wired in** - `bdbb774` (feat)
3. **Task 3: Record the LOG-23 and LOG-24 fixes in the bug tracker** - `05e678e` (docs)

**Plan metadata:** committed alongside this SUMMARY (see final close-out commit).

## Files Created/Modified

- `app/src/main/java/com/umbra/app/ui/feed/FeedViewModel.kt` - `muteUser`'s resolver lambda body changed; two new top-level `internal` functions (`muteWriteResultMessage`, `pinWriteResultMessage`) added; both `onSigned` callbacks in `muteUser`/`togglePin` now capture and map the write's `Result`
- `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt` - `mirrorMuteIntoActiveFilter`'s KDoc corrected (documentation-only, no code change)
- `app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt` - eight new tests covering `muteWriteResultMessage`/`pinWriteResultMessage`'s six behavior cases (success/failure/null-message for mute, success-pinned/success-unpinned/failure-pin/failure-unpin for pin)
- `app/src/test/java/com/umbra/app/domain/feed/FeedFilterTest.kt` - one new test pinning `mergeActiveFeedFilters`'s fixed-synthetic-id invariant
- `docs/KNOWN_ISSUES.md` - LOG-23 and LOG-24 marked "fix applied — needs on-device validation" with `**Fix:**` notes

## Decisions Made

- Followed the plan's task order exactly: Task 1 (LOG-23 resolver fix) first, Task 2 (LOG-24 result-mapping, TDD) second, Task 3 (docs) last.
- For Task 2's RED phase, committed the failing tests as their own `test(...)` commit before writing any implementation, per the plan's explicit TDD instruction — the compile failure (unresolved references to functions that didn't exist yet) is the RED signal in this codebase's testing setup, since the two new functions are pure and have no runtime-failure path to exercise before they exist.
- No REFACTOR commit: the GREEN implementation (two small `if/else` expressions plus two call-site edits) needed no follow-up cleanup.

## Deviations from Plan

None — plan executed exactly as written. All acceptance criteria (grep checks, test counts, diff scoping) were verified and matched before each commit.

## Issues Encountered

None.

## Known Stubs

None.

## Threat Flags

None — no new network surface, signing surface, or log line was introduced. The same `AmberSignerGateway.signEvent` round trip and NIP-51 publish run unchanged; this plan only changes what happens with a write's already-computed `Result` and how a resolver lambda looks up an already-existing filter. Consistent with `02-05-PLAN.md`'s `<threat_model>` (T-02-05-01 through T-02-05-04, all closed as scoped — the mute mirror activates a previously-dead code path that writes only into the user's own, normal, user-editable `FeedFilter` row, never a hidden app-side rule).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- This was the last plan in Phase 2 (Concurrency & State Correctness). All five plans (02-01 through 02-05) are now complete, closing BUG-03, BUG-05, BUG-06, BUG-07, BUG-08, BUG-12, BUG-13, BUG-14.
- LOG-23 and LOG-24 now sit in `docs/KNOWN_ISSUES.md` as "fix applied — needs on-device validation," consistent with this project's opt-in on-device-validation convention — no blocker for downstream phases.
- Phase-level verification (VERIFICATION.md, phase completion marking) is handled by the orchestrator as a separate step after this plan's close-out, per this run's instructions — not performed here.

## Self-Check: PASSED

- `app/src/main/java/com/umbra/app/ui/feed/FeedViewModel.kt`: FOUND, contains `muteWriteResultMessage`/`pinWriteResultMessage` and the corrected resolver lambda.
- `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt`: FOUND, KDoc corrected, no code change (`git diff` confined to comment lines).
- `app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt`: FOUND, contains the eight new mapping tests plus the two pre-existing loading-state tests.
- `app/src/test/java/com/umbra/app/domain/feed/FeedFilterTest.kt`: FOUND, contains the new synthetic-id invariant test.
- `docs/KNOWN_ISSUES.md`: FOUND, LOG-23 and LOG-24 both updated in place (not duplicated, not moved to DONE.md).
- Commit `077eebf`: FOUND in `git log`.
- Commit `4ce2bd2`: FOUND in `git log`.
- Commit `bdbb774`: FOUND in `git log`.
- Commit `05e678e`: FOUND in `git log`.
- Test run: `FeedFilterTest`, `FeedViewModelStateTest`, the broader `ui.feed.*`/`domain.feed.*`/`ui.common.*` selection, and the full `testDebugUnitTest` suite all `BUILD SUCCESSFUL`.
- `compileDebugKotlin` and `lintDebug`: both `BUILD SUCCESSFUL`, no new warnings.

---
*Phase: 02-concurrency-state-correctness*
*Completed: 2026-09-04*
