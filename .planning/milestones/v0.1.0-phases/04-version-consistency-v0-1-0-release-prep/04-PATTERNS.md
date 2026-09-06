# Phase 4: Version Consistency & v0.1.0 Release Prep - Pattern Map

**Mapped:** 2026-09-05
**Files analyzed:** 7 (2 code, 3 docs, 1 skill, 1 config)
**Analogs found:** 7 / 7 (all either edit-in-place with a clear existing shape, or have a direct sibling-file analog)

This phase is release-engineering/documentation work, not new feature code — there are no controller/service/component roles in the usual sense. "Analog" here means "the existing file/section whose shape the new or edited content must match," which RESEARCH.md already verified in full for every locked decision. This file translates that into copy-from-here pattern assignments for the planner.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `app/build.gradle.kts` (`buildFeatures` block) | config | transform (build-time codegen toggle) | same file, `benchmark`/`release` block conventions | exact (edit in place) |
| `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt` (line ~172) | component (Compose screen) | request-response (read compile-time constant, render) | same file's own `SettingInfoItem` call pattern | exact (edit in place) |
| `app/src/main/res/values/strings.xml` (delete `settings_version_value`) | config (resources) | CRUD (delete) | same file, sibling `settings_version` entry that stays | exact (edit in place) |
| `CHANGELOG.md` (`[Unreleased]` → `[0.1.0]` split) | config (docs) | transform (reshape existing content) | same file's own Keep a Changelog structure | exact (edit in place) |
| `docs/RELEASE_CHECKLIST.md` (new) | utility (docs/checklist) | batch (record verification results) | `docs/KNOWN_ISSUES.md` (status/entry conventions), RESEARCH.md's own Verified Findings table | role-match |
| `.claude/skills/umbra-release/SKILL.md` (new) | utility (project skill) | request-response (runbook, human-executed steps) | `.claude/skills/umbra-gradle/SKILL.md` | role-match (structure), explicit style departure (D-08) |
| `v0.1.0` git tag (local, annotated) | config (VCS artifact) | event-driven (one-shot, triggers CI on push — but push is out of scope) | n/a — no prior tag exists in this repo (confirmed zero tags remote/local) | no analog (first tag ever cut) |

## Pattern Assignments

### `app/build.gradle.kts` — enable `buildConfig`

**Analog:** same file, `buildFeatures` block (lines 62-64) and the `benchmark`/`release` block style around it.

**Current state** (`[VERIFIED: app/build.gradle.kts:62-64]`):
```kotlin
buildFeatures {
    compose = true
}
```

**Pattern to apply** — add a sibling boolean flag, matching the existing one-flag-per-line style:
```kotlin
buildFeatures {
    compose = true
    buildConfig = true
}
```
No other wiring needed — AGP auto-populates `BuildConfig.VERSION_NAME`/`VERSION_CODE`/`APPLICATION_ID`/`DEBUG`/`BUILD_TYPE` from the existing `defaultConfig` block (`build.gradle.kts:19-30`, already has `versionName = "0.1.0"`) once this flag is on.

Do not touch `gradle.properties` (Pitfall 1 in RESEARCH.md — the deprecated project-wide flag is a different, non-applicable mechanism).

---

### `SettingsScreen.kt` — swap the version value call site

**Analog:** same file's own call-site shape one property over — `title` stays a `stringResource` call, only `value` changes source.

**Current state** (`[VERIFIED: SettingsScreen.kt:169-174]`):
```kotlin
item {
    SettingInfoItem(
        title = stringResource(R.string.settings_version),
        value = stringResource(R.string.settings_version_value)
    )
}
```

**Pattern to apply** — import alongside the existing `import com.umbra.app.R` (line 23):
```kotlin
import com.umbra.app.BuildConfig
```
Call site, value only (title/label line untouched per D-02):
```kotlin
item {
    SettingInfoItem(
        title = stringResource(R.string.settings_version),
        value = BuildConfig.VERSION_NAME
    )
}
```
`BuildConfig.VERSION_NAME` is a plain compile-time `String` constant — no `remember`/`LaunchedEffect`, no composable-context requirement beyond what's already there (Pitfall 6).

---

### `strings.xml` — retire `settings_version_value`

**Analog:** same file, the sibling entry `settings_version` that is kept.

**Current state** (`[VERIFIED: strings.xml:358-359]`):
```xml
<string name="settings_version">Version</string>
<string name="settings_version_value">0.1.0-beta</string>
```

**Pattern to apply** — delete only the second line once `SettingsScreen.kt` no longer references it. Verify via `grep -rn "settings_version_value"` (resource name, not just the literal string) returning zero source hits before deleting (D-03). Single `values/strings.xml` in the repo, no locale variants to also edit.

---

### `CHANGELOG.md` — split `[Unreleased]` into dated `[0.1.0]`

**Analog:** same file's existing Keep a Changelog structure (already in use, no format change).

**Current state** (`[VERIFIED: CHANGELOG.md:1-20]`):
```markdown
# Changelog

All notable changes to Umbra are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Feed display with chronological notes
- Profile view with recent notes and metadata
...
```

**Pattern to apply** (mechanical reshape, D-04/D-05/D-06):
```markdown
## [Unreleased]

## [0.1.0] - 2026-09-05

### Added
<!-- existing Added bullets moved here, light cleanup only -->

### Changed
<!-- existing Changed bullets moved here -->

### Fixed
<!-- existing Fixed bullets moved here, PLUS a new summary-level entry
     for this milestone's own hardening work (see D-07 source material below) -->

### Security
<!-- existing Security bullets moved here -->
```

**D-07 summary-bullet source material** (from `docs/DONE.md`, do not transcribe individual `LOG-N` entries) — cluster into 3-4 reader-facing bullets:
- Swallowed/scrubbed exception logging surfaced at error level (was silently discarded or logged without the throwable).
- Concurrency/race-condition fixes — unsynchronized fields converted to `AtomicReference`/per-resource `Mutex` locks across relay CRUD, session management, event ingestion.
- Deletion/state-correctness fixes — NIP-09 deletions not applying to cached addressable events, missing rollback on optimistic UI updates, dead mute-mirror logic.
- (Optional) Regression test coverage added retroactively for these fixes.

**Do not** mark anything from `docs/KNOWN_ISSUES.md` (10 still-open entries) as fixed in this changelog entry.

---

### `docs/RELEASE_CHECKLIST.md` (new file)

**Analog for entry/status conventions:** `docs/KNOWN_ISSUES.md`'s heading style (bolded `**Status:**`/`**Found:**` field lines under a `###` heading) — reused here as a table per RESEARCH.md's own Open Questions recommendation, since this is a reusable checklist artifact rather than a per-bug log.

**Analog for content:** RESEARCH.md's "Verified Findings" section — already contains the real, executed results this file exists to persist. Reuse directly rather than re-deriving:

```markdown
| Check | Command | Result | Date |
|---|---|---|---|
| Unminified build sanity | `./gradlew testDebugUnitTest` | (record pass/fail) | 2026-09-05 |
| Lint | `./gradlew lintDebug` | (record pass/fail) | 2026-09-05 |
| Release build (R8, unsigned) | `./gradlew assembleRelease` | BUILD SUCCESSFUL in 2m 29s, `umbra-v0.1.0.apk` (13,641,639 bytes) | 2026-09-05 |
| CI signing secrets present | `gh secret list` | `KEY_ALIAS`, `KEY_PASSWORD`, `KEY_STORE_PASSWORD`, `SIGNING_KEY` all present | 2026-09-05 |
| Tag unclaimed on remote | `git ls-remote --tags origin` | empty output — `v0.1.0` unclaimed | 2026-09-05 |
```
Structure/wording beyond this shape is Claude's Discretion (A2 in RESEARCH.md) — a table mirroring Verified Findings is the recommended, reusable shape.

---

### `.claude/skills/umbra-release/SKILL.md` (new file)

**Analog:** `.claude/skills/umbra-gradle/SKILL.md` for frontmatter schema and general structural conventions — **but D-08 deliberately departs from its narrative-plus-checklist voice** in favor of an exact runnable runbook.

**Frontmatter pattern** (`[VERIFIED: umbra-gradle/SKILL.md:1-4]`):
```markdown
---
name: umbra-gradle
description: Use when touching app/build.gradle.kts, gradle/libs.versions.toml, proguard-rules.pro, or the benchmark build type. ...
---
```
Apply the same two-key frontmatter shape with `name: umbra-release` and a `description:` stating exactly when to invoke it (any future version bump / release cut).

**Structural pattern to depart from (reference only, not to copy verbatim):** `umbra-gradle/SKILL.md` uses prose sections with embedded code blocks and a closing "Don't" bullet list (lines 53-57). `umbra-release/SKILL.md` should instead be a **numbered, sequential runbook** — exact commands in the exact order they must run, e.g.:

```markdown
## Release runbook

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`'s `defaultConfig` block
   (versionCode: increment by >=1 from the previous release; versionName: new semver string).
2. Update `CHANGELOG.md`: move `[Unreleased]` bullets into a new dated `## [X.Y.Z] - <date>`
   section, leave an empty `## [Unreleased]` above it.
3. Verify: `./gradlew lintDebug && ./gradlew testDebugUnitTest && ./gradlew assembleRelease`
   (unsigned R8 build; CI signs via `r0adkll/sign-android-release` using repo secrets —
   no local keystore needed).
4. Tag locally: `git tag -a vX.Y.Z -m "vX.Y.Z — <short description>"` (annotated, not
   lightweight — verify with `git cat-file -t vX.Y.Z` returning `tag`).
5. STOP — confirm with the user before pushing. Do not run `git push` for the tag
   without explicit go-ahead.
6. (After confirmation) `git push origin vX.Y.Z` — never `git push --tags`.
7. If `android-release.yml` fails partway, retry via `workflow_dispatch` with the
   `version` input set to the same tag name — do not delete/re-push the tag as the
   first recovery option.
```

**Security note to embed** (from RESEARCH.md's Known Threat Patterns table): document that signing is entirely CI-side via GitHub encrypted secrets (`SIGNING_KEY`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) — the skill must never suggest adding a local keystore file to the repo.

---

### `v0.1.0` git tag

**No analog** — this is the first tag ever cut in this repo (`git ls-remote --tags origin` returned empty this session). Exact command, per D-13:
```bash
git tag -a v0.1.0 -m "v0.1.0 — first public release"
```
Verify it's annotated (not lightweight): `git cat-file -t v0.1.0` must return `tag`, not `commit`.
**Do not push** — REL-03/D-14 scope this phase to local-tag-only; pushing triggers `.github/workflows/android-release.yml`'s real signed public release and requires separate explicit user go-ahead.

## Shared Patterns

### Keep a Changelog format
**Source:** `CHANGELOG.md` (already fully in use — no format decision needed)
**Apply to:** The `[0.1.0]` section split only; do not introduce a new format or restructure categories that aren't wrong (D-06).

### CI-side signing, never local keystore
**Source:** `.github/workflows/android-release.yml:58-67` (`r0adkll/sign-android-release`, four GitHub secrets)
**Apply to:** `docs/RELEASE_CHECKLIST.md` and `umbra-release/SKILL.md` both — neither should introduce or reference a local signing keystore/`signingConfig` block; `release` build type in `app/build.gradle.kts` (lines 33-37) intentionally has none.

### Confirm-before-push gate for irreversible actions
**Source:** D-14 (carried from `PROJECT.md`), Pitfall 3 in RESEARCH.md
**Apply to:** `umbra-release/SKILL.md`'s runbook (an explicit stop-and-confirm step before any `git push` of a release tag) and this phase's own execution (no plan task may run `git push` for the tag).

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `v0.1.0` git tag | config (VCS) | event-driven | First tag ever cut in this repo — no prior tag to pattern-match against; command syntax is fully specified by D-13 instead. |

## Metadata

**Analog search scope:** `app/build.gradle.kts`, `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt`, `app/src/main/res/values/strings.xml`, `CHANGELOG.md`, `docs/KNOWN_ISSUES.md`, `docs/DONE.md`, `.claude/skills/umbra-gradle/SKILL.md`, `.github/workflows/android-release.yml`
**Files scanned:** 8
**Pattern extraction date:** 2026-09-05
