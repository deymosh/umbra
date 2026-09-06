# Phase 4: Version Consistency & v0.1.0 Release Prep - Context

**Gathered:** 2026-09-05
**Status:** Ready for planning

<domain>
## Phase Boundary

The app reports one true version from a single source (`app/build.gradle.kts`'s `versionName`, surfaced via `BuildConfig.VERSION_NAME`), `CHANGELOG.md` names a dated `[0.1.0]` release, a signed public release is one `git push` away (tag prepared locally, never pushed), and the release process is captured as a reusable project skill. This is release engineering and documentation, not feature work — no new user-facing capability is introduced.

</domain>

<decisions>
## Implementation Decisions

### Version display format (VERS-01, VERS-02)
- **D-01:** `SettingsScreen.kt`'s version row displays the bare value with no suffix — `BuildConfig.VERSION_NAME` ("0.1.0"), not "0.1.0-beta" or any hand-written qualifier. v0.1.0 is the first public release; VERS-01 already frames `versionName` as the single source of truth, so no second string (beta label, build metadata, git hash) gets bolted on next to it.
- **D-02:** Only the *value* changes at that call site (`stringResource(R.string.settings_version_value)` → `BuildConfig.VERSION_NAME`). The surrounding row/label ("Version" or whatever text currently frames it) is left exactly as-is — no incidental layout/label review while touching this code.
- **D-03:** `settings_version_value` string resource is deleted from `strings.xml` per VERS-02 once nothing references it — confirm via grep for the resource name, not just the literal `0.1.0-beta` text (VERS-01's own acceptance criteria only greps the literal).

### CHANGELOG release entry (REL-01)
- **D-04:** The new section header is `## [0.1.0] - 2026-09-05` — dated to when this phase's changelog work actually lands, not deferred to whenever the tag is eventually pushed.
- **D-05:** An empty `## [Unreleased]` header stays above `## [0.1.0]` (no bullets under it) — standard Keep a Changelog practice, ready to receive the next post-release entry without needing to be re-added from scratch.
- **D-06:** The existing Added/Changed/Fixed bullet lists move from `[Unreleased]` to `[0.1.0]` with **light cleanup** — fix any bullets noticed to be stale, duplicated, or superseded along the way, but this is not a full reorganization pass. Don't restructure categories or rewrite bullets that aren't actually wrong.
- **D-07:** Add a new summary of this milestone's own hardening work (Phases 1-3: log-visibility fixes, concurrency/race fixes, deletion-handling fixes, etc. — currently tracked only as `LOG-N` entries in `docs/DONE.md`) into the `[0.1.0]` section, most likely under `### Fixed` or a new subsection. This is the first time that work appears in `CHANGELOG.md` at all; keep it at a summary level (don't transcribe all ~38 `LOG-N` entries individually) — a reader-facing account of what changed, not a mirror of `docs/DONE.md`.

### Release skill (SKILL-01)
- **D-08:** `.claude/skills/umbra-release/SKILL.md` is a **runnable step-by-step runbook** — exact `gradlew` commands, exact files to edit (`app/build.gradle.kts` `versionName`/`versionCode`, `CHANGELOG.md`), exact `git tag` commands, ending in the explicit confirm-before-push gate (success criterion 5). This is a deliberate departure from the narrative-plus-checklist style of `umbra-gradle`/`umbra-signer` — optimized for "follow these exact steps next release" with minimal interpretation needed.
- **D-09:** The skill is written as a **general, reusable runbook** for any future release (bump `versionCode`/`versionName`, update `CHANGELOG.md`, tag, push), not scoped narrowly to v0.1.0 — v0.1.0 appears only as the worked example inside it. Matches SKILL-01's own wording ("reusable project skill", matching the `umbra-*` skill catalog convention).

### Release readiness verification & tag (REL-02, REL-03)
- **D-10:** Release-readiness verification (lint/tests/`assembleRelease` results, confirmed `android-release.yml` signing inputs) is recorded in a **new `docs/RELEASE_CHECKLIST.md`**, not folded only into phase planning docs — a standalone, checkable artifact meant to be reused for future releases, pairing naturally with the `umbra-release` skill (D-08/D-09). — **Reversibility:** reversible — it's a new doc; nothing else depends on its existence yet.
- **D-11:** Scouting during this discussion found `app/build.gradle.kts`'s `release` build type has **no `signingConfig` assigned at all** — signing happens post-build in CI via `r0adkll/sign-android-release` (`.github/workflows/android-release.yml`), not through a Gradle `signingConfig` block. This means `./gradlew assembleRelease` should build successfully locally too (R8-minified, just unsigned), without needing real signing keys — which would supersede the `assembleBenchmark`-as-fallback blocker noted in `STATE.md`'s Blockers/Concerns section (written under the assumption that keys were required to build at all).
- **D-12:** Given D-11, the plan/execution should **try `./gradlew assembleRelease` first** as REL-02's actual local evidence. Only fall back to `assembleBenchmark` (the roadmap's originally-anticipated substitute) if `assembleRelease` genuinely fails locally for a real reason (not just "no signing keys"). Record in `docs/RELEASE_CHECKLIST.md` whichever actually happened and why.
- **D-13:** The local `v0.1.0` git tag is an **annotated tag with a short, single-line message** — `git tag -a v0.1.0 -m "v0.1.0 — first public release"` (exact wording finalized during planning/execution), not a lightweight tag and not one embedding the full `[0.1.0]` CHANGELOG body. Tag name confirmed as `v0.1.0` (matches `android-release.yml`'s `v*` push trigger and standard `vMAJOR.MINOR.PATCH` semver convention) — user explicitly confirmed this naming.
- **D-14 (carried forward from PROJECT.md):** The tag is prepared locally only — pushing it (which triggers `android-release.yml`'s signed build + GitHub Release) is an irreversible, publicly-visible action requiring the user's explicit go-ahead, out of scope for this phase to execute. — **Reversibility:** one-way — a pushed tag triggers a real signed public GitHub Release; this constraint has already been locked at the PROJECT.md level and is restated here so planning doesn't reintroduce a push step.

### Claude's Discretion
- Exact wording of the tag's annotation message, the `[0.1.0]` Fixed-summary phrasing, and `docs/RELEASE_CHECKLIST.md`'s exact structure/checklist item wording are left to planning/execution to finalize, within the decisions above.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Version source of truth
- `app/build.gradle.kts` — `defaultConfig.versionName = "0.1.0"` (line ~28), `buildFeatures { compose = true }` block (line ~62) where `buildConfig = true` needs to be added
- `app/src/main/res/values/strings.xml` — line 359, `settings_version_value` string resource ("0.1.0-beta") to be retired
- `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt` — line 172, `value = stringResource(R.string.settings_version_value)` call site to be replaced with `BuildConfig.VERSION_NAME`

### Changelog
- `CHANGELOG.md` — Keep a Changelog format already in use; current `## [Unreleased]` section (Added/Changed/Fixed) to be split into an empty `## [Unreleased]` plus dated `## [0.1.0] - 2026-09-05`

### Release CI
- `.github/workflows/android-release.yml` — triggers on `v*` tag push or `workflow_dispatch`; builds `assembleRelease`, signs via `r0adkll/sign-android-release` (secrets `SIGNING_KEY`/`KEY_ALIAS`/`KEY_STORE_PASSWORD`/`KEY_PASSWORD`), creates GitHub Release with `generate_release_notes: true`. No `signingConfig` block exists in `app/build.gradle.kts` for the `release` build type — signing is entirely CI-side.
- `.github/workflows/android-ci.yml` — existing CI gate (`lintDebug`, `testDebugUnitTest`, `assembleBenchmark`) to cross-check against before declaring release-readiness

### Existing skill convention (for SKILL-01)
- `.claude/skills/umbra-gradle/SKILL.md` — closest existing analog for frontmatter format (`name`/`description`) and general skill structure, though D-08 deliberately departs from its narrative style toward a runnable runbook
- `.claude/skills/umbra-signer/SKILL.md`, `.claude/skills/run-umbra/SKILL.md` — other `umbra-*` skills to check for catalog consistency (naming, section ordering)

### Bug tracking (source material for D-07's changelog summary)
- `docs/DONE.md` — Phases 1-3's ~38 `LOG-N` completed entries, the source material to summarize (not transcribe) into `CHANGELOG.md`'s `[0.1.0]` section
- `docs/KNOWN_ISSUES.md` — 10 entries still open (awaiting on-device validation or the `NostrSessionManager` test-seam gap) plus LOG-35/LOG-56/LOG-57 — these stay open past v0.1.0, do not get marked fixed in the changelog

### Project-level locks
- `.planning/PROJECT.md` — "v0.1.0 tag is prepared locally only; pushing it requires the user's explicit go-ahead" (Key Decisions table) — governs D-14
- `.planning/STATE.md` — Blockers/Concerns section's `assembleRelease`/`assembleBenchmark` note, superseded in practice by D-11/D-12 (verify during execution, don't just trust the old note)
- `.planning/REQUIREMENTS.md` — VERS-01, VERS-02, REL-01, REL-02, REL-03, SKILL-01 (full acceptance criteria for this phase)
- `.planning/ROADMAP.md` — Phase 4 section, success criteria 1-5

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `buildFeatures { compose = true }` block in `app/build.gradle.kts` (~line 62) is the exact spot to add `buildConfig = true` — AGP generates `BuildConfig.VERSION_NAME` automatically once that flag is on, no manual field wiring needed.
- `.claude/skills/umbra-gradle/SKILL.md` demonstrates the project's documentation voice/depth for Gradle-touching work — useful tone reference even though SKILL-01's format departs from it (D-08).

### Established Patterns
- Keep a Changelog format is already fully in use in `CHANGELOG.md` — no new format decision needed, only content decisions (D-04 through D-07).
- The `benchmark` build type (`initWith(getByName("release"))`, `signingConfig = signingConfigs.getByName("debug")`) exists precisely because `assembleDebug` never exercises R8 — relevant context for why REL-02 cares about an R8-shaped build at all, and why D-11/D-12 matter (assembleRelease may get there without even needing benchmark's debug-signing workaround).

### Integration Points
- `SettingsScreen.kt` is the only production call site for `R.string.settings_version_value` (confirmed by grep) — no other screen or test references it, so D-01/D-02/D-03's change is fully contained to that one file plus the resource/build files.

</code_context>

<specifics>
## Specific Ideas

- User explicitly corrected/confirmed the tag naming convention mid-discussion: tag name is `v0.1.0`, matching semver `vMAJOR.MINOR.PATCH` and the existing `android-release.yml` `v*` trigger — this was a point of care for the user, not a rubber-stamp.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. No scope-creep items came up.

### Reviewed Todos (not folded)
None — `cross_reference_todos` found zero pending todos matching Phase 4 (todo_count: 0).

</deferred>

---

*Phase: 4-Version Consistency & v0.1.0 Release Prep*
*Context gathered: 2026-09-05*
