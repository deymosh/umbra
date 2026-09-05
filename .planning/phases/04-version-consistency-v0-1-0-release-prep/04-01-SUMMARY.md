---
phase: 04-version-consistency-v0-1-0-release-prep
plan: 01
subsystem: build
tags: [gradle, buildconfig, compose, settings, agp]

# Dependency graph
requires:
  - phase: 04-version-consistency-v0-1-0-release-prep
    provides: "04-CONTEXT.md's D-01/D-02/D-03 decisions and 04-RESEARCH.md's buildConfig mechanism confirmation"
provides:
  - "app/build.gradle.kts's versionName is the single source of truth for the version Umbra reports to the user"
  - "SettingsScreen's About Umbra Version row reads BuildConfig.VERSION_NAME (AGP-generated), not a hand-maintained string resource"
  - "The drifted settings_version_value string resource (\"0.1.0-beta\") is deleted from the tracked source tree"
affects: [04-02-changelog-release-checklist, 04-03-release-tag-and-skill]

# Actuals (#2632)
actuals:
  tokens: 842
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "buildConfig = true in the app module's buildFeatures block, relying only on AGP's automatic BuildConfig fields (VERSION_NAME, VERSION_CODE, APPLICATION_ID, DEBUG, BUILD_TYPE) — no buildConfigField(...) entries."

key-files:
  created: []
  modified:
    - app/build.gradle.kts
    - app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt
    - app/src/main/res/values/strings.xml

key-decisions:
  - "Applied Task 2's strings.xml deletion before running Task 1's own gate, since AGP lint's UnusedResources check (warnings-as-errors) fails on the now-dead settings_version_value resource the moment Task 1's SettingsScreen.kt edit lands, even though the plan's Task 1 text explicitly says to leave the resource in place until Task 2. This is a plan sequencing gap, not a code defect. Commits were still split by file scope exactly as planned (Task 1: build.gradle.kts + SettingsScreen.kt; Task 2: strings.xml), so the atomic-commit-per-task structure is unaffected — only the order in which the gate was run against the working tree changed."
  - "Task 2's acceptance criterion 'grep -rn 0.1.0-beta app/ returns zero lines' surfaces stale hits only inside the gitignored app/build/ directory (leftover release-variant intermediates from a prior, unrelated build in this sandbox). Verified the plan's actual stated purpose — 'no reference ... anywhere under app/src' — independently and confirmed zero hits there; app/build/ is not tracked source and regenerates on the next build."

requirements-completed: [VERS-01, VERS-02]

coverage:
  - id: D1
    description: "buildConfig = true enabled in app/build.gradle.kts's buildFeatures block, generating com.umbra.app.BuildConfig from the existing defaultConfig with no buildConfigField entries"
    requirement: "VERS-01"
    verification:
      - kind: other
        ref: "grep -c 'buildConfig = true' app/build.gradle.kts == 1; grep -c 'buildConfigField' app/build.gradle.kts == 0; grep -ci 'buildfeature' gradle.properties == 0"
        status: pass
      - kind: unit
        ref: "./gradlew compileDebugKotlin lintDebug testDebugUnitTest"
        status: pass
    human_judgment: false
  - id: D2
    description: "SettingsScreen's About Umbra Version row value reads BuildConfig.VERSION_NAME instead of stringResource(R.string.settings_version_value); title/label and every sibling row unchanged"
    requirement: "VERS-01"
    verification:
      - kind: other
        ref: "grep -c 'import com.umbra.app.BuildConfig' SettingsScreen.kt == 1; grep -c 'value = BuildConfig.VERSION_NAME' SettingsScreen.kt == 1; grep -c 'title = stringResource(R.string.settings_version)' SettingsScreen.kt == 1; git diff --numstat SettingsScreen.kt == 2 added / 1 deleted"
        status: pass
    human_judgment: true
    rationale: "The rendered composable output (does the Settings screen actually display the version correctly on a real layout) is not asserted by any unit test — this project deliberately keeps emulator/device runs opt-in per CLAUDE.md rather than launching one during phase execution."
  - id: D3
    description: "settings_version_value string resource deleted from app/src/main/res/values/strings.xml; the settings_version label entry retained; no other resource touched; no locale-variant strings.xml left holding a stale copy"
    requirement: "VERS-02"
    verification:
      - kind: other
        ref: "grep -rc settings_version_value app/src == 0; grep -rn '0.1.0-beta' app/src == 0 lines; grep -c '<string name=\"settings_version\">' strings.xml == 1; git diff --numstat strings.xml == 0 added / 1 deleted; find app/src/main/res -name strings.xml | wc -l == 1"
        status: pass
      - kind: unit
        ref: "./gradlew compileDebugKotlin lintDebug testDebugUnitTest"
        status: pass
    human_judgment: false

duration: ~10min
completed: 2026-09-05
status: complete
---

# Phase 4 Plan 1: Version Single Source of Truth Summary

**Settings' Version row now reads the AGP-generated `BuildConfig.VERSION_NAME` instead of a second, hand-maintained string resource that had already drifted to "0.1.0-beta" while `build.gradle.kts`'s real `versionName` stayed at "0.1.0".**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-09-05T22:55:00Z
- **Completed:** 2026-09-05T23:04:00Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Enabled `buildConfig = true` in `app/build.gradle.kts`'s existing `buildFeatures` block, alongside `compose = true`, so AGP generates `com.umbra.app.BuildConfig` from the already-correct `defaultConfig` (`versionCode = 1`, `versionName = "0.1.0"`) — no `buildConfigField(...)` entries added, no `gradle.properties` change.
- `SettingsScreen.kt`'s About Umbra `SettingInfoItem` now reads `value = BuildConfig.VERSION_NAME` (imported alongside the existing `R` import); the `title = stringResource(R.string.settings_version)` argument and every other row on the screen are byte-identical to before.
- Deleted the now-dead `settings_version_value` string resource ("0.1.0-beta") from `app/src/main/res/values/strings.xml`, retaining the `settings_version` label entry it sat beside. The previously-drifted literal no longer exists anywhere in the tracked source tree.
- Full Gradle gate (`compileDebugKotlin`, `lintDebug`, `testDebugUnitTest`) ran green against the combined final tree state.

## Task Commits

Each task was committed atomically:

1. **Task 1: Settings Version row reads the Gradle versionName, end to end** - `1fdc828` (feat)
2. **Task 2: Retire the drifted version string resource** - `f38487f` (fix)

_No TDD tasks in this plan — both tasks are direct build-config/UI/resource edits with no `<behavior>` block._

## Files Created/Modified

- `app/build.gradle.kts` - added `buildConfig = true` to the `buildFeatures` block
- `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt` - imports `BuildConfig`, Version row value swapped to `BuildConfig.VERSION_NAME`
- `app/src/main/res/values/strings.xml` - deleted the `settings_version_value` resource; `settings_version` label retained

## Decisions Made

- Applied Task 2's `strings.xml` deletion to the working tree before running Task 1's own verification gate — see `key-decisions` in the frontmatter for the full reasoning (AGP lint's `UnusedResources` check fails on the transiently-dead resource that Task 1's own instructions say to leave in place). Commits were still split exactly per the plan's file scope.
- Treated Task 2's `grep -rn '0.1.0-beta' app/` acceptance criterion as satisfied against the tracked source tree (`app/src`), since the only remaining hits are stale, gitignored `app/build/` intermediates from an earlier release build in this sandbox, not tracked source.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Task 1's own build gate failed on AGP lint's `UnusedResources` check**
- **Found during:** Task 1 (Settings Version row reads the Gradle versionName)
- **Issue:** The plan's Task 1 instructions explicitly leave `settings_version_value` in `strings.xml` untouched until Task 2. Once `SettingsScreen.kt` stopped referencing it, AGP lint's `UnusedResources` check (warnings-as-errors under `lintDebug`) failed the build on that now-dead resource — a genuine build gate failure, not a hypothetical one.
- **Fix:** Performed Task 2's `strings.xml` deletion before running the shared `compileDebugKotlin`/`lintDebug`/`testDebugUnitTest` gate, so the gate ran once against the final combined tree state (all three files) instead of an intermediate, lint-broken state. Commits were still split by file scope exactly as the plan specifies — Task 1's commit contains only `build.gradle.kts` and `SettingsScreen.kt`; Task 2's commit contains only the `strings.xml` deletion.
- **Files modified:** app/src/main/res/values/strings.xml (moved earlier in execution order, not in scope)
- **Verification:** `./gradlew compileDebugKotlin lintDebug testDebugUnitTest` — `BUILD SUCCESSFUL` against the combined tree; both tasks' individual acceptance-criteria greps re-verified separately afterward and all passed.
- **Committed in:** f38487f (Task 2 commit, same file the fix touches)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** No scope creep — the fix is exactly Task 2's own planned action, applied one step earlier in execution order than the plan's task-by-task narrative implied, to satisfy Task 1's own stated build-gate acceptance criterion. Both tasks' commits and diffs match the plan's file-scope boundaries exactly.

## Issues Encountered

None beyond the deviation documented above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Ready for 04-02. `build.gradle.kts`'s `versionName` is now the only place in the tracked source where a version number is written; `CHANGELOG.md`'s `[Unreleased]` -> `[0.1.0]` conversion and `docs/RELEASE_CHECKLIST.md` creation have no remaining dependency on this plan's files.
- Human verification of the rendered Settings screen (does "0.1.0" actually display correctly on-device) remains an opt-in on-device check per CLAUDE.md — not run automatically in this phase, and not blocking Phase 4's remaining plans.

---
*Phase: 04-version-consistency-v0-1-0-release-prep*
*Completed: 2026-09-05*

## Self-Check: PASSED

- FOUND: commit `1fdc828` (Task 1)
- FOUND: commit `f38487f` (Task 2)
- FOUND: `app/build.gradle.kts` contains `buildConfig = true`
- FOUND: `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt` imports `com.umbra.app.BuildConfig` and reads `value = BuildConfig.VERSION_NAME`
- FOUND: `app/src/main/res/values/strings.xml` no longer contains `settings_version_value`
- FOUND: `./gradlew compileDebugKotlin lintDebug testDebugUnitTest` passed (BUILD SUCCESSFUL)
