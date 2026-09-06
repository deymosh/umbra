---
phase: 04-version-consistency-v0-1-0-release-prep
reviewed: 2026-09-06T00:00:00Z
depth: standard
files_reviewed: 6
files_reviewed_list:
  - app/build.gradle.kts
  - app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt
  - app/src/main/res/values/strings.xml
  - CHANGELOG.md
  - docs/RELEASE_CHECKLIST.md
  - .claude/skills/umbra-release/SKILL.md
findings:
  critical: 0
  warning: 1
  info: 1
  total: 2
status: issues_found
---

# Phase 04: Code Review Report

**Reviewed:** 2026-09-06T00:00:00Z
**Depth:** standard
**Files Reviewed:** 6
**Status:** issues_found

## Summary

Phase 4's actual diff is small and low-risk: `app/build.gradle.kts` gained one line
(`buildFeatures.buildConfig = true`), `SettingsScreen.kt` swapped a hand-maintained
`stringResource(R.string.settings_version_value)` for `BuildConfig.VERSION_NAME` (verified: passed
directly as a `String` argument, not wrapped in `remember`/`mutableStateOf` — no state-holder
anti-pattern introduced), `strings.xml` dropped the now-dead `settings_version_value` resource
(confirmed via repo-wide grep: no remaining references), and `CHANGELOG.md` / `docs/RELEASE_CHECKLIST.md`
/ `.claude/skills/umbra-release/SKILL.md` are new/edited documentation for the 0.1.0 cut.

Checked specifically per the review brief: no secret values, keystore paths, or key material appear
in `RELEASE_CHECKLIST.md` or `SKILL.md` (both explicitly disclaim storing any); no literal
`git push --tags` / push-every-local-tag form appears anywhere in `SKILL.md` (it correctly uses
`git push origin vX.Y.Z` and explicitly warns against the all-tags form).

One documentation-accuracy gap was found by cross-referencing `SKILL.md`'s recovery guidance against
the actual `.github/workflows/android-release.yml` it describes (out of the explicit file list, but
necessary to check the claim it makes), plus one minor pre-existing code-quality item in
`SettingsScreen.kt` noticed while reading the full file.

## Warnings

### WR-01: Release runbook's `workflow_dispatch` recovery step doesn't guarantee the correct commit is built

**File:** `.claude/skills/umbra-release/SKILL.md:74-78`
**Issue:** Step 8 says:

> the first thing to try is re-running it from the Actions UI or the GitHub CLI using its
> `workflow_dispatch` trigger, with the `version` input set to the same tag name — the workflow
> resolves that input ahead of the ref name, so a dispatched re-run reproduces the tag-push path's
> release metadata exactly.

Checking this against `.github/workflows/android-release.yml`: the `version` input is only used
for the release's `tag_name`/`name` and the APK filename (`dist/umbra-${TAG}.apk`) — it does *not*
control which commit `actions/checkout@v5` fetches. With no explicit `ref:` given to `checkout`,
a `workflow_dispatch` run builds from whatever branch/tag is selected in the "Use workflow from"
dropdown (Actions UI) or the `--ref` flag (`gh workflow run`), which defaults to the repository's
default branch if not changed. If a release-tag push fails partway and the recovery is dispatched
without deliberately selecting the `vX.Y.Z` tag as the ref, the workflow will build and sign
whatever commit `master` is currently at — which may already differ from the commit the tag
actually points to (e.g. if unrelated work landed on `master` in between) — and publish it under
the `vX.Y.Z` release name. That contradicts the "reproduces the tag-push path's release metadata
exactly" claim for the part that matters most: the actual bytes shipped in the signed APK. For a
project whose whole premise is a trustworthy, auditable client, silently releasing "vX.Y.Z" built
from a different commit than the one tagged `vX.Y.Z` is a real risk, not just a metadata nit.

**Fix:** Make Step 8 give a literal, ref-pinned command instead of describing the trigger
generically, e.g.:

```
gh workflow run android-release.yml --ref vX.Y.Z -f version=vX.Y.Z -f prerelease=false
```

and note explicitly that `--ref vX.Y.Z` (or selecting the `vX.Y.Z` tag, not a branch, in the
Actions UI dropdown) is what makes the rebuild match the original tagged commit — the `version`
input alone only affects the release's displayed name and artifact filename, not what gets checked
out and built.

## Info

### IN-01: Redundant imports in `SettingsScreen.kt`

**File:** `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt:10-12,32`
**Issue:** Lines 10-11 explicitly import `androidx.compose.material3.Card` and
`androidx.compose.material3.CardDefaults`, immediately followed by a wildcard
`import androidx.compose.material3.*` on line 12 that already covers both — the two explicit
imports are dead weight. Separately, line 32 has `import kotlin.OptIn`, which is redundant since
`kotlin.*` (including `OptIn`) is implicitly imported into every Kotlin file by default. These
predate this phase's diff (phase 4 only added the `com.umbra.app.BuildConfig` import), but the
file was touched this phase and is in scope; worth a quick cleanup pass.
**Fix:**
```kotlin
// Remove these two (already covered by the wildcard import below):
// import androidx.compose.material3.Card
// import androidx.compose.material3.CardDefaults
import androidx.compose.material3.*
...
// Remove (redundant — kotlin.* is implicitly imported):
// import kotlin.OptIn
```

---

_Reviewed: 2026-09-06T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
