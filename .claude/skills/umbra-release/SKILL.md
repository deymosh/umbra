---
name: umbra-release
description: Use when cutting a Umbra release — bumping the version fields, dating the changelog entry, verifying the release build, recording the checklist, tagging, and triggering the signed GitHub Release.
---

# Cutting a Umbra release

A numbered, sequential runbook for any future release. Follow it top to bottom; the steps use
`X.Y.Z` as a placeholder for whatever version is actually being cut. The 0.1.0 release appears
below only as a worked example of a step already run once — it is not what this runbook is about.

## Steps

1. **Bump both version fields** in `app/build.gradle.kts`'s `defaultConfig`. The two fields behave
   differently and both must move:
   - `versionName` becomes the new semantic-version string, e.g. `"X.Y.Z"`. This is the
     human-facing value — the Settings screen reads it through the generated build constant, so
     there is no second place in the app that needs updating.
   - `versionCode` is a strictly increasing integer. It increments by at least one from the
     previous release's value and is never reused or decremented — Android's package manager uses
     it, not `versionName`, to decide whether an install is an upgrade.

   Worked example: the 0.1.0 release set `versionName = "0.1.0"` and `versionCode = 1`.

2. **Move the changelog entry.** In `CHANGELOG.md`, move the accumulated `## [Unreleased]`
   bullets into a new dated `## [X.Y.Z] - YYYY-MM-DD` section, leaving an empty `## [Unreleased]`
   header above it for the next cycle. The file already follows Keep a Changelog, so this is a
   move, not a reformat.

3. **Verify**, with these exact commands:

   ```
   ./gradlew lintDebug
   ./gradlew testDebugUnitTest
   ./gradlew assembleRelease
   ```

   The third command is the meaningful gate: it is the only one of the three that exercises R8
   minification and resource shrinking, which a debug build never touches. It succeeds locally
   unsigned — the `release` build type carries no signing configuration, and none is needed to
   build it.

4. **Record the results** in `docs/RELEASE_CHECKLIST.md`, one dated row per check.

5. **Create the tag:**

   ```
   git tag -a vX.Y.Z -m "<one-line message>"
   ```

   It must be annotated, not lightweight — an annotated tag is a real object with a tagger, a
   date, and a message, which is what release tooling expects. Confirm it with:

   ```
   git cat-file -t vX.Y.Z
   ```

   This must print `tag`, not `commit`.

6. **STOP. Confirm before pushing.** Do not push the tag without the user's explicit go-ahead.
   Pushing starts the release workflow, which builds, signs, and publishes a public GitHub Release
   that cannot be cleanly withdrawn once it exists.

7. **Only after that confirmation, push the single ref by name:**

   ```
   git push origin vX.Y.Z
   ```

   Never use the push-every-local-tag form here — it publishes every tag that happens to exist
   locally, including any experimental one, and it only takes one stray local tag for that to
   publish something nobody intended.

8. **Recovery.** If the release workflow fails partway, the first thing to try is re-running it
   from the Actions UI or the GitHub CLI using its `workflow_dispatch` trigger, with the `version`
   input set to the same tag name — the workflow resolves that input ahead of the ref name, so a
   dispatched re-run reproduces the tag-push path's release metadata exactly. Deleting and
   re-pushing the tag is not the first recovery option.

## Signing and security

Signing happens entirely in CI, from four repository secrets the workflow's signing step reads by
name: `SIGNING_KEY`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`. No secret value, encoded
key blob, or example key-store path belongs in this file, and none belongs in this repository at
all — no key-store file, no key-store path, and no Gradle release signing configuration should
ever be added here. If a future change to `app/build.gradle.kts` adds a signing configuration to
the `release` build type, that is a sign the process has gone wrong, not a convenience worth
keeping.

## Don't

- Don't push a tag before the confirm-before-push gate has actually been answered by the user.
- Don't push with the push-every-local-tag form — always name the single ref.
- Don't add a key-store file, a key-store path, or a Gradle signing configuration to this
  repository. Signing is CI-only, from repository secrets, by design.
- Don't delete and re-push a tag as the first response to a failed release run — re-dispatch the
  workflow first.
- Don't skip `./gradlew assembleRelease` in favor of a debug build when verifying a release — it
  is the only one of the three checks that actually runs R8.
