---
phase: 04-version-consistency-v0-1-0-release-prep
plan: 02
subsystem: release-docs
tags: [changelog, release-checklist, ci-signing, documentation]

# Dependency graph
requires:
  - phase: 04-version-consistency-v0-1-0-release-prep
    provides: "04-01's single source of truth for versionName (BuildConfig.VERSION_NAME), and the project's Keep a Changelog / docs/DONE.md / docs/KNOWN_ISSUES.md conventions"
provides:
  - "CHANGELOG.md's [Unreleased] section split into a dated [0.1.0] release with a reader-facing hardening summary"
  - "docs/RELEASE_CHECKLIST.md, a standalone, reusable, dated record proving every release-readiness gate was actually run"
affects: [04-03-release-tag-and-skill]

# Actuals (#2632)
actuals:
  tokens: 1800
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "docs/RELEASE_CHECKLIST.md's check/command/observed-result/date table shape, reusable for future releases by appending rows or a new dated table rather than editing a past result in place."

key-files:
  created:
    - docs/RELEASE_CHECKLIST.md
  modified:
    - CHANGELOG.md

key-decisions:
  - "Wrote the hardening summary as four new bullets appended to the existing ### Fixed subsection rather than a separate nested heading, since the plan allowed either shape and a nested heading risked interfering with the single-### Fixed-heading acceptance check."
  - "Ran testDebugUnitTest before assembleRelease had touched anything; testDebugUnitTest reported UP-TO-DATE (Gradle determined no rebuild was needed since the last real run), but this is still a genuine, observed result from this session's invocation of the command, not a transcription from an earlier research pass — cross-checked against the underlying test-results XML (930 tests, 0 failures, 0 errors) rather than trusting the UP-TO-DATE line alone."

requirements-completed: [REL-01, REL-02]

coverage:
  - id: D1
    description: "CHANGELOG.md holds exactly one empty ## [Unreleased] header directly above exactly one ## [0.1.0] - 2026-09-05 header carrying all four original subsections (Added/Changed/Fixed/Security) plus a new hardening summary under Fixed, with no LOG-N identifier and no still-open KNOWN_ISSUES.md entry described as fixed"
    requirement: "REL-01"
    verification:
      - kind: other
        ref: "grep -c '^## \\[Unreleased\\]$' CHANGELOG.md == 1; grep -c '^## \\[0\\.1\\.0\\] - 2026-09-05$' CHANGELOG.md == 1; awk-based between-headers non-whitespace line count == 0; grep -cE '\\bLOG-[0-9]+' CHANGELOG.md == 0; git diff CHANGELOG.md shows additions-only under ### Fixed, no changes to Roadmap/Security notes/preamble"
        status: pass
    human_judgment: true
    rationale: "Whether the four new Fixed bullets accurately summarize (without overstating) the hardening work in docs/DONE.md, and whether the NIP-17 partial-shipment qualification and known-open-issue exclusion are honored, is a judgment call the acceptance criteria only partially mechanize."
  - id: D2
    description: "docs/RELEASE_CHECKLIST.md records one dated row per release-readiness check (unit tests, lint, R8-shaped release build, CI signing secrets present, remote tag unclaimed), each naming the exact command and its result observed by actually running that command this session, with no secret value, keystore path, or GSD planning identifier anywhere in the file"
    requirement: "REL-02"
    verification:
      - kind: unit
        ref: "./gradlew lintDebug (BUILD SUCCESSFUL); ./gradlew testDebugUnitTest (BUILD SUCCESSFUL, 930 tests/0 failures/0 errors); ./gradlew assembleRelease (BUILD SUCCESSFUL in 2m 59s, umbra-v0.1.0.apk produced)"
        status: pass
      - kind: other
        ref: "gh secret list confirms SIGNING_KEY/KEY_ALIAS/KEY_STORE_PASSWORD/KEY_PASSWORD present; git ls-remote --tags origin returns empty (no v0.1.0); grep -cE 'BEGIN [A-Z ]*PRIVATE KEY|[A-Za-z0-9+/]{200,}' docs/RELEASE_CHECKLIST.md == 0; grep -ciE '\\.(jks|keystore|p12)\\b' docs/RELEASE_CHECKLIST.md == 0; git ls-files '*.jks' '*.keystore' '*.p12' empty; grep -cE 'D-[0-9]{2}|Phase [0-9]|PLAN\\.md' docs/RELEASE_CHECKLIST.md == 0"
        status: pass
    human_judgment: false
---

# Phase 4 Plan 2: Changelog Split and Release Readiness Checklist Summary

**CHANGELOG.md now names a dated 0.1.0 release carrying a reader-facing hardening summary, and a new docs/RELEASE_CHECKLIST.md records this session's actual, observed pass/fail results for every release-readiness gate.**

## Performance

- **Duration:** ~15min
- **Completed:** 2026-09-05
- **Tasks:** 2
- **Files modified:** 2 (1 modified, 1 created)

## Accomplishments

- Reshaped `CHANGELOG.md`: moved the four existing `[Unreleased]` subsections (`### Added`/`### Changed`/`### Fixed`/`### Security`) verbatim under a new `## [0.1.0] - 2026-09-05` header, left an empty `## [Unreleased]` header directly above it, and appended four new bullets under `### Fixed` summarizing this milestone's own hardening work (error-visibility logging, concurrency/race fixes, deletion/state-correctness fixes, retroactively added regression tests) — sourced from `docs/DONE.md`, no `LOG-N` identifiers carried over, no still-open `docs/KNOWN_ISSUES.md` entry described as fixed, and the NIP-17 DM entry's "backend/data model only" qualification kept verbatim.
- Actually ran, in order, this session: `./gradlew lintDebug` (`BUILD SUCCESSFUL`), `./gradlew testDebugUnitTest` (`BUILD SUCCESSFUL`, 930 tests / 0 failures / 0 errors per the test-results XML), and `./gradlew assembleRelease` (`BUILD SUCCESSFUL` in 2m 59s, producing the unsigned, R8-minified `app/build/outputs/apk/release/umbra-v0.1.0.apk`, 13,641,227 bytes) — no fallback to `assembleBenchmark` was needed.
- Read `.github/workflows/android-release.yml` directly and confirmed: the `v*` tag trigger plus `workflow_dispatch` (`prerelease`/`version` inputs), the `assembleRelease` build step, the `r0adkll/sign-android-release` signing action, and its four secret names. Confirmed all four (`SIGNING_KEY`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) exist on the repository via `gh secret list` (names/timestamps only, no value read or attempted). Confirmed via `git ls-remote --tags origin` that no `v0.1.0` tag (or any tag) exists on the remote yet.
- Wrote `docs/RELEASE_CHECKLIST.md`: a standalone, dated table of the five checks above plus a closing section stating that signing happens entirely in CI from repository secrets (no local keystore belongs in this repository) and that pushing the release tag is a separate, explicitly-confirmed action this checklist stops short of.

## Task Commits

Each task was committed atomically:

1. **Task 1: Split the changelog into an empty Unreleased and a dated 0.1.0 release** - `5deffa4` (docs)
2. **Task 2: Run the release-readiness checks and record them in a new release checklist** - `b9692d7` (docs)

_No TDD tasks in this plan — both tasks are direct documentation edits with no `<behavior>` block._

## Files Created/Modified

- `CHANGELOG.md` - `[Unreleased]` content moved under a new dated `[0.1.0] - 2026-09-05` header; four new hardening-summary bullets added under `### Fixed`; `## Roadmap`/`## Security notes` and the preamble untouched
- `docs/RELEASE_CHECKLIST.md` (new) - dated table of the five release-readiness checks actually run this session, plus a closing section on CI-side signing and the separate tag-push gate

## Decisions Made

- Appended the hardening-summary bullets directly into the existing `### Fixed` subsection (no nested sub-heading), matching the plan's "or as a clearly-labelled sub-grouping" allowance while keeping the single-`### Fixed`-heading invariant unambiguous.
- Cross-checked `testDebugUnitTest`'s `UP-TO-DATE` Gradle output against the underlying `app/build/test-results/testDebugUnitTest/*.xml` files (930 tests, 0 failures, 0 errors) before recording the result, since an `UP-TO-DATE` task still reflects Gradle's own verification that the prior run's inputs are unchanged, but the actual test counts needed for an honest checklist row can only come from the report artifacts.

## Deviations from Plan

None — plan executed exactly as written. `assembleRelease` succeeded on the first attempt (the `release` build type has no `signingConfig` assigned, so no local keystore was needed), so the `assembleBenchmark` fallback was never invoked.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required. All four CI signing secrets were already present on the repository from an earlier setup step; this plan only confirmed their names, never their values.

## Next Phase Readiness

- Ready for 04-03. `CHANGELOG.md` now has a real dated `0.1.0` release entry and `docs/RELEASE_CHECKLIST.md` proves the release-readiness gates pass, so 04-03's local `v0.1.0` git tag and `.claude/skills/umbra-release/SKILL.md` have no remaining blocking dependency on this plan's files.
- Pushing the `v0.1.0` tag itself remains explicitly out of scope for this milestone until the user gives the go-ahead, per `docs/RELEASE_CHECKLIST.md`'s own closing section.

---
*Phase: 04-version-consistency-v0-1-0-release-prep*
*Completed: 2026-09-05*

## Self-Check: PASSED

- FOUND: commit `5deffa4` (Task 1)
- FOUND: commit `b9692d7` (Task 2)
- FOUND: `CHANGELOG.md` contains `## [0.1.0] - 2026-09-05` with an empty `## [Unreleased]` above it
- FOUND: `docs/RELEASE_CHECKLIST.md` exists, 40 lines, with a dated row per check
- FOUND: `app/build/outputs/apk/release/umbra-v0.1.0.apk` (13,641,227 bytes)
- FOUND: `gh secret list` output includes all four signing secret names
- FOUND: `git ls-remote --tags origin` returned empty (no `v0.1.0`)
