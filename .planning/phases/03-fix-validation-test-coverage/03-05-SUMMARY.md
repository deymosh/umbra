---
phase: 03-fix-validation-test-coverage
plan: 05
subsystem: testing
tags: [kotlin, coroutines, junit4, threads, relay, concurrency]

requires:
  - phase: 02-concurrency-state-correctness
    provides: "UmbraNostrClient.dialingRelays per-relay dial guard and the onWebSocketOpen superseded-socket identity check, both already shipped as the LOG-12 fix"
provides:
  - "A brand-new UmbraNostrClientTest.kt with three cases pinning both halves of the LOG-12 same-relay dial-race fix"
  - "Exact new test method names for Plan 03-07 to cite when moving LOG-12 from KNOWN_ISSUES.md to DONE.md"
affects: [03-07]

actuals:
  tokens: 1945
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Real java.util.Thread + CountDownLatch pair (not a coroutine test dispatcher) to force a genuine two-thread overlap through a synchronous, non-suspending guarded region"
    - "A latch-blocking WebSocket double whose close() gates on entered/release latches and then throws a marker exception, so the held dial aborts before any transport call is reached"

key-files:
  created:
    - app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt
  modified: []

key-decisions:
  - "No production file touched -- both halves of the already-shipped LOG-12 fix get coverage-only additions, verified by git status --porcelain app/src/main being empty after every commit"
  - "Split the plan's two tasks into two separate commits even though both touch the same new file: Task 1 created the file with its two onWebSocketOpen cases, Task 2 added the third (concurrent-dial-guard) case on top -- keeps the per-task atomic-commit contract intact despite the file being created and extended within one plan"

patterns-established: []

requirements-completed: [VALID-08]

coverage:
  - id: D1
    description: "A relay-open callback for a socket no longer registered for that relay url closes the stale socket and leaves the relay unmarked as active, with no connected issue emitted for it"
    requirement: VALID-08
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt#given a superseded socket when its open callback arrives late then it is closed and the relay is not marked active"
        status: pass
    human_judgment: false
  - id: D2
    description: "A relay-open callback for the currently-registered socket marks the relay active, emits a connected issue, and clears the relay's accumulated failure backoff (failure count and cooldown)"
    requirement: VALID-08
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt#given the current socket when its open callback arrives then the relay is marked active and its failure backoff is cleared"
        status: pass
    human_judgment: false
  - id: D3
    description: "While a dial for a relay url is genuinely in flight on another thread, a second concurrent dial for the same url returns immediately with no relay issue of its own, and the in-flight guard is released once the held dial finishes (even on failure)"
    requirement: VALID-08
    verification:
      - kind: unit
        ref: "app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt#given a dial already in flight for a relay when a second concurrent connect for the same relay runs then it no-ops instead of starting its own dial"
        status: pass
    human_judgment: false

duration: ~20min
completed: 2026-09-05
status: complete
---

# Phase 3 Plan 5: UmbraNostrClient Dial-Race Test Coverage Summary

**A brand-new `UmbraNostrClientTest.kt`, using a real second thread plus a latch-gated WebSocket double, pins both halves of LOG-12's same-relay dial-race fix — the superseded-socket identity check and the per-relay in-flight dial guard — against already-shipped production code, zero production diff.**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-09-05T12:59:54Z
- **Completed:** 2026-09-05T13:05:00Z
- **Tasks:** 2
- **Files created:** 1

## Accomplishments

- LOG-12 identity-check half: two new cases prove `onWebSocketOpen` closes a superseded socket (one close call, code 1000), leaves the relay unmarked active, and emits no `CONNECTED` issue for it; and that the callback for the currently-registered socket marks the relay active, clears `relayFailureCount`/`relayCooldownUntil` for that url, and does emit a `CONNECTED` issue.
- LOG-12 guard half: one new case forces a genuine two-thread race against `UmbraNostrClient.connect()` — a real `Thread` calling `connect()` is held inside the guarded region by a `LatchBlockingWebSocket` whose `close()` gates on an `entered`/`release` `CountDownLatch` pair before throwing a marker exception. While held, a second concurrent `connect()` call for the same relay url is asserted to emit zero relay issues; after the held dial fails and the thread joins, `dialingRelays` is asserted to no longer contain the url, proving the guard releases even on the failure path.
- Deliberately used a real thread instead of two coroutines on `runTest`'s single-threaded test dispatcher, per both the plan and `CONTEXT.md` D-02 — `connect()` is fully synchronous with no suspension point, so virtual time could never genuinely interleave two calls to it; only a real second thread makes the race provable rather than merely asserted.
- `TorProxyConfig`'s process-global readiness flag is set and reset inside a `try`/`finally` around the guard-race case, so no other test class in the same JVM ever observes it mutated — verified by running the whole class twice in a row with identical results.

## Task Commits

Each task was committed atomically:

1. **Task 1: New test file — a superseded socket's late open callback is ignored (LOG-12, identity-check half)** - `4224468` (test)
2. **Task 2: A second concurrent dial for the same relay no-ops while one is in flight (LOG-12, guard half)** - `a076d8a` (test)

## Files Created/Modified
- `app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt` (new) - Three cases: two constructing the client directly and invoking the internal `onWebSocketOpen` extension function, one racing a real background thread against the main test thread through `UmbraNostrClient.connect()`'s dial-in-flight guard.

## Decisions Made
- No production file touched in either task — verified via `git status --porcelain app/src/main` (empty) after every commit, per the plan's explicit prohibition.
- Split the plan's two tasks into two commits despite both editing the same new file: wrote the Task-1-only content (two cases, no `TorProxyConfig`/thread/latch machinery) first, verified and committed it, then added Task 2's third case and the `LatchBlockingWebSocket` double on top as a second commit — preserves the per-task atomic-commit contract without collapsing tracer-task and expansion-task work into one commit.
- Used the exact literal `wss://relay.invalid` (and `https://relay.invalid` for the synthetic switching-protocols `Response`'s `Request`) as the only host anywhere in the file — confirmed by `grep -noE '"(wss?|https?)://[^"]*"'` showing exactly those two matches, both against the reserved, non-resolvable `relay.invalid` domain, so no code path in this file can ever reach a real network destination even if a future refactor accidentally removed the `LatchBlockingWebSocket`'s abort-before-transport guarantee.

## Deviations from Plan

None — plan executed exactly as written. All three method names match the plan's specified strings character for character.

## Issues Encountered
None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- The three method names above are ready for Plan 03-07 to cite verbatim when moving LOG-12 from `docs/KNOWN_ISSUES.md` to `docs/DONE.md`.
- `UmbraNostrClientTest.kt` has 3 passing cases (`./gradlew testDebugUnitTest --tests "com.umbra.app.data.nostr.UmbraNostrClientTest"`), completing in ~0.14s total (well under the plan's 30s ceiling); the whole class was re-run twice in a row with identical results. `compileDebugKotlin` and `lintDebug` both succeed with no new warnings.
- No blockers for downstream plans.

---
*Phase: 03-fix-validation-test-coverage*
*Completed: 2026-09-05*

## Self-Check: PASSED

- FOUND: app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt
- FOUND: .planning/phases/03-fix-validation-test-coverage/03-05-SUMMARY.md
- FOUND: 4224468 (Task 1 commit)
- FOUND: a076d8a (Task 2 commit)
