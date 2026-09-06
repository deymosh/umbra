---
phase: 02-concurrency-state-correctness
plan: 01
subsystem: concurrency
tags: [kotlin-coroutines, mutex, relay-config, unit-testing]

# Dependency graph
requires:
  - phase: 01-error-visibility-log-hygiene
    provides: scrubbed error-visibility logging conventions this phase's catch blocks already follow
provides:
  - "RelayCrudCoordinator.setDmEnabled only marks the DM relay list dirty when it actually changes a relay (LOG-31/BUG-14)"
  - "RelayCrudCoordinator.updateRelayRole's per-relay-id Mutex plus fresh RelayRepository.getRelayById read closes the lost-update race across all six per-role setters (LOG-29/BUG-12)"
  - "A RelayCrudCoordinatorTest harness (subject() factory + RecordingRelayRepository fake with enter/exit call-log and per-invocation gating) that later concurrency plans in this phase can copy"
affects: [02-concurrency-state-correctness]

# Actuals (#2632)
actuals:
  tokens: 4845
  tasks: 2
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Per-relay-id Mutex via ConcurrentHashMap<String, Mutex>.computeIfAbsent(id) { Mutex() } for a check-then-act relay mutation, keyed narrower than the shared cachedEventsMutex precedent"
    - "Fresh repository point read as the lock-scoped base snapshot instead of a throttled UI-mirror StateFlow, when the two can diverge"
    - "RecordingRelayRepository test fake with an ordered enter/exit call log plus per-invocation CompletableDeferred gating, to force two coroutines to genuinely overlap in a runTest rather than merely run sequentially"

key-files:
  created:
    - app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt
  modified:
    - app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt
    - app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt
    - docs/KNOWN_ISSUES.md

key-decisions:
  - "Per-relay-id Mutex (D-06), not a single coordinator-wide lock — toggles on different relays keep running concurrently"
  - "Fresh RelayRepository.getRelayById point read replaces the state.value.relays lookup inside the lock, since state.relays only resyncs via a 300ms-throttled collector and would still cause a lost update even with the lock alone"
  - "dmRelayListDirty=true set moved into the mapper lambda after the transport-rejection branch, so a rejected/no-op DM enable never claims the published list changed"

patterns-established:
  - "Per-relay-id Mutex + fresh repository read for check-then-act relay mutations"
  - "Genuinely-concurrent coroutine test pattern (per-invocation CompletableDeferred gate + ordered call log) for kotlinx-coroutines-test races"

requirements-completed: [BUG-12, BUG-14]

coverage:
  - id: D1
    description: "setDmEnabled only marks the DM relay list dirty when the mapper actually changes the relay (rejected ws:// enable leaves dmRelayListDirty false; accepted wss:// enable sets it true; unknown relayId leaves it false and persists nothing)"
    requirement: "BUG-14"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given a plaintext non-onion relay when setDmEnabled(true) runs then dmRelayListDirty stays false and the transport error is surfaced"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given a wss relay when setDmEnabled(true) runs then dmRelayListDirty is set and the persisted relay is DM-active"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given an unknown relayId when setDmEnabled(true) runs then dmRelayListDirty stays false and nothing is persisted"
        status: pass
    human_judgment: false
  - id: D2
    description: "Two concurrent role toggles on the same relay both land (no lost update); toggles on different relays are not falsely serialized against each other"
    requirement: "BUG-12"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given two overlapping role toggles on the same relay when both resolve then neither update is lost"
        status: pass
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt#given overlapping role toggles on different relays when advanced then the un-gated relay is not serialized behind the gated one"
        status: pass
    human_judgment: false

duration: 14min
completed: 2026-09-03
status: complete
---

# Phase 2 Plan 1: RelayCrudCoordinator Concurrency Tracer Summary

**Per-relay-id Mutex plus a fresh RelayRepository read closes RelayCrudCoordinator's lost-update race, and setDmEnabled's dirty flag now only flips on a real DM-role change — both proven by a new genuinely-concurrent RelayCrudCoordinatorTest harness.**

## Performance

- **Duration:** 14 min
- **Started:** 2026-09-03T21:39:33Z
- **Completed:** 2026-09-03T21:53:29Z
- **Tasks:** 2
- **Files modified:** 4 (1 created, 3 modified)

## Accomplishments
- Fixed LOG-31/BUG-14: `setDmEnabled`'s `dmRelayListDirty = true` now sets inside the mapper, only on the branch that actually returns a changed relay — a rejected non-`wss://`/non-`.onion` enable no longer falsely claims the DM relay list needs re-publishing.
- Fixed LOG-29/BUG-12: `updateRelayRole` now acquires a per-relay-id `Mutex` (`ConcurrentHashMap<String, Mutex>`) around its entire read-map-persist-plus-side-effects sequence, and reads its base relay via a fresh `RelayRepository.getRelayById(relayId)` point read instead of the throttled `state.value.relays` mirror — closing the race for all six per-role setters (`setOutboxEnabled`/`setInboxEnabled`/`setDmEnabled`/`setSearchEnabled`/`setIndexEnabled`/`setDiscoveredRelayEnabled`) at once.
- Established a `RelayCrudCoordinatorTest` harness (subject() factory + `RecordingRelayRepository` fake with an ordered enter/exit call log and per-invocation `CompletableDeferred` gating) that forces genuinely-overlapping coroutines in a `runTest`, for later concurrency plans in this phase to reuse.
- Confirmed by manual regression: reverting `updateRelayRole` to its pre-fix, unlocked shape makes the same-relay concurrency test fail (two "enter" markers land before either "exit"), proving the fix is load-bearing, not just plausible.

## Task Commits

Each task was committed atomically:

1. **Task 1: DM dirty flag only flips when the mapper actually changes the relay (LOG-31/BUG-14)** - `5007f32` (fix)
2. **Task 2: Per-relay-id Mutex plus fresh point read closes the lost-update race (LOG-29/BUG-12)** - `710268f` (fix)

**Bug tracker update:** `65ce67c` (docs: mark LOG-29/LOG-31 fix applied)

## Files Created/Modified
- `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` - New test harness: `subject()` factory, `RecordingRelayRepository` fake (call log + gating), 5 tests covering both bugs
- `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt` - `setDmEnabled`'s dirty-flag move; `updateRelayRole`'s per-relay Mutex + fresh repository read; new `relayRepository` constructor param
- `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt` - Wires its already-injected `relayRepository` into `RelayCrudCoordinator`'s construction
- `docs/KNOWN_ISSUES.md` - LOG-29/LOG-31 marked "fix applied — needs on-device validation" with Fix notes

## Decisions Made
- Per-relay-id `Mutex` (D-06), not a single coordinator-wide lock — verified via the different-relay test that toggles on relay B complete while relay A's write is still gated.
- The lock alone doesn't close the race: `updateRelayRole`'s base-relay read had to move from `state.value.relays` (a 300ms-throttled UI mirror) to `relayRepository.getRelayById(relayId)` (the persisted source of truth), confirmed necessary by the manual pre-fix-code regression check.
- `dmRelayListDirty`'s set stays inside the mapper (not hoisted out), matching the other five setters' unconditional-because-always-changing shape, per the plan's explicit prohibition against altering their behavior.

## Deviations from Plan

None - plan executed exactly as written. Both tasks matched their `<action>` specs; no Rule 1-4 deviations were needed.

## Issues Encountered
None. The Kotlin compiler's pre-existing `ExperimentalCoroutinesApi` opt-in warning on `advanceUntilIdle()` calls appears identically across every test file in this module (confirmed via a forced recompile of `InteractionActionsCoordinatorTest.kt` and others) — not something introduced by this plan, and not treated as a failure since the project has no `-Werror`.

## Known Stubs

None.

## Threat Flags

None — both fixes operate on already-existing, already-Tor-routed local relay-configuration code paths; no new network surface, signing surface, or data exposure was introduced. The `isDmTransportAllowed` rejection stays the first statement inside `setDmEnabled`'s mapper, unweakened and unreordered.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `RelayCrudCoordinatorTest`'s `subject()`/`RecordingRelayRepository` pattern is ready for Wave 2's parallel plans (LOG-19/21/22/23/24/30) to copy for their own `kotlinx-coroutines-test` concurrency coverage, per D-07.
- LOG-29/LOG-31 sit in `docs/KNOWN_ISSUES.md` as "fix applied — needs on-device validation," consistent with the project's opt-in on-device validation convention — no blocker for downstream plans.
- No blockers for Wave 2.

## Self-Check: PASSED

All created/modified files and all task/docs/summary commit hashes verified present on disk and in git log.

---
*Phase: 02-concurrency-state-correctness*
*Completed: 2026-09-03*
