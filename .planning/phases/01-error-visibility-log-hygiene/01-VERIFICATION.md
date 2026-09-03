---
phase: 01-error-visibility-log-hygiene
verified: 2026-09-03T12:07:26Z
status: passed
score: 12/12 must-haves verified
behavior_unverified: 0
overrides_applied: 0
coincidental_reliance_items: []
---

# Phase 1: Error Visibility & Log Hygiene Verification Report

**Phase Goal:** Every failure path in Umbra's publish, login, logout, cleanup, and relay-transport code reports its throwable at a visible, scrubbed level instead of vanishing — so the remaining fixes in this milestone fail loudly rather than silently.
**Verified:** 2026-09-03T12:07:26Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | The eight LOG-17 sites log at error level with the throwable attached; no debug-level, throwable-dropping handler remains at any of them | ✓ VERIFIED | `PublishEventUseCases.kt:41,68`, `LoginViewModel.kt:100,151,230`, `UmbraNostrClient.kt:371`, `RelayMessageHandling.kt:139`, `RelayWebSocketListener.kt:51` — all `logger.e(...)`/`client.logger.e(...)` with a bound throwable. `UmbraNostrClient`'s SOCKS-transient branch (line ~366) confirmed byte-unchanged at `logger.d` with its early return intact. |
| 2 | The four previously-silent catches (`clearAllData()`'s `disconnectFromAll()`, `SettingsScreen.kt`'s logout, `LogoutUseCase`/`TrimMemoryCachesUseCase`'s per-step cleanup, `LoginViewModel`'s inner `activateUserSession` catch) each emit a scrubbed log carrying the exception | ✓ VERIFIED | `EventRepositoryImpl.kt:502` `logger.e(e) { "disconnectFromAll failed during clearAllData; continuing wipe" }` with the wipe sequence continuing after (no `return`); `SettingsScreen.kt:212` `settingsScreenLogger.e(e) { "Logout failed" }`; `LogoutUseCase.kt` 7/7 per-step catches at `logger.e(e)`, `TrimMemoryCachesUseCase.kt` 5/5 at `logger.e(e)`; `LoginViewModel.kt:85,137` both `activateUserSession` catches now call `logger.e(e) { "Session activation failed" }` while the inner try/catch layer and post-catch `nostrSessionController.start()` flow are unchanged. See note below on `SettingsScreen.kt` reachability (tracked, not a phase gap). |
| 3 | The three LOG-18 sites route through `LogScrubber`; no raw relay URL, pubkey, or unscrubbed exception text reaches a release-build log at those paths | ✓ VERIFIED | `EventRepositoryImpl.kt:429,487` both wrap the interpolated relay URL in `scrubUrlForLogs(relayUrl)` (level unchanged, debug); `NegentropySyncOrchestrator.kt:119` routes the exception message through `LogScrubber.scrubThrowableMessageForLogs(e)` instead of raw `e.message`. |
| 4 | `compileDebugKotlin`, `lintDebug`, `testDebugUnitTest` all pass with no new warnings | ✓ VERIFIED | Ran all three fresh in this verification session: `BUILD SUCCESSFUL` for each (lint zero warnings/errors; full unit test suite green). Targeted re-run (forced, non-cached) of `LogScrubberTest` (7/7), `TrimMemoryCachesUseCaseTest` (3/3), `PublishEventUseCasesTest` (3/3) confirmed via `test-results` XML: 0 failures, 0 errors across all. |
| 5 | `docs/KNOWN_ISSUES.md` entries LOG-18/20/26/27/28 read "fix applied — needs on-device validation" with a Fix: line; `docs/TODO.md`'s LOG-17 moved verbatim into `docs/DONE.md` with Completed:/From: lines | ✓ VERIFIED | All five KNOWN_ISSUES entries confirmed at that exact status string with an accompanying `- **Fix:**` line. `docs/TODO.md` has zero `### LOG-17` headings; `docs/DONE.md` has exactly one, carrying `**Completed:** 2026-09-02` and `**From:** TODO LOG-17`. |
| 6 | CR-01 (code-review Critical finding, fixed this session outside the three plans' scope): `Logger.e()` no longer leaks the raw, unscrubbed `Throwable` via Android's own stack-trace formatting | ✓ VERIFIED | `Logger.kt:22-27` now builds `LogScrubber.scrubThrowableForLogs(throwable)` — a replacement `Throwable` with original stack frames, scrubbed message, no cause — and passes *that* to `Log.e()`, not the original. New `LogScrubberTest` cases assert the scrubbed throwable's message is redacted and its cause chain is dropped (`safe.cause == null`). This closes the gap that would otherwise have undermined every other truth above (a scrubbed message string means nothing if the attached throwable object reprints the same unscrubbed text via Android's own trace formatting). |

**Score:** 6/6 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `app/src/test/java/com/umbra/app/testutil/fakes/FakeUmbraLogger.kt` | Recording `UmbraLogger` test double | ✓ VERIFIED | Exists, implements `UmbraLogger`, consumed by 3 test classes with passing assertions on throwable identity, not just call count. |
| `app/src/main/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCase.kt` | 5 per-step catches log at error level | ✓ VERIFIED | 5/5 `logger.e(e)` calls, 0 remaining `_: Exception`, later steps still run after an early failure (test-proven). |
| `app/src/main/java/com/umbra/app/domain/usecase/LogoutUseCase.kt` | 7 per-step catches log at error level | ✓ VERIFIED | 7/7 `logger.e(e)` calls; 1 deliberately-untouched method-wide `catch (_: Exception)` remains (filed as LOG-32, in scope's documented exclusion). |
| `app/src/main/java/com/umbra/app/domain/usecase/PublishEventUseCases.kt` | Both publish failure paths at error level | ✓ VERIFIED | 2/2 `logger.e(e)` calls, orphaned `LogScrubber` import removed, `lintDebug` clean. |
| `app/src/main/java/com/umbra/app/di/UseCaseModule.kt` | Hilt wiring supplies tagged logger to both cleanup use cases | ✓ VERIFIED | `provideLogoutUseCase`/`provideTrimMemoryCachesUseCase` pass `UmbraLog.tag("UmbraLogout")`/`UmbraLog.tag("UmbraTrimMemory")` as trailing constructor args. |
| `app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt` | 5 error-level handlers | ✓ VERIFIED | 3 outer (`loginAnonymously`, `savePublicKey`, `logout`) + 2 session-activation, all `logger.e(e)`; two-layer handler structure and login success semantics preserved (D-03). |
| `app/src/main/java/com/umbra/app/data/nostr/UmbraNostrClient.kt` | Non-SOCKS WebSocket failure at error level | ✓ VERIFIED | `logger.e(throwable) { "WebSocket error for $relay..." }`; SOCKS branch untouched. |
| `app/src/main/java/com/umbra/app/data/nostr/RelayMessageHandling.kt` | Message-processing failure at error level | ✓ VERIFIED | `logger.e(e) { ... scrubUrlForLogs(relayUrl) }`. |
| `app/src/main/java/com/umbra/app/data/nostr/RelayWebSocketListener.kt` | Listener drain failure at error level | ✓ VERIFIED | `client.logger.e(e) { ... scrubUrlForLogs(relayUrl) }`. |
| `app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt` | Scrubbed relay URLs + logged disconnect failure | ✓ VERIFIED | 3/3 `scrubUrlForLogs` sites (1 new import + 2 fixed calls); wipe-path `logger.e(e)` added, continues after. |
| `app/src/main/java/com/umbra/app/data/repository/NegentropySyncOrchestrator.kt` | Scrubbed exception text | ✓ VERIFIED | `LogScrubber.scrubThrowableMessageForLogs(e)`, `${e.message}` gone. |
| `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt` | File-scope logger + error-level logout handler | ✓ VERIFIED (see reachability note) | `settingsScreenLogger` declared and called once; `isLoggingOut`/navigation/`popUpTo` unchanged. |
| `app/src/main/java/com/umbra/app/util/logging/Logger.kt` + `LogScrubber.kt` | CR-01 fix: throwable object itself scrubbed before reaching `Log.e` | ✓ VERIFIED | See Truth 6 above; moved to `util/logging/` package cleanly, no stale `util/LogScrubber` imports remain anywhere in the source tree. |
| `docs/KNOWN_ISSUES.md` | LOG-18/20/26/27/28 → applied-fix status with Fix line | ✓ VERIFIED | Confirmed per-entry via awk scan. |
| `docs/DONE.md` | LOG-17 moved verbatim with trailers | ✓ VERIFIED | Confirmed. |
| `docs/TODO.md` | LOG-17 removed; LOG-32/33 filed | ✓ VERIFIED | Confirmed; also LOG-36 (`SettingsScreen` dead-code catch, found by code review) filed as backlog. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `UseCaseModule.kt` | `LogoutUseCase.kt` | `UmbraLog.tag("UmbraLogout")` trailing ctor arg | ✓ WIRED | Confirmed at line 292. |
| `UseCaseModule.kt` | `TrimMemoryCachesUseCase.kt` | `UmbraLog.tag("UmbraTrimMemory")` trailing ctor arg | ✓ WIRED | Confirmed at line 309. |
| `RelayWebSocketListener.kt` | `UmbraNostrClient.kt` | shared `client.logger` field | ✓ WIRED | Confirmed, no local logger introduced. |
| `NegentropySyncOrchestrator.kt` | `util/logging/LogScrubber.kt` | new import, used in sync-failure log | ✓ WIRED | Confirmed. |
| `Logger.kt` | `LogScrubber.scrubThrowableForLogs` | CR-01 fix — throwable sanitized before `Log.e` | ✓ WIRED | Confirmed, `LogScrubberTest` exercises it. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full build gate (compile+lint+test) | `./gradlew compileDebugKotlin && ./gradlew lintDebug && ./gradlew testDebugUnitTest` | `BUILD SUCCESSFUL` all three | ✓ PASS |
| `LogScrubberTest` (CR-01 fix regression coverage) | forced re-run, non-cached | 7/7 tests, 0 failures | ✓ PASS |
| `TrimMemoryCachesUseCaseTest` | forced re-run, non-cached | 3/3 tests, 0 failures | ✓ PASS |
| `PublishEventUseCasesTest` | forced re-run, non-cached | 3/3 tests, 0 failures | ✓ PASS |
| Phase-wide error-level site count | `grep -rni 'logger\.e(' app/src/main/java/com/umbra/app/ --include=*.kt \| wc -l` | 27 (26 phase-added + 1 pre-existing `feedScreenLogger`, matching SUMMARY's documented case-sensitivity note) | ✓ PASS |
| Phase-wide unscrubbed-interpolation sweep (Check 1) | `grep -rn 'logger\.e(' ... \| grep -F '${' \| grep -v scrub` | no output | ✓ PASS |
| Phase-wide throwable-dropped-at-d/w sweep (Check 2) | `grep -rnE '\.(d\|w) *\{[^}]*\b(e\.message\|throwable\.message)\b'` | no output | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| BUG-01 | 01-01, 01-02 | Promote LOG-17's 8 sites to scrubbed error-level logging | ✓ SATISFIED | All 8 sites confirmed (Truth 1). |
| BUG-02 | 01-02 | Scrub LOG-18's 3 unscrubbed sites | ✓ SATISFIED | Confirmed (Truth 3). |
| BUG-04 | 01-02 | Log the exception swallowed by `clearAllData()`'s `disconnectFromAll()` | ✓ SATISFIED | Confirmed (Truth 2). |
| BUG-09 | 01-03 | Apply LOG-25's fix to `SettingsScreen.kt`'s logout entry point | ✓ SATISFIED | Confirmed (Truth 2), with reachability caveat below. |
| BUG-10 | 01-01 | Log LogoutUseCase/TrimMemoryCachesUseCase per-step cleanup exceptions | ✓ SATISFIED | 12/12 sites confirmed (Truth 2). |
| BUG-11 | 01-02 | Stop swallowing `activateUserSession`'s exception | ✓ SATISFIED | Confirmed (Truth 2). |

No orphaned requirements: `REQUIREMENTS.md`'s "Phase 1" traceability rows (BUG-01/02/04/09/10/11) match exactly the union of `requirements:` fields declared across the three PLAN frontmatters — no Phase-1-mapped requirement is missing from a plan, and no plan claims a requirement not mapped to Phase 1.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | No `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER` markers found in any of the 13 files this phase (plus its follow-up fix) touched | — | none |

### Notable Findings (Not Gaps — Tracked Separately, Confirmed Correctly Scoped)

These were surfaced by `01-REVIEW.md` and cross-checked directly against the codebase in this verification pass. Per the phase's explicit scope (ROADMAP Success Criteria 1-5) and the dispatching task's explicit instruction, none of these count as a phase-goal gap — they are pre-existing or deliberately-deferred issues, correctly filed rather than silently fixed or silently dropped:

- **CR-01 (Critical, now fixed):** `Logger.e()` was reprinting the raw unscrubbed `Throwable` via Android's own stack-trace formatting, which would have defeated this phase's entire purpose for every promoted site (message scrubbed, but the attached throwable object wasn't). Fixed this session in `Logger.kt`/`LogScrubber.kt` (commit `78396e0`), filed as LOG-34, confirmed fixed and test-covered in this verification (Truth 6). This was the one finding that, if unfixed, would have been a BLOCKER for the whole phase.
- **WR-03 / LOG-36 (dead code, filed, not fixed):** `SettingsScreen.kt`'s new logout `catch` block is structurally unreachable — `LoginViewModel.logout()` already catches every exception internally (with its own `logger.e` call) and never rethrows, so the outer `try/catch` this phase added around `loginViewModel.logout()` can never fire. The identical pre-existing shape exists in `FeedScreen.kt` (LOG-25, shipped before this phase) and was the literal pattern this phase's BUG-09 fix was instructed to mirror. Filed as `docs/TODO.md` LOG-36. Confirmed present and correctly filed; not a regression this phase introduced, and explicitly out of the phase's ROADMAP-defined scope.
- **WR-01 / LOG-32 (filed, not fixed):** `LogoutUseCase`'s outer method-wide catch and its unwrapped `userPreferences.clearAll()` call remain silent — deliberately, per D-02's locked 7-site scope. Filed.
- **WR-02, WR-04 / LOG-35 (filed, not fixed):** `LoginViewModel.requestAmberLogin()`'s catch discards its throwable entirely (no logger call at all) and surfaces raw, unscrubbed `e.message` directly into on-screen UI text in two places. Pre-existing, outside this phase's file/site scope, filed as LOG-35 and confirmed still open (correctly not claimed fixed).

### Human Verification Required

None. All must-haves are verifiable from source, build-gate execution, and passing unit tests; no visual/UX/real-time behavior is in scope for this logging-only phase.

### Gaps Summary

No gaps. All 6 ROADMAP success criteria and all 6 requirement IDs (BUG-01, BUG-02, BUG-04, BUG-09, BUG-10, BUG-11) are verified against the actual codebase, not just SUMMARY claims. The one issue serious enough to have blocked the phase's actual goal (CR-01 — throwables leaking unscrubbed via `Log.e`'s own trace formatting) was found by code review and fixed within this same session, with new test coverage (`LogScrubberTest`) proving the fix. The three secondary findings (LOG-32, LOG-35, LOG-36) are pre-existing or deliberately-scoped-out issues, correctly filed in `docs/TODO.md`/`docs/KNOWN_ISSUES.md` rather than silently dropped, and fall outside the ROADMAP's Phase 1 success criteria.

---

_Verified: 2026-09-03T12:07:26Z_
_Verifier: Claude (gsd-verifier)_
