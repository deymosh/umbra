---
phase: 01-error-visibility-log-hygiene
plan: 02
subsystem: logging
tags: [kotlin, error-visibility, log-hygiene, privacy]

# Dependency graph
requires: ["01-error-visibility-log-hygiene/01-01"]
provides:
  - "All eight BUG-01 sites now log at error level with the throwable attached (six landed here, two in Plan 01-01)"
  - "EventRepositoryImpl's feed-EOSE and channel-reapply relay-URL interpolations scrubbed; NegentropySyncOrchestrator's NIP-77 failure exception message scrubbed"
  - "EventRepositoryImpl.clearAllData()'s disconnectFromAll() failure now logs the throwable instead of vanishing silently, with the wipe still continuing"
  - "LoginViewModel's two session-activation handlers log their exception while preserving the existing two-layer handler structure and login success semantics"
affects: [01-error-visibility-log-hygiene/01-03]

actuals:
  tokens: 9500
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Six self-constructing-logger classes (no constructor seam) verified by compile + lint + precise grep/awk source assertions instead of a FakeUmbraLogger unit test, matching the plan's own documented verification-strategy split for classes that can't take an injected UmbraLogger without a broader DI refactor"

key-files:
  modified:
    - app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt
    - app/src/main/java/com/umbra/app/data/nostr/UmbraNostrClient.kt
    - app/src/main/java/com/umbra/app/data/nostr/RelayMessageHandling.kt
    - app/src/main/java/com/umbra/app/data/nostr/RelayWebSocketListener.kt
    - app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt
    - app/src/main/java/com/umbra/app/data/repository/NegentropySyncOrchestrator.kt
    - .planning/REQUIREMENTS.md

key-decisions:
  - "BUG-01 traceability correction: the automated requirements.mark-complete verb left BUG-01's checkbox untouched (its prior text carried a '2 of 8 sites done' progress annotation that didn't match the tool's completion pattern), while BUG-02/BUG-04/BUG-11 applied cleanly. Manually corrected REQUIREMENTS.md's BUG-01 checkbox and traceability row to Complete after confirming all eight LOG-17 sites now log at error level with a throwable (verified by grep across every touched file, not just this plan's six)."
  - "CRLF line-ending quirk in UmbraNostrClient.kt: the plan's own awk verification gate (awk '/internal fun logWebSocketFailure/,/^    }$/' ...) doesn't match its own end-pattern against this file's pre-existing CRLF line endings, so a literal run of that exact gate over-captures past the function. Re-ran the same awk with \\r stripped first (tr -d '\\r' | awk ...), which returned the expected count of 1 (only the surviving SOCKS-transient logger.d line). The underlying code change itself is a single-line diff, confirmed separately by git diff --stat showing 1 insertion/1 deletion for that file — not a functional issue, just a gate command that needs \\r-stripping on this file."

requirements-completed: [BUG-01, BUG-02, BUG-04, BUG-11]

coverage:
  - id: T1
    description: "LoginViewModel's three outer failure handlers (anonymous login, save public key, logout) promoted to logger.e(e) with static messages; both session-activation handlers un-silenced to logger.e(e) { \"Session activation failed\" } while keeping the inner try/catch and login's existing success flow (nostrSessionController.start(), authenticated-state update) unchanged"
    requirement: "BUG-01, BUG-11"
    verification:
      - kind: other
        ref: "grep -c 'logger\\.e(e)' LoginViewModel.kt == 5; grep -c '_: *Exception' == 0; grep -c 'scrubThrowableMessageForLogs' == 0; grep -c 'activateUserSession' == 2; git diff shows nostrSessionController.start() unchanged after both session-activation blocks"
        status: pass
      - kind: other
        ref: "./gradlew compileDebugKotlin && ./gradlew lintDebug"
        status: pass
    human_judgment: false
  - id: T2
    description: "UmbraNostrClient's general WebSocket-error branch, RelayMessageHandling's message-processing failure handler, and RelayWebSocketListener's drain-coroutine failure handler all promoted to logger.e(throwable/e) with the throwable attached; the SOCKS-transient branch in logWebSocketFailure left byte-unchanged (still logger.d, still early-returns)"
    requirement: "BUG-01"
    verification:
      - kind: other
        ref: "git diff --stat for UmbraNostrClient.kt: 1 file changed, 1 insertion(+), 1 deletion(-); tr -d '\\r' | awk over logWebSocketFailure shows exactly 1 remaining logger.d (the SOCKS branch); scrubThrowableMessageForLogs counts unchanged at 2 (import + SOCKS-branch val); RelayMessageHandling.kt and RelayWebSocketListener.kt each show scrubUrlForLogs retained and scrubThrowableMessageForLogs import removed"
        status: pass
      - kind: other
        ref: "./gradlew compileDebugKotlin && ./gradlew lintDebug"
        status: pass
    human_judgment: false
  - id: T3
    description: "EventRepositoryImpl's feed-EOSE and channel-reapply debug logs now scrub the relay URL via scrubUrlForLogs (level unchanged); clearAllData()'s disconnectFromAll() catch now logs the throwable at error level naming only the failed step, with the wipe sequence continuing afterward exactly as before; NegentropySyncOrchestrator's NIP-77 sync-failure log now scrubs the exception message via LogScrubber"
    requirement: "BUG-02, BUG-04"
    verification:
      - kind: unit
        ref: "./gradlew testDebugUnitTest (full suite) — EventRepositoryIngestionIntegrationTest (11 tests) and NegentropySyncOrchestratorTest (13 tests) both pass with zero failures"
        status: pass
      - kind: other
        ref: "grep -cE 'relay \\$relayUrl' EventRepositoryImpl.kt == 0; grep -c scrubUrlForLogs == 3; grep -c 'logger\\.e(e)' == 3 (2 pre-existing + 1 new disconnectFromAll handler); grep -cF '\\${e.message}' NegentropySyncOrchestrator.kt == 0; git diff shows no return added inside the disconnect handler and no scrubPubkeyForLogs/scrubUrlForLogs interpolated into the wipe-path message itself"
        status: pass
      - kind: other
        ref: "./gradlew lintDebug"
        status: pass
    human_judgment: false
---

# Phase 1 Plan 2: Relay-Transport, Repository, and Login Error-Visibility Summary

**Six BUG-01 sites in self-constructing-logger classes, both BUG-02 scrubbing gaps, BUG-04's silent wipe-path disconnect handler, and BUG-11's two swallowed session-activation failures all now log at error level with the throwable attached and relay URLs scrubbed — verified by compile, lint, full test suite, and precise source-assertion grep gates since none of the six touched classes has a logger constructor seam.**

## Performance

- **Tasks:** 3
- **Files modified:** 7 (6 production, 1 planning doc)
- **Commits:** 3

## Accomplishments

- `LoginViewModel`'s three outer failure handlers (`loginAnonymously`, `savePublicKey`, `logout`) promoted from `logger.d` with manual throwable-message re-scrubbing to `logger.e(e) { "<static description>" }` — the manual re-scrub is redundant since `Logger.e()` already scrubs and appends the throwable's message internally.
- `LoginViewModel`'s two session-activation catches (around `eventRepository.activateUserSession(...)` in both `loginAnonymously()` and `savePublicKey()`) now log the caught exception at error level instead of discarding it — the inner try/catch layer is unchanged, so a hydration failure still doesn't block login itself; `nostrSessionController.start()` and the authenticated-state update still run right after, exactly as before.
- `UmbraNostrClient.logWebSocketFailure`'s general (non-SOCKS) WebSocket-error branch now logs at error level with the throwable attached; the SOCKS-transient branch stays at debug level with its early return untouched, since it feeds the existing short fixed-retry path and was deliberately excluded from BUG-01's eight-site count.
- `RelayMessageHandling.kt`'s message-processing failure handler and `RelayWebSocketListener.kt`'s drain-coroutine failure handler (both logging through `UmbraNostrClient`'s shared `logger` field) promoted to `logger.e(e)`/`client.logger.e(e)`, keeping their existing `scrubUrlForLogs(relayUrl)` wrapping since URL scrubbing is a separate concern from the throwable.
- `EventRepositoryImpl`'s feed-EOSE and channel-reapply debug logs now scrub the interpolated relay URL via `scrubUrlForLogs` instead of embedding it raw — level unchanged, scrubbing-only fix.
- `EventRepositoryImpl.clearAllData()`'s `disconnectFromAll()` catch no longer swallows its exception silently — it now logs the throwable at error level with a message naming only the failed step (no relay list, no pubkey, no row count), and the wipe sequence still runs its remaining steps afterward.
- `NegentropySyncOrchestrator`'s NIP-77 per-relay sync-failure log now routes the exception message through `LogScrubber.scrubThrowableMessageForLogs` instead of interpolating `e.message` raw, since an OkHttp/WebSocket exception message can itself embed the target relay host.
- Every orphaned `LogScrubber` throwable-message import removed across the six files where it became unused; every still-needed import (URL scrub, pubkey scrub) retained and confirmed by grep count.
- `REQUIREMENTS.md`'s BUG-01 entry corrected from "in progress (2/8 sites)" to complete now that this plan's six sites join Plan 01-01's two.

## Task Commits

Each task was committed atomically:

1. **Task 1: LoginViewModel — three outer failure handlers promoted, two session-activation handlers un-silenced** - `2657f0f` (fix)
2. **Task 2: Relay transport trio — WebSocket failure, message processing, listener drain** - `c4c84b0` (fix)
3. **Task 3: Repository scrubbing gaps and the silent wipe-path handler** - `f1dc03b` (fix)

## Files Created/Modified

- `app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt` - Five handlers promoted/un-silenced to `logger.e(e)`; orphaned `scrubThrowableMessageForLogs` import removed
- `app/src/main/java/com/umbra/app/data/nostr/UmbraNostrClient.kt` - `logWebSocketFailure`'s non-SOCKS branch promoted to `logger.e(throwable)`; SOCKS branch untouched
- `app/src/main/java/com/umbra/app/data/nostr/RelayMessageHandling.kt` - Message-processing failure handler promoted to `logger.e(e)`; orphaned import removed
- `app/src/main/java/com/umbra/app/data/nostr/RelayWebSocketListener.kt` - Drain-coroutine failure handler promoted to `client.logger.e(e)`; orphaned import removed
- `app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt` - Two relay-URL interpolations scrubbed; wipe-path disconnect failure now logged
- `app/src/main/java/com/umbra/app/data/repository/NegentropySyncOrchestrator.kt` - NIP-77 sync-failure exception message scrubbed via `LogScrubber`
- `.planning/REQUIREMENTS.md` - BUG-01/BUG-02/BUG-04/BUG-11 marked complete (BUG-01's checkbox and traceability row corrected manually, see Decisions Made)

## Decisions Made

- **BUG-01 traceability correction:** the automated `requirements mark-complete` verb applied cleanly for BUG-02/BUG-04/BUG-11 but left BUG-01 untouched — its prior checkbox text carried a "2 of 8 sites done" progress annotation from Plan 01-01 that didn't match the tool's completion-detection pattern. Manually verified all eight LOG-17 sites now log at error level with a throwable attached (grep across every one of `PublishEventUseCases.kt`, `LoginViewModel.kt`, `UmbraNostrClient.kt`, `RelayMessageHandling.kt`, `RelayWebSocketListener.kt`), then corrected `REQUIREMENTS.md`'s BUG-01 checkbox and traceability row to Complete by hand.
- **CRLF-aware verification gate:** `UmbraNostrClient.kt` has pre-existing CRLF line endings. The plan's own literal awk verification command (`awk '/internal fun logWebSocketFailure/,/^    }$/' ... | grep -c 'logger\.d'`) doesn't match `^    }$` against a line ending in `\r\n`, so it over-captures past the function boundary when run as written. Re-ran the identical logic with `\r` stripped first (`tr -d '\r' < file | awk ...`), which correctly returned `1` (only the surviving SOCKS-transient `logger.d` line). This is a pre-existing file-encoding quirk unrelated to this plan's edit — the actual code change is a single-line diff, independently confirmed by `git diff --stat` showing exactly 1 insertion/1 deletion for that file.

## Deviations from Plan

None - plan executed exactly as written. Both items in Decisions Made are verification-process clarifications, not deviations from any task's `<action>` or `<acceptance_criteria>`.

## Issues Encountered

None beyond the CRLF gate-command quirk noted above, which didn't block or alter any code change.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- All eight BUG-01 sites, all three BUG-02 sites, BUG-04, and BUG-11 are now fully resolved and verified by compile + lint + full test suite + precise grep/diff assertions.
- BUG-09 (LOG-26, `SettingsScreen.kt`'s independent logout entry point) remains for Plan 01-03, which also owns the phase's remaining bug-tracking-doc updates.
- No blockers for Plan 01-03.

---
*Phase: 01-error-visibility-log-hygiene*
*Completed: 2026-09-03*

## Self-Check: PASSED

All 6 modified production files confirmed present on disk with expected content; all 3 commit hashes (`2657f0f`, `c4c84b0`, `f1dc03b`) confirmed in `git log`.
