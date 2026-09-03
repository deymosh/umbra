---
phase: 01-error-visibility-log-hygiene
plan: 03
subsystem: logging
tags: [kotlin, compose, error-visibility, log-hygiene, bug-tracking]

# Dependency graph
requires:
  - phase: 01-error-visibility-log-hygiene/01-01
    provides: "FakeUmbraLogger test double, constructor-injected UmbraLogger on both cleanup use cases, BUG-10 fully resolved, 2/8 BUG-01 sites"
  - phase: 01-error-visibility-log-hygiene/01-02
    provides: "Remaining 6/8 BUG-01 sites, all 3 BUG-02 scrubbing gaps, BUG-04, BUG-11"
provides:
  - "SettingsScreen.kt's independent logout entry point now logs failures at error level (BUG-09), mirroring FeedScreen.kt's already-shipped fix"
  - "Phase-wide find-non-lambda-logs audit sweep across all ten touched files, plus the full compileDebugKotlin/lintDebug/testDebugUnitTest gate, both clean"
  - "docs/KNOWN_ISSUES.md's five phase-1 entries (LOG-18, LOG-20, LOG-26, LOG-27, LOG-28) all at applied-fix status with descriptive Fix lines"
  - "docs/TODO.md's LOG-17 moved verbatim into docs/DONE.md with Completed/From trailers; LOG-32 and LOG-33 filed for two out-of-scope gaps found during the phase"
  - "REQUIREMENTS.md traceability confirmed internally consistent for all six of this phase's requirements (BUG-01, BUG-02, BUG-04, BUG-09, BUG-10, BUG-11), all Complete"
affects: []

actuals:
  tokens: 3509
  tasks: 3
  commits: 2

tech-stack:
  added: []
  patterns:
    - "File-scope tagged logger for a Composable screen (private val <screenName>Logger = UmbraLog.tag(\"<ScreenName>\")) applied a second time, confirming FeedScreen.kt's LOG-25 shape as the reusable pattern for any future Compose-file logout/failure entry point"

key-files:
  created: []
  modified:
    - app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt
    - docs/KNOWN_ISSUES.md
    - docs/TODO.md
    - docs/DONE.md
    - .planning/REQUIREMENTS.md

key-decisions:
  - "Grep-count verification quirk (Task 2): the plan's own literal `grep -rh 'logger\\.e(' ... | wc -l` verify command is case-sensitive and therefore never matches a camelCase file-scope logger variable ending in \"...Logger.e(\" (capital L) — this affects both this phase's new `settingsScreenLogger.e(` site and the pre-existing, out-of-phase `feedScreenLogger.e(` site (LOG-25, shipped before this phase). Re-ran the check case-insensitively: true total is 27 error-level call sites in the main source set; subtracting FeedScreen.kt's one pre-existing, out-of-phase LOG-25 site gives exactly 26 — the plan's own expected total for this phase's accounting. Not a regression, no source change needed; documented here the same way Plan 01-02 documented its CRLF awk-gate quirk."
  - "LOG-27 required no KNOWN_ISSUES.md edit in Task 3: Plan 01-01 had already advanced its status to 'fix applied — needs on-device validation' with a Fix line when BUG-10 was fully resolved. Task 3's five-entry closeout list (LOG-18, LOG-20, LOG-26, LOG-27, LOG-28) is satisfied for LOG-27 by that earlier plan's work — confirmed via the same awk-per-entry check the plan's verify block specifies before making any edits, so only four entries (LOG-18, LOG-20, LOG-26, LOG-28) actually needed a Status/Fix change this task."

patterns-established:
  - "Phase-wide find-non-lambda-logs sweep as the final wave's own task, run once across all files a multi-wave phase touched, rather than per-wave — avoids redundant full-source-set scans in each earlier plan while still catching a phase-wide regression before the phase is reported done."

requirements-completed: [BUG-09, BUG-01, BUG-02, BUG-04, BUG-10, BUG-11]

coverage:
  - id: D1
    description: "SettingsScreen.kt's logout MenuItemRow catch block now logs the caught exception at error level via a new file-scope settingsScreenLogger, mirroring FeedScreen.kt's already-shipped LOG-25 fix exactly; isLoggingOut guard and unconditional navigation to the login screen unchanged"
    requirement: "BUG-09"
    verification:
      - kind: other
        ref: "grep -c settingsScreenLogger SettingsScreen.kt == 2; grep -c 'private val settingsScreenLogger = UmbraLog.tag(\"SettingsScreen\")' == 1; grep -c '_: *Exception' == 0; grep -c 'settingsScreenLogger.e(e) { \"Logout failed\" }' == 1; grep -c 'const val TAG' == 0; git diff shows isLoggingOut/navigation/popUpTo unchanged"
        status: pass
      - kind: other
        ref: "./gradlew compileDebugKotlin && ./gradlew lintDebug"
        status: pass
    human_judgment: false
  - id: D2
    description: "Phase-wide find-non-lambda-logs audit (Check 1: unscrubbed interpolation in error-level logs across the whole main source set; Check 2: throwable-dropped d/w calls across the ten touched files) both clean; full compileDebugKotlin/lintDebug/testDebugUnitTest triple passes; error-level site count confirmed at the expected 26 once the case-sensitivity grep quirk is corrected for"
    requirement: "BUG-01, BUG-02, BUG-04, BUG-09, BUG-10, BUG-11"
    verification:
      - kind: other
        ref: "grep -rni 'logger\\.e(' app/src/main/java/com/umbra/app/ --include=*.kt | grep -F '${' | grep -v scrub  -> no hits; grep -rnE '(logger|client\\.logger)\\.(d|w) *\\{.*\\$\\{?(relayUrl|url|pubkey|npub)\\b' ... | grep -v scrub -> no hits; grep -Pn Check-2 pattern over the 10 touched files -> no hits"
        status: pass
      - kind: other
        ref: "./gradlew compileDebugKotlin && ./gradlew lintDebug && ./gradlew testDebugUnitTest"
        status: pass
    human_judgment: false
  - id: D3
    description: "docs/KNOWN_ISSUES.md's five phase-1 entries all at applied-fix status with a Fix line each; docs/TODO.md's LOG-17 moved verbatim into docs/DONE.md with Completed/From trailers; LOG-32/LOG-33 filed; no entry marked Validated or moved out of KNOWN_ISSUES.md on non-device evidence; REQUIREMENTS.md's six phase-1 requirements all show Complete in both the checkbox and the traceability table"
    requirement: "BUG-01, BUG-02, BUG-04, BUG-09, BUG-10, BUG-11"
    verification:
      - kind: other
        ref: "awk-per-entry Status check over LOG-18/20/26/27/28 == 5 'fix applied'; grep -c '**Status:** open' KNOWN_ISSUES.md == 8; grep -c '**Validated:**' KNOWN_ISSUES.md == 0; LOG-17 absent from TODO.md, present once in DONE.md with From/Completed lines; LOG-32/LOG-33 present in TODO.md; no LOG id above 33 anywhere; git diff docs/DONE.md shows only appended lines; REQUIREMENTS.md BUG-01/02/04/09/10/11 all [x] and Complete"
        status: pass
    human_judgment: false

duration: ~20min
completed: 2026-09-03
status: complete
---

# Phase 1 Plan 3: Settings Logout Fix, Phase-Wide Audit Sweep, Bug Tracker Closeout Summary

**SettingsScreen.kt's independent logout entry point now logs failures at error level (closing BUG-09, the phase's last code fix), a phase-wide find-non-lambda-logs audit across all ten touched files plus the full build gate both come back clean, and the bug tracker (KNOWN_ISSUES/TODO/DONE) plus REQUIREMENTS.md traceability are brought into line with what actually shipped across all three plans.**

## Performance

- **Duration:** ~20 min
- **Completed:** 2026-09-03T11:42:09Z
- **Tasks:** 3
- **Files modified:** 5 (1 production, 3 docs, 1 planning doc)

## Accomplishments

- `SettingsScreen.kt` gained a file-scope `settingsScreenLogger` (`UmbraLog.tag("SettingsScreen")`) and its logout `catch` block now logs the caught exception at error level with the static message `"Logout failed"` before navigating to the login screen — the exact same fix shape already shipped for `FeedScreen.kt`'s independent logout entry point (LOG-25). The `isLoggingOut` guard and the unconditional navigation to the login screen are byte-unchanged.
- Ran the full phase-wide `find-non-lambda-logs` audit sweep across the entire main source set (not just the ten files this phase touched): Check 1 (unscrubbed relay URL/pubkey/throwable interpolation in `logger.e(...)` lambdas) returned zero hits; Check 2 (throwable dropped via `logger.d`/`logger.w` instead of `logger.e`) returned zero hits across the ten touched files.
- `compileDebugKotlin`, `lintDebug`, and the full `testDebugUnitTest` suite all pass with zero warnings.
- Confirmed the phase's expected error-level site total of 26 is correct — found and documented a case-sensitivity quirk in the literal verify grep (see Decisions Made) rather than treating the initial mismatched count as a regression.
- `docs/KNOWN_ISSUES.md`'s LOG-18, LOG-20, LOG-26, and LOG-28 advanced to `fix applied — needs on-device validation` with a descriptive Fix line each; LOG-27 already carried that status from Plan 01-01 and needed no change.
- `docs/TODO.md`'s LOG-17 moved verbatim into `docs/DONE.md` with `**Completed:** 2026-09-02` and `**From:** TODO LOG-17` trailers; two new backlog entries filed — LOG-32 (`LogoutUseCase`'s still-silent outer catch and unwrapped final cleanup call) and LOG-33 (`NegentropySyncOrchestrator`'s unscrubbed sync-aborted reason string) — for gaps found while implementing this phase but deliberately outside its locked scope.
- `.planning/REQUIREMENTS.md`'s BUG-09 checkbox and traceability row corrected to Complete; all six of this phase's requirements (BUG-01, BUG-02, BUG-04, BUG-09, BUG-10, BUG-11) now read Complete consistently in both places.

## Task Commits

Each task was committed atomically:

1. **Task 1: Settings logout failure handler and file-scope logger** - `8f2f24e` (fix)
2. **Task 2: Phase-wide log-hygiene audit sweep and full build gate** - no commit (verification-only task, no files modified, per the plan's own `<files>` spec)
3. **Task 3: Bug tracker closeout** - `476aeca` (docs)

## Files Created/Modified

- `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt` - New file-scope `settingsScreenLogger`; logout catch block now logs the throwable at error level
- `docs/KNOWN_ISSUES.md` - LOG-18/20/26/28 advanced to applied-fix status with Fix lines
- `docs/TODO.md` - LOG-17 removed (moved to DONE.md); LOG-32/LOG-33 filed
- `docs/DONE.md` - LOG-17 appended verbatim with Completed/From trailers
- `.planning/REQUIREMENTS.md` - BUG-09 marked Complete (checkbox + traceability row)

## Decisions Made

- **Grep-count verification quirk (Task 2):** the plan's literal `grep -rh 'logger\.e(' app/src/main/java/com/umbra/app/ --include=*.kt | wc -l` verify command returned 25, not the expected 26. Root cause: the pattern is case-sensitive and a file-scope logger variable named with a camelCase `...Logger` suffix (capital `L`) never contains the literal lowercase substring `logger.e(` — this silently excludes both this phase's new `settingsScreenLogger.e(...)` call and the pre-existing, out-of-phase `feedScreenLogger.e(...)` call (`FeedScreen.kt`, LOG-25, shipped well before this phase). Re-running the same check case-insensitively across the whole main source set gives 27 true error-level call sites; subtracting `FeedScreen.kt`'s one pre-existing site (not part of this phase's accounting) yields exactly 26 — the plan's own expected total. No source change was needed; this is a verify-script quirk, not a regression, in the same spirit as Plan 01-02's documented CRLF `awk` gate quirk.
- **LOG-27 needed no edit in Task 3:** the awk-per-entry check specified by the plan's own `<verify>` block was run before making any KNOWN_ISSUES.md edits, and showed LOG-27 already at `fix applied — needs on-device validation` with a Fix line, set by Plan 01-01 when BUG-10 was fully resolved. Only LOG-18, LOG-20, LOG-26, and LOG-28 needed a Status/Fix change this task; LOG-27 was left untouched (already satisfying every one of Task 3's acceptance criteria for that entry).

## Deviations from Plan

None - plan executed exactly as written. Both items in Decisions Made are verification-process findings/clarifications, not deviations from any task's `<action>` or `<acceptance_criteria>` — every acceptance criterion in the plan passed as written once the grep-count check was corrected for the documented case-sensitivity gap.

## Issues Encountered

- The sandbox's `grep` is shell-aliased to a `ugrep`-backed wrapper that rejects combined `-P -E` flags and mishandles backreferences (`\1`) under its default mode. Worked around by invoking `/usr/bin/grep -Pn ...` directly for the two checks (Check 2's backreference pattern, and the case-insensitive error-site recount) that needed PCRE features the wrapper's default mode doesn't support. No effect on any code change — tooling workaround only.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 1 (Error Visibility & Log Hygiene) is complete: all six requirements (BUG-01, BUG-02, BUG-04, BUG-09, BUG-10, BUG-11) are Complete in `REQUIREMENTS.md`, all fifteen in-scope log sites are fixed, and the full build gate plus phase-wide audit sweep are both clean.
- `docs/KNOWN_ISSUES.md` correctly reflects five applied-but-unvalidated fixes from this phase (LOG-18, LOG-20, LOG-26, LOG-27, LOG-28), awaiting the user's own on-device `run-umbra` validation pass — none was marked Validated or moved to DONE.md on the strength of automated evidence alone, per this phase's explicit prohibition.
- Two new backlog entries (LOG-32, LOG-33) are filed and ready for triage in a future phase or ad hoc fix.
- No blockers for Phase 2 (Concurrency & State Correctness).

---
*Phase: 01-error-visibility-log-hygiene*
*Completed: 2026-09-03*

## Self-Check: PASSED

All 6 created/modified files confirmed present on disk; both commit hashes (`8f2f24e`, `476aeca`) confirmed in `git log`.
