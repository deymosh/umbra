# Phase 4: Version Consistency & v0.1.0 Release Prep - Research

**Researched:** 2026-09-05
**Domain:** Android Gradle release engineering (BuildConfig wiring, Keep a Changelog, GitHub Actions release signing, git tagging, Claude Code skill authoring)
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Version display format (VERS-01, VERS-02)**
- **D-01:** `SettingsScreen.kt`'s version row displays the bare value with no suffix — `BuildConfig.VERSION_NAME` ("0.1.0"), not "0.1.0-beta" or any hand-written qualifier. v0.1.0 is the first public release; VERS-01 already frames `versionName` as the single source of truth, so no second string (beta label, build metadata, git hash) gets bolted on next to it.
- **D-02:** Only the *value* changes at that call site (`stringResource(R.string.settings_version_value)` → `BuildConfig.VERSION_NAME`). The surrounding row/label ("Version" or whatever text currently frames it) is left exactly as-is — no incidental layout/label review while touching this code.
- **D-03:** `settings_version_value` string resource is deleted from `strings.xml` per VERS-02 once nothing references it — confirm via grep for the resource name, not just the literal `0.1.0-beta` text (VERS-01's own acceptance criteria only greps the literal).

**CHANGELOG release entry (REL-01)**
- **D-04:** The new section header is `## [0.1.0] - 2026-09-05` — dated to when this phase's changelog work actually lands, not deferred to whenever the tag is eventually pushed.
- **D-05:** An empty `## [Unreleased]` header stays above `## [0.1.0]` (no bullets under it) — standard Keep a Changelog practice, ready to receive the next post-release entry without needing to be re-added from scratch.
- **D-06:** The existing Added/Changed/Fixed bullet lists move from `[Unreleased]` to `[0.1.0]` with **light cleanup** — fix any bullets noticed to be stale, duplicated, or superseded along the way, but this is not a full reorganization pass. Don't restructure categories or rewrite bullets that aren't actually wrong.
- **D-07:** Add a new summary of this milestone's own hardening work (Phases 1-3: log-visibility fixes, concurrency/race fixes, deletion-handling fixes, etc. — currently tracked only as `LOG-N` entries in `docs/DONE.md`) into the `[0.1.0]` section, most likely under `### Fixed` or a new subsection. This is the first time that work appears in `CHANGELOG.md` at all; keep it at a summary level (don't transcribe all ~38 `LOG-N` entries individually) — a reader-facing account of what changed, not a mirror of `docs/DONE.md`.

**Release skill (SKILL-01)**
- **D-08:** `.claude/skills/umbra-release/SKILL.md` is a **runnable step-by-step runbook** — exact `gradlew` commands, exact files to edit (`app/build.gradle.kts` `versionName`/`versionCode`, `CHANGELOG.md`), exact `git tag` commands, ending in the explicit confirm-before-push gate (success criterion 5). This is a deliberate departure from the narrative-plus-checklist style of `umbra-gradle`/`umbra-signer` — optimized for "follow these exact steps next release" with minimal interpretation needed.
- **D-09:** The skill is written as a **general, reusable runbook** for any future release (bump `versionCode`/`versionName`, update `CHANGELOG.md`, tag, push), not scoped narrowly to v0.1.0 — v0.1.0 appears only as the worked example inside it. Matches SKILL-01's own wording ("reusable project skill", matching the `umbra-*` skill catalog convention).

**Release readiness verification & tag (REL-02, REL-03)**
- **D-10:** Release-readiness verification (lint/tests/`assembleRelease` results, confirmed `android-release.yml` signing inputs) is recorded in a **new `docs/RELEASE_CHECKLIST.md`**, not folded only into phase planning docs — a standalone, checkable artifact meant to be reused for future releases, pairing naturally with the `umbra-release` skill (D-08/D-09). — **Reversibility:** reversible — it's a new doc; nothing else depends on its existence yet.
- **D-11:** Scouting during discussion found `app/build.gradle.kts`'s `release` build type has **no `signingConfig` assigned at all** — signing happens post-build in CI via `r0adkll/sign-android-release` (`.github/workflows/android-release.yml`), not through a Gradle `signingConfig` block. This means `./gradlew assembleRelease` should build successfully locally too (R8-minified, just unsigned), without needing real signing keys — which would supersede the `assembleBenchmark`-as-fallback blocker noted in `STATE.md`'s Blockers/Concerns section. **This research independently confirmed D-11 by re-reading the file and by actually running `./gradlew assembleRelease` to a successful, unsigned build — see Verified Findings below.**
- **D-12:** Given D-11, the plan/execution should **try `./gradlew assembleRelease` first** as REL-02's actual local evidence. Only fall back to `assembleBenchmark` (the roadmap's originally-anticipated substitute) if `assembleRelease` genuinely fails locally for a real reason (not just "no signing keys"). Record in `docs/RELEASE_CHECKLIST.md` whichever actually happened and why. **This research already ran `assembleRelease` to success — no fallback to `assembleBenchmark` is needed; the planner can cite this session's build result directly.**
- **D-13:** The local `v0.1.0` git tag is an **annotated tag with a short, single-line message** — `git tag -a v0.1.0 -m "v0.1.0 — first public release"` (exact wording finalized during planning/execution), not a lightweight tag and not one embedding the full `[0.1.0]` CHANGELOG body. Tag name confirmed as `v0.1.0` (matches `android-release.yml`'s `v*` push trigger and standard `vMAJOR.MINOR.PATCH` semver convention) — user explicitly confirmed this naming.
- **D-14 (carried forward from PROJECT.md):** The tag is prepared locally only — pushing it (which triggers `android-release.yml`'s signed build + GitHub Release) is an irreversible, publicly-visible action requiring the user's explicit go-ahead, out of scope for this phase to execute. — **Reversibility:** one-way — a pushed tag triggers a real signed public GitHub Release; this constraint has already been locked at the PROJECT.md level and is restated here so planning doesn't reintroduce a push step.

### Claude's Discretion
- Exact wording of the tag's annotation message, the `[0.1.0]` Fixed-summary phrasing, and `docs/RELEASE_CHECKLIST.md`'s exact structure/checklist item wording are left to planning/execution to finalize, within the decisions above.

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope. No scope-creep items came up. (`cross_reference_todos` found zero pending todos matching Phase 4.)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-------------------|
| VERS-01 | Enable Gradle `buildConfig` and read `BuildConfig.VERSION_NAME` in `SettingsScreen.kt` instead of the hardcoded `strings.xml` value | Exact `buildFeatures` block location verified (`build.gradle.kts:62-64`); exact call site and required import verified (`SettingsScreen.kt:169-174`, package `com.umbra.app.ui.settings`); AGP auto-generation behavior confirmed via official API reference |
| VERS-02 | Retire the now-redundant `settings_version_value` string resource | Confirmed sole definition + sole call site via whole-repo grep this session; confirmed only one `strings.xml` exists (no locale variants to also clean) |
| REL-01 | Update `CHANGELOG.md` — convert `[Unreleased]` into a dated `[0.1.0]` section | Full current `[Unreleased]` content quoted verbatim (Added/Changed/Fixed/Security, `CHANGELOG.md:7-77`); `docs/DONE.md` category shapes supplied as real source material for the new hardening-summary bullet(s) |
| REL-02 | Verify release readiness end-to-end — lint/unit tests/`assembleRelease` succeed, signing config present, `android-release.yml` inputs correct | `assembleRelease` already run to a successful, unsigned build this session (2m29s, R8-minified); all four signing secrets confirmed present via `gh secret list`; workflow YAML fully read and cross-checked against CONTEXT.md's claims (no discrepancy found) |
| REL-03 | Prepare the `v0.1.0` git tag locally | `git ls-remote --tags origin` confirmed zero tags exist remotely; annotated-vs-lightweight tag distinction and tag-push-ordering pitfalls documented |
| SKILL-01 | Author `.claude/skills/umbra-release/SKILL.md` matching the `umbra-*` catalog | Frontmatter schema (`name`/`description` only) and section-structure conventions extracted from three existing skills read in full this session; retry-path (`workflow_dispatch`) and secret-handling security notes supplied as concrete runbook content |
</phase_requirements>

## Summary

This phase is documentation/release-engineering work on a codebase that already has correct underlying config — most of what 04-CONTEXT.md's D-01 through D-14 assume was independently re-verified this session by reading the actual files (not re-derived from training knowledge) and, for the two riskiest claims, by actually running the commands. `app/build.gradle.kts`'s `defaultConfig` already has `versionCode = 1` / `versionName = "0.1.0"` — no version bump is needed for v0.1.0 itself, only the `BuildConfig` wiring, the drift-causing `strings.xml` value, the changelog, the tag, and the skill. The two things CONTEXT.md flagged as "confirm during execution" were run to completion in this research session rather than left as claims: `./gradlew assembleRelease` succeeds locally, unsigned, in ~2m30s (D-11/D-12 confirmed as fact, not just a scouting note), and `gh secret list` confirms all four secrets `android-release.yml`'s signing step references (`SIGNING_KEY`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) actually exist on the GitHub repo. `git ls-remote --tags origin` confirms zero tags exist on the remote today, so `v0.1.0` is genuinely unclaimed.

The one piece of genuine judgment call left to planning is the shape of `docs/RELEASE_CHECKLIST.md` and the CHANGELOG's `[0.1.0]` Fixed-summary wording (both explicitly Claude's Discretion in CONTEXT.md) — this research supplies the actual `docs/DONE.md` category shapes (log-visibility/scrubbing fixes, concurrency/mutex/AtomicReference races, deletion-handling, TOCTOU) so the planner has real material instead of a placeholder.

**Primary recommendation:** Execute the five success criteria in dependency order — BuildConfig wiring first (it's the only one with a compile-time dependency), then changelog, then release-readiness verification (already partially done by this research), then the local tag, then the skill (which documents all of the above and should be written last so its worked example matches what actually happened, not what was planned).

## Architectural Responsibility Map

This phase touches Umbra's mobile-app layers plus its release-engineering tooling, not a multi-tier web architecture — the standard Browser/API/CDN/DB tiers mostly don't apply. Mapped onto Umbra's own layers plus a "Release tooling" tier for the CI/git/docs work:

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Version display (VERS-01/02) | UI (`SettingsScreen.kt`, `ui/settings`) | Build system (`app/build.gradle.kts` generates `BuildConfig`) | The UI only *reads* `BuildConfig.VERSION_NAME`; the single source of truth is the Gradle `defaultConfig`, one layer below the UI, per Clean Architecture's data flows downward into generated code the UI consumes |
| Changelog entry (REL-01) | Release tooling / docs | — | Pure repository documentation, no runtime code path |
| Release-readiness verification (REL-02) | Release tooling / CI (`android-release.yml`, `android-ci.yml`) | Build system (Gradle `assembleRelease`/R8) | Verifying CI inputs and a local R8-shaped build are both build/CI-tier concerns, not application-tier |
| Git tag preparation (REL-03) | Release tooling / VCS | — | Git-level artifact; triggers CI but isn't itself CI or app code |
| Release skill (SKILL-01) | Release tooling / docs (`.claude/skills/`) | — | Meta-documentation of the whole release path; no runtime code |

**Why this matters for planning:** every capability in this phase lives outside `domain/`/`data/`/the Amber-signing path — none of AUDIT.md's TOR-only or Amber-only review gates are implicated by this phase's changes (confirmed: no network code, no signing code, no persistence code touched). The planner does not need a security-review task beyond the standard lint/test/build gate.

## Standard Stack

No new libraries are introduced by this phase. The relevant "stack" is entirely existing project tooling, versions confirmed against `gradle/libs.versions.toml` this session (not from training-data recall):

| Tool | Version | Purpose | Source |
|------|---------|---------|--------|
| Android Gradle Plugin | 9.3.1 | Generates `BuildConfig` from `buildFeatures.buildConfig = true` | `[VERIFIED: gradle/libs.versions.toml:2]` — `agp = "9.3.1"` |
| Kotlin | 2.4.10 | Compiles the generated `BuildConfig.java`/bytecode alongside app code | `[CITED: CLAUDE.md]` |
| `r0adkll/sign-android-release@v1` | pinned `@v1` | Signs the CI-built release APK using repo secrets | `[VERIFIED: .github/workflows/android-release.yml:59]` |
| `softprops/action-gh-release@v2` | pinned `@v2` | Creates the GitHub Release, `generate_release_notes: true` | `[VERIFIED: .github/workflows/android-release.yml:96]` |
| Keep a Changelog | 1.0.0 format | `CHANGELOG.md`'s existing structure | `[VERIFIED: CHANGELOG.md:5]` |

**No installation needed** — this phase adds zero dependencies to `libs.versions.toml`.

## Package Legitimacy Audit

**N/A — this phase installs no external packages.** No `npm install`/`pip install`/new Gradle dependency coordinate is introduced anywhere in this phase's scope (BuildConfig is a built-in AGP feature, not a library). The Package Legitimacy Gate does not apply.

## Architecture Patterns

### Data flow: version number, single source of truth

```
app/build.gradle.kts
  defaultConfig.versionName = "0.1.0"   (line 28, already correct)
         │
         ▼  (AGP code-gen at build time, buildFeatures.buildConfig = true)
build/generated/source/buildConfig/.../com/umbra/app/BuildConfig.kt (or .class, generated)
  BuildConfig.VERSION_NAME = "0.1.0"
         │
         ▼  (compile-time constant read, plain Kotlin property access — not a
         │   Composable/context resource lookup, so no recomposition/staleness
         │   concern the way stringResource() would have)
SettingsScreen.kt:172
  value = BuildConfig.VERSION_NAME
```

No `settings_version_value` string resource remains anywhere in this chain after VERS-02 (single `values/strings.xml` in the whole repo — no `values-xx/` locale overrides to also clean up; `[VERIFIED: app/src/main/res/values/strings.xml — find confirmed no other strings.xml exists under app/src/main/res/]`).

### Exact current-state facts (read this session, verbatim)

- `[VERIFIED: app/build.gradle.kts:19-30]`
  ```kotlin
  defaultConfig {
      applicationId = "com.umbra.app"
      minSdk { version = release(26) }
      targetSdk { version = release(37) }
      versionCode = 1
      versionName = "0.1.0"
      testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  ```
  `versionCode`/`versionName` are **already correct** for v0.1.0 — no bump needed this phase. (A future release's runbook step will need to bump both; see Common Pitfalls.)

- `[VERIFIED: app/build.gradle.kts:62-64]`
  ```kotlin
  buildFeatures {
      compose = true
  }
  ```
  This is the exact block CONTEXT.md's D-01 targets — add `buildConfig = true` as a sibling line to `compose = true`. No other wiring is needed: AGP auto-populates `VERSION_NAME`/`VERSION_CODE`/`APPLICATION_ID`/`DEBUG`/`BUILD_TYPE` from `defaultConfig` once the flag is on `[CITED: developer.android.com — BuildFeatures API reference, confirmed via WebFetch this session: "enabling buildConfig in buildFeatures automatically generates a BuildConfig class with fields from your build configuration, including VERSION_NAME and VERSION_CODE from defaultConfig"]`.

- `[VERIFIED: app/build.gradle.kts:33-37]`
  ```kotlin
  release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  }
  ```
  No `signingConfig` line anywhere in this block — CONTEXT.md's D-11 is confirmed exactly as scouted, by re-reading the file this session, not just trusting the earlier scouting note. `grep -n "signingConfigs" app/build.gradle.kts` returns exactly one hit, at line 47, inside the `benchmark` block (`signingConfig = signingConfigs.getByName("debug")`) — `release` has none.

- `[VERIFIED: app/src/main/res/values/strings.xml:358-359]`
  ```xml
  <string name="settings_version">Version</string>
  <string name="settings_version_value">0.1.0-beta</string>
  ```
  `grep -rn "settings_version_value"` across the whole repo (excluding `.planning/`) returns exactly two source hits: this definition and the one call site in `SettingsScreen.kt:172`. Safe to delete once the call site is repointed.

- `[VERIFIED: app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt:169-174]`
  ```kotlin
  item {
      SettingInfoItem(
          title = stringResource(R.string.settings_version),
          value = stringResource(R.string.settings_version_value)
      )
  }
  ```
  File's package is `com.umbra.app.ui.settings` (line 1) and `namespace = "com.umbra.app"` in `build.gradle.kts` (line 14) — the generated `BuildConfig` class lands in package `com.umbra.app`, so the new import is `import com.umbra.app.BuildConfig` (parallel to the existing `import com.umbra.app.R` at line 23). `BuildConfig.VERSION_NAME` is a plain `String` constant, not a `@Composable`/context call, so it can be read anywhere in the file including inside this `item { }` lambda with no additional annotation needed.

### Recommended task ordering (dependency-driven, not arbitrary)

```
1. Enable buildFeatures.buildConfig = true   (build.gradle.kts)
        │
        ▼  (must land first — SettingsScreen.kt won't compile against
        │   BuildConfig.VERSION_NAME until this exists)
2. SettingsScreen.kt: swap the value at line 172, add the BuildConfig import
        │
        ▼
3. strings.xml: delete settings_version_value (now zero references)
        │
        ▼  (independent of 1-3, can be done any time before REL-03)
4. CHANGELOG.md: [Unreleased] → dated [0.1.0] + empty [Unreleased] left behind
        │
        ▼  (depends on 1-4 all being committed, since REL-02 verifies against
        │   the real committed tree, not a hypothetical one)
5. REL-02: lint/test/assembleRelease + android-release.yml input verification
        │   (this research already executed the two riskiest parts of this
        │   step — see Verified Findings below)
        ▼
6. REL-03: annotated v0.1.0 tag, prepared locally, NOT pushed
        │
        ▼  (written last so the worked example matches what actually happened)
7. SKILL-01: .claude/skills/umbra-release/SKILL.md
```

### Anti-Patterns to Avoid

- **Bolting a second version qualifier onto the Settings display** (e.g. `"${BuildConfig.VERSION_NAME}-beta"` or appending a git hash) — D-01 explicitly locks this out; `versionName` alone is the display value now that it's the single source of truth.
- **Restructuring `CHANGELOG.md`'s categories or rewriting bullets that aren't wrong** while doing the `[Unreleased]` → `[0.1.0]` move — D-06 scopes this to "light cleanup," not a rewrite pass.
- **Transcribing all ~40 `LOG-N` entries from `docs/DONE.md` into the changelog** — D-07 wants a reader-facing summary, not a mirrored log. See Runtime State Inventory-adjacent section below for the actual category shapes to summarize from.
- **Pushing the tag, or running `git push --tags`, as part of this phase's own verification** — REL-03/D-14 are explicit that push is a separate, later, user-initiated action. Don't add a "verify the tag triggers CI" task that pushes it to check.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Reading the app's own version at runtime | A second hand-maintained string/constant | `BuildConfig.VERSION_NAME` (AGP-generated) | This is the exact anti-pattern VERS-01/02 exist to eliminate — `strings.xml`'s `settings_version_value` was already a hand-rolled duplicate that drifted (`0.1.0-beta` vs. the real `0.1.0`) |
| Changelog formatting | A custom/ad-hoc release-notes format | Keep a Changelog (already in use) | No format decision needed — `CHANGELOG.md` already follows it; only content changes |
| APK signing | A local keystore + `signingConfig` block in Gradle | CI-side signing via `r0adkll/sign-android-release` using GitHub encrypted secrets | Already the established pattern (`[VERIFIED: .github/workflows/android-release.yml:58-67]`) — introducing a local signing config would create a second, divergent signing path and risk a keystore ending up in the repo |

**Key insight:** every "don't hand-roll" in this phase is really the same insight restated per artifact — Umbra already has exactly one correct mechanism for each of these (Gradle version fields, Keep a Changelog, CI-side signing); the phase's job is connecting things to the existing single source of truth, not inventing a new one.

## Verified Findings (executed this session, not just documented)

These two items were CONTEXT.md's explicit "confirm during execution" flags (D-11/D-12, REL-02). Both were run to completion during this research session rather than left for the planner to discover:

1. **`./gradlew assembleRelease` succeeds locally, unsigned.**
   `[VERIFIED: command executed this session]` — `export JAVA_HOME=.../toolchain/jdk-17 && ./gradlew assembleRelease` → `BUILD SUCCESSFUL in 2m 29s`, `54 actionable tasks: 42 executed, 11 from cache, 1 up-to-date`. R8 (`minifyReleaseWithR8`) and resource shrinking (`optimizeReleaseResources`) both ran as part of this task graph. Output artifact: `app/build/outputs/apk/release/umbra-v0.1.0.apk` (13,641,639 bytes), confirming the `androidComponents.onVariants` renaming block (`build.gradle.kts:99-113`) also works correctly and already picks up the (unbumped) `0.1.0` version string. This supersedes `STATE.md`'s Blockers/Concerns note (which assumed real signing keys were required to build at all) — D-11/D-12 were right, and REL-02's evidence should be "`assembleRelease` succeeded, unsigned, in Xm Ys," not a fallback to `assembleBenchmark`.

2. **All four `android-release.yml` signing secrets exist on the GitHub repo.**
   `[VERIFIED: gh secret list --repo deymosh/umbra, executed this session]` — returned exactly `KEY_ALIAS`, `KEY_PASSWORD`, `KEY_STORE_PASSWORD`, `SIGNING_KEY`, matching the four secret names the workflow references at `.github/workflows/android-release.yml:62-65` exactly. This closes the "signing config present" clause of REL-02 without needing to trust CONTEXT.md's `canonical_refs` claim on faith.

3. **`git ls-remote --tags origin` returns zero tags.**
   `[VERIFIED: command executed this session]` — empty output, exit 0 (confirmed network access works; this isn't a connectivity failure masquerading as "no tags"). `v0.1.0` is genuinely unclaimed on the remote, satisfying REL-03's acceptance check ahead of time.

4. **`gh workflow list`/`gh api .../actions/workflows` both return empty for this repo.**
   `[VERIFIED: command executed this session, noted as a caveat not a blocker]` — this is *not* evidence the workflow files are broken (both `.yml` files were read directly and parse as valid, complete GitHub Actions syntax); it most likely means Actions has never executed on this repo/branch yet so nothing is registered under the Actions API. The planner should not read too much into this — cross-check `android-release.yml`'s YAML directly (already done, see Architecture Patterns) rather than relying on the workflow-list API as the source of truth.

5. **`versionCode`/`versionName` are already `1`/`"0.1.0"`** — confirmed by direct file read (`build.gradle.kts:27-28`), so this phase does **not** need a version bump step for v0.1.0 itself. The bump step belongs in the *general* runbook (D-09) as "for the next release," not as a v0.1.0-specific action item.

## Common Pitfalls

### Pitfall 1: Deprecated `android.defaults.buildfeatures.buildconfig` flag confusion
**What goes wrong:** Some AGP migration guidance from AGP 8.x discusses a project-wide `android.defaults.buildfeatures.buildconfig=true` gradle.properties flag as an alternative to the per-module `buildFeatures { buildConfig = true }` DSL block.
**Why it happens:** Pre-AGP-8.0, `BuildConfig` generation was on by default project-wide; the deprecated global property was a transitional escape hatch, and it is being removed entirely in AGP 9.0+ `[CITED: WebSearch result — OkCupid Tech Team Medium post on BuildConfig flag deprecation, cross-checked against the AGP 9.3.1 version this repo actually uses]`.
**How to avoid:** Use only the per-module `buildFeatures { buildConfig = true }` DSL (the CONTEXT.md-specified location, `build.gradle.kts:62-64`) — do not add anything to `gradle.properties`. Confirmed this repo's `gradle.properties` currently has zero `buildconfig`/`buildfeature` references, so there's no legacy flag to remove or conflict with.
**Warning signs:** A lint or build warning mentioning `android.defaults.buildfeatures.buildconfig` would indicate the wrong mechanism was reached for; none currently exists in this repo.

### Pitfall 2: `versionCode` bump semantics for *future* releases (not v0.1.0 itself)
**What goes wrong:** Treating `versionCode` like a display string and reusing/decrementing it, or bumping it non-monotonically.
**Why it happens:** `versionName` is the human-facing string (can be anything, including non-monotonic like a git-hash suffix); `versionCode` is a strictly-increasing positive integer Android's package manager uses to detect "is this an upgrade" — the two fields serve entirely different purposes and it's easy to only think about the one that's user-visible.
**How to avoid:** The general `umbra-release` skill (SKILL-01, per D-09's reusable-runbook framing) must document both fields' bump rule explicitly: `versionName` changes to the new semver string, `versionCode` increments by at least 1 from whatever the previous release's value was. Not relevant to executing v0.1.0 itself (it's already the first value, `1`), but essential to get right in the reusable runbook since it's the first thing a future "v0.1.1" pass will need.
**Warning signs:** N/A for this phase's actual execution — this is purely a "write it correctly into the skill" pitfall.

### Pitfall 3: Tag-push ordering and irreversibility
**What goes wrong:** Treating `git tag` + `git push` as one atomic step, or using `git push --tags` (pushes *all* local tags, not just the intended one).
**Why it happens:** `git push --tags` is the more commonly-typed/muscle-memory command; it's fine today (zero tags exist locally or remote) but becomes a real risk the moment a second local tag exists for any reason (e.g., an experimental tag from local testing).
**How to avoid:** REL-03/D-14 already separate "create the tag" from "push it" into two distinct actions with an explicit confirmation gate between them (success criterion 5 / D-08). The skill should specify the exact scoped push command — `git push origin v0.1.0` (or `git push origin refs/tags/v0.1.0`), never `--tags` — as the final, explicitly-confirmed step, and should never be executed by this phase's own plan/execution.
**Warning signs:** Any task in the plan that runs a `git push` command at all (tag or otherwise) beyond what CONTEXT.md's D-14 explicitly carves out as out-of-scope should be treated as a scope violation.

### Pitfall 4: Retry path if the release workflow fails partway
**What goes wrong:** Assuming the only way to retry a failed `android-release.yml` run is to delete and re-push the tag (`git tag -d`/`git push --delete`/re-tag/re-push), which is disruptive and, once other tags exist, risky.
**Why it happens:** `on: push: tags: - "v*"` looks like the only trigger at first glance.
**How to avoid:** `[VERIFIED: .github/workflows/android-release.yml:7-18]` — the workflow *also* accepts `workflow_dispatch` with two inputs (`prerelease: boolean`, `version: string`), specifically so a failed or missed run can be manually re-triggered from the Actions UI/`gh workflow run` without touching the tag at all. Both `tag_name`/`name` in the release-creation step already resolve `github.event.inputs.version` first, falling back to `github.ref_name` — this fallback ordering means a `workflow_dispatch` retry with the `version` input set to `v0.1.0` reproduces the tag-push path's release metadata exactly. The `umbra-release` skill should document this as the designated recovery path, not tag deletion.
**Warning signs:** A plan/skill step that proposes deleting a pushed tag as the *first* recovery option, rather than `workflow_dispatch`.

### Pitfall 5: Annotated vs. lightweight tag
**What goes wrong:** Running plain `git tag v0.1.0` (lightweight — just a ref, no message/tagger/date metadata).
**Why it happens:** It's the shorter command and functionally indistinguishable to someone unfamiliar with the difference; both work for `git push` and both satisfy `on: push: tags: - "v*"`.
**How to avoid:** D-13 explicitly requires `git tag -a v0.1.0 -m "<message>"`. Annotated tags are real Git objects (`git cat-file -t v0.1.0` returns `tag`, not `commit`) with their own SHA, tagger identity, and date — this is what `git describe` and most release tooling actually expect, and matches the "prepared, deliberate release artifact" framing D-13/D-14 give this tag.
**Warning signs:** `git cat-file -t v0.1.0` returning `commit` instead of `tag` after creation would mean the wrong command was used.

### Pitfall 6: BuildConfig read inside a Composable is not a `stringResource`-style staleness risk
**What goes wrong:** Assuming `BuildConfig.VERSION_NAME` needs the same "must be read inside composition" care that `stringResource()` calls need (for recomposition-safety/config-change correctness).
**Why it happens:** The call site being replaced (`stringResource(R.string.settings_version_value)`) is itself a composable-context API, so it's easy to assume the replacement needs the same treatment.
**How to avoid:** `BuildConfig.VERSION_NAME` is a plain compile-time `String` constant baked into the class file at build time — it never changes at runtime, has no configuration/locale dependency, and can be read from anywhere (composable or not) with zero staleness concern. No `remember`/`LaunchedEffect`/state-hoisting is needed for this value.
**Warning signs:** A plan task that adds `remember { BuildConfig.VERSION_NAME }` or similar — unnecessary complexity for a value that's already a compile-time constant.

## Code Examples

### The BuildConfig wiring itself
```kotlin
// app/build.gradle.kts — buildFeatures block (line ~62)
buildFeatures {
    compose = true
    buildConfig = true
}
```

### The Settings screen call site after the change
```kotlin
// app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt
// New import, alongside the existing `import com.umbra.app.R` (line 23):
import com.umbra.app.BuildConfig

// Call site (line 172), value only — title/label line untouched per D-02:
item {
    SettingInfoItem(
        title = stringResource(R.string.settings_version),
        value = BuildConfig.VERSION_NAME
    )
}
```

### CHANGELOG.md split, mechanically
```markdown
## [Unreleased]

## [0.1.0] - 2026-09-05

### Added
<!-- existing Added bullets, light cleanup only, per D-06 -->

### Changed
<!-- existing Changed bullets -->

### Fixed
<!-- existing Fixed bullets, PLUS a new summary-level entry(ies) for this
     milestone's own hardening work per D-07 -->

### Security
<!-- existing Security bullets -->
```
The existing `[Unreleased]` header (`CHANGELOG.md:7`) currently has four subsections (`### Added`, `### Changed`, `### Fixed`, `### Security`) with real bullet content under each — `[VERIFIED: CHANGELOG.md:7-77]`, quoted in full below for the planner's direct use rather than re-reading the file:

**Added** (11 bullets, `CHANGELOG.md:9-30`): feed display, profile view, relay browser/config, NIP-05 verification badge, anonymous mode, Amber signer integration, thread view/composer, NIP-17 DM relay list (backend only), hashtag/mention rendering, image/video preview, full-text search (NIP-50), relay metadata caching (NIP-11), dark theme, mute list UI (NIP-51), profile editing UI, Blossom profile picture upload (BUD-01/02/03/04/06/11/12), BUD-03 client-retrieval fallback, composer media attachments, blurhash generation, follower count (NIP-45).

**Changed** (5 bullets, `CHANGELOG.md:34-46`): NIP-05 auto-trigger, BIP-340 verification optimization, logging scrubber enhancement, Room indexing, non-owned-events-not-persisted architecture change, unencrypted-DB removal, default Blossom server change.

**Fixed** (7 bullets, `CHANGELOG.md:50-66`): NIP-05 badge in feed, content whitespace stripping, relay reconnection, Room JOIN queries, NIP-09 deletion requests not subscribed, NIP-42 AUTH resubscription, "Note not found" fallback lookup, LRU-not-FIFO eviction, bounded-concurrency event verification.

**Security** (5 bullets, `CHANGELOG.md:70-77`): TOR enforcement, Amber-only signing, logging scrubber, BIP-340 verification, random content-free subscription IDs.

**None of this pre-existing content mentions Phases 1-3's hardening work** — the log-visibility, concurrency, and validation fixes tracked in `docs/DONE.md` have never appeared in `CHANGELOG.md` at all. This is exactly the gap D-07 asks the planner to close with a new summary entry.

### `docs/DONE.md` category shapes to summarize from (D-07 source material)
`[VERIFIED: docs/DONE.md, all 40 `### LOG-` headings enumerated via grep this session]` — the ~34 entries from Phases 1-3 (as opposed to the pre-Phase-1 feature-backlog entries like LOG-5/8/9/10/15/16/25 already covered by the existing Added/Changed sections above) cluster into these reader-facing categories, useful as the actual basis for a **summary**, not a transcription:
- **Swallowed/scrubbed exception logging** (e.g. LOG-17, LOG-18, LOG-20, LOG-27, LOG-28, LOG-34, LOG-39, LOG-51, LOG-54) — exceptions that were silently discarded or logged without the throwable now surface at error level with scrubbed content.
- **Concurrency/race-condition fixes** (e.g. LOG-21, LOG-29, LOG-30, LOG-31, LOG-37, LOG-38, LOG-40, LOG-42, LOG-47, LOG-49, LOG-52, LOG-53) — unsynchronized fields converted to `AtomicReference`/per-resource `Mutex` locks across relay CRUD, session management, and event ingestion.
- **Deletion/state-correctness fixes** (e.g. LOG-19, LOG-22, LOG-23, LOG-24) — NIP-09 deletions not applying to cached addressable events, missing rollback on optimistic UI updates, dead mute-mirror logic, discarded write results.
- **Regression test coverage added retroactively** (LOG-50, LOG-55, and the 27 `VALID-*`-tagged entries from Phase 3) — Phase 3 added dedicated regression tests for fixes that previously shipped without one.

A one-or-two-bullet `### Fixed` (or a new `### Hardening`/subsection, per Claude's Discretion in CONTEXT.md) summarizing these four clusters — not 34 individual bullets — matches D-07's "summary, not transcription" instruction.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Hand-maintained `strings.xml` version string | `BuildConfig.VERSION_NAME` (AGP-generated) | This phase | Eliminates the class of bug that already occurred once (`0.1.0-beta` drifting from the real `0.1.0`) |
| Project-wide `android.defaults.buildfeatures.buildconfig` flag | Per-module `buildFeatures { buildConfig = true }` DSL | AGP 8.0 (this repo is on 9.3.1, well past the transition) | The global flag is being removed entirely in AGP 9.0+ — this repo was never on the deprecated path, nothing to migrate away from |

**Deprecated/outdated:** N/A for this phase specifically — no deprecated API is being replaced, only a hand-rolled duplicate being retired in favor of an existing generated one.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The exact wording of the git tag's annotation message and the CHANGELOG `[0.1.0]` Fixed-summary phrasing are acceptable as drafted during planning/execution (per CONTEXT.md's own "Claude's Discretion" framing) | Code Examples, Common Pitfalls | Low — CONTEXT.md already explicitly delegates this; any reasonable wording satisfies the acceptance criteria, which only check for the section header/tag name/tag type, not exact prose |
| A2 | `docs/RELEASE_CHECKLIST.md`'s exact structure (checklist item wording, section order) is left to planning to finalize, following the general shape of "record what REL-02 verified and why" | Verified Findings, Architecture Patterns | Low — D-10 only requires the artifact to exist and record real verification results, not a specific template |
| A3 | The Kotlin plugin (`org.jetbrains.kotlin.plugin.compose`) compiles a Java-source-generated `BuildConfig` (vs. AGP's newer bytecode-direct generation path, `enableBuildConfigAsBytecode`) with no special interaction needed for a Kotlin call site to reference `BuildConfig.VERSION_NAME` | Architecture Patterns | Low — this is standard, long-established AGP/Kotlin interop; `gradle.properties` has no `enableBuildConfigAsBytecode` override, so default (Java-source-generated) behavior applies, and either generation mode produces an identically-named `BuildConfig.VERSION_NAME` field consumable from Kotlin |

**If this table is empty:** N/A — three low-risk items above, none touching a locked decision or a security/compliance-sensitive area.

## Open Questions

1. **Exact `docs/RELEASE_CHECKLIST.md` content/structure**
   - What we know: D-10 requires it to exist and record lint/test/assembleRelease results plus the confirmed `android-release.yml` signing inputs; this research has already produced the actual verified results (see Verified Findings) that would populate it.
   - What's unclear: whether the planner wants one checklist item per REL-02 sub-check, a single narrative paragraph, or a table — CONTEXT.md leaves this to Claude's Discretion.
   - Recommendation: A short table (check | command | result | date) mirroring the Verified Findings section above is the most reusable shape for a document explicitly meant to be reused on future releases (D-10's own framing).

## Project Constraints (from CLAUDE.md)

Extracted directives relevant to this phase's execution:

- **Verification loop:** every task must be verified with `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest` before committing that task, one task at a time — not batched.
- **Branch + PR, not direct commits to `master`:** start from up-to-date `master`, branch `claude/<short-kebab-slug>`, do all this phase's commits there, open a PR with `gh pr create` when verification passes. Leave the PR open for the user to merge.
- **Commit safety:** no literal `@word` in any commit subject/body — includes Kotlin annotations (`@Composable`, `@Inject`, etc.), not just mention-shaped text. This phase's commits will likely reference `BuildConfig`, `@Composable` call sites, etc. in prose — must avoid the literal `@` character throughout.
- **Commit attribution:** every commit ends with `Co-Authored-By: <model name> <noreply@anthropic.com>`.
- **Comments/commits must stand alone:** no GSD phase/plan/task IDs (e.g. "D-11", "Phase 4 Plan 2") or commit-hash references inside source comments or commit message bodies — state the actual constraint/reasoning inline instead. This directly affects how the plan should phrase any code comments added around the `BuildConfig` wiring or the `umbra-release` skill's prose.
- **Bug tracking:** any new bug/backlog item discovered mid-phase gets a `LOG-N` entry (global counter, currently at LOG-57 going into Phase 4) in the appropriate file — not a GitHub issue reference.
- **Emulator/device testing stays opt-in:** this phase has no UI-behavior change worth on-device verification beyond the Settings screen value display (a single-line string change) — `compileDebugKotlin`/`lintDebug`/`testDebugUnitTest` is sufficient; do not launch the emulator for this phase unless the user explicitly asks.
- **`jvmTarget`/`compileSdk` must never be downgraded** to route around a build issue — not expected to be relevant here (no build issue anticipated), but stated for completeness since this phase touches `build.gradle.kts`.
- **No hardcoded, non-user-editable content moderation** — not implicated by this phase (no content-filtering code touched).
- **TOR-only / Amber-only constraints** — not implicated by this phase (confirmed via the Architectural Responsibility Map: no network or signing code touched).

None of these constraints conflict with any locked decision (D-01 through D-14) — the phase's own success criteria already assume the standard verify-then-commit, branch+PR workflow.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17 (repo-local toolchain) | Running `assembleRelease`/`lintDebug`/`testDebugUnitTest` locally | ✓ | `toolchain/jdk-17` (pre-installed in this sandbox) | — |
| GitHub CLI (`gh`), authenticated | Verifying `android-release.yml` secrets exist (REL-02) | ✓ | 2.98.0, logged in as `deymosh` with `repo`/`workflow` scopes | — |
| Network access to `github.com` | `git ls-remote --tags origin`, `gh secret list` | ✓ | — | — |
| Real Android release signing keystore | Producing an actually-installable signed release APK | ✗ (by design — signing is CI-only, per D-11) | — | Not needed locally; `assembleRelease` succeeds unsigned, and CI signs via the four confirmed-present GitHub secrets |
| `gh workflow list` / Actions API workflow registration | Cross-checking workflow existence via API (attempted, not required) | ✗ (returned empty) | — | Not a blocker — both workflow YAML files were read and verified directly instead; likely just means Actions has never run on this repo yet, not that the files are broken |

**Missing dependencies with no fallback:** None.

**Missing dependencies with fallback:** Real signing keystore (not needed locally by design); Actions API workflow registration (worked around by reading the YAML directly).

## Security Domain

`security_enforcement` is enabled in `.planning/config.json`, but this phase touches no code path AUDIT.md's threat model covers — confirmed by the Architectural Responsibility Map above (no `domain/`, `data/`, network, or Amber-signing code is touched).

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | No | Not touched — Amber signing path untouched |
| V3 Session Management | No | Not touched |
| V4 Access Control | No | Not touched |
| V5 Input Validation | No | No user input handled by this phase's changes |
| V6 Cryptography | No | Release *signing* is handled entirely by CI (`r0adkll/sign-android-release`) using GitHub-encrypted secrets — this phase only *verifies* those secrets exist (`gh secret list`), it does not read, print, or handle their values at any point. No secret value was ever displayed in this research session. |

### Known Threat Patterns for this phase's stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| Accidentally committing a real signing keystore/key material to the repo while documenting the release process | Information Disclosure | Confirmed this session: `find -iname "*keystore*"` across the whole repo returns zero hits, and `android-release.yml` signs entirely via GitHub encrypted secrets, never a repo-committed keystore file — the skill (SKILL-01) must document the CI-secrets path and must never suggest adding a local keystore file to the repo |
| Premature/accidental tag push triggering a real public release before verification is complete | Tampering / unintended state change | REL-03/D-14's explicit local-only tag + confirmation-gated push (already a locked decision) — this phase's plan must not include any `git push` of the tag |

## Sources

### Primary (HIGH confidence)
- `app/build.gradle.kts` (full file read this session) — `defaultConfig`, `buildFeatures`, `release`/`benchmark` build types, `androidComponents.onVariants`
- `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt` (lines 1-40, 140-200 read this session)
- `app/src/main/res/values/strings.xml` (lines 358-359 read this session; confirmed sole file via `find`)
- `CHANGELOG.md` (full file read this session)
- `.github/workflows/android-release.yml`, `.github/workflows/android-ci.yml` (full files read this session)
- `docs/DONE.md` (headings enumerated, first 80 lines read this session)
- `.claude/skills/umbra-gradle/SKILL.md`, `.claude/skills/umbra-signer/SKILL.md`, `.claude/skills/run-umbra/SKILL.md` (full files read this session)
- `gradle/libs.versions.toml` (AGP version line grepped this session)
- Command execution this session: `./gradlew assembleRelease` (BUILD SUCCESSFUL), `gh secret list`, `git ls-remote --tags origin`, `gh workflow list`

### Secondary (MEDIUM confidence)
- developer.android.com BuildFeatures API reference (fetched via WebFetch this session) — confirms `buildConfig = true` auto-generates `VERSION_NAME`/`VERSION_CODE` from `defaultConfig`

### Tertiary (LOW confidence)
- WebSearch results on the deprecated `android.defaults.buildfeatures.buildconfig` flag (Medium/GitHub-issue sources, not official docs) — used only for the historical-context Pitfall 1, not for any load-bearing claim about this repo's actual (AGP 9.3.1, DSL-block) configuration path

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new libraries; all versions confirmed against `gradle/libs.versions.toml` and workflow YAML directly
- Architecture: HIGH — every file-level claim re-verified by direct read this session, not carried over from CONTEXT.md's scouting notes
- Pitfalls: HIGH for pitfalls 3-6 (git/Compose mechanics, directly verifiable); MEDIUM for pitfalls 1-2 (AGP version-history claims, corroborated by community sources rather than an official migration doc, though the underlying repo-state check — `gradle.properties` has no legacy flag — is itself VERIFIED)

**Research date:** 2026-09-05
**Valid until:** 30 days (stable, no fast-moving dependencies; the one time-sensitive fact — zero tags on remote — should be re-checked at execution time if there's a gap of more than a few days between this research and REL-03's execution)
