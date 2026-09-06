# Release Checklist

A dated record of the checks run before cutting a Umbra release, and what each one actually
found. This file is meant to be re-run and updated for every release, not just the first one —
add a new row (or a new dated table) rather than editing a past result in place.

| Check | Command | Observed result | Date |
|---|---|---|---|
| Unit tests | `./gradlew testDebugUnitTest` | `BUILD SUCCESSFUL` — 930 tests, 0 failures, 0 errors across the full suite | 2026-09-05 |
| Lint | `./gradlew lintDebug` | `BUILD SUCCESSFUL` — no lint findings (warnings are treated as errors in this project's lint configuration, so a clean pass means zero warnings too) | 2026-09-05 |
| R8-shaped release build | `./gradlew assembleRelease` | `BUILD SUCCESSFUL` in 2m 59s — produced `app/build/outputs/apk/release/umbra-v0.1.0.apk` (13,641,227 bytes), minified and shrunk, unsigned | 2026-09-05 |
| CI signing secrets present | `gh secret list` | All four secrets the signing step needs are present by name: `SIGNING_KEY`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD` | 2026-09-05 |
| Release tag unclaimed on the remote | `git ls-remote --tags origin` | Empty output — no `v0.1.0` tag (or any tag) exists on the remote yet | 2026-09-05 |

## What the release build produced

`./gradlew assembleRelease` builds the app's `release` build type, which has minification and
resource shrinking on but has no signing configuration of its own — the APK it produces is a
real, R8-optimized build, just not yet signed for distribution. That's expected and by design:
this project's release workflow builds `assembleRelease` in CI and signs the result there, using
`r0adkll/sign-android-release` against four repository secrets read by name only
(`SIGNING_KEY`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`). The workflow triggers on a
`v*` tag push, and can also be run manually with a `prerelease` flag and an optional `version`
override.

Confirming the four secret names above with `gh secret list` only reports whether a secret with
that name exists and when it was last updated — the command cannot return, and this checklist
never records, any secret's actual value.

## Two things this checklist deliberately does not do

- **No local keystore.** Signing happens entirely in CI, from the repository secrets above. This
  repository does not contain, and should never be made to contain, a signing keystore file or a
  local `signingConfig` pointing at one — if a future change to `app/build.gradle.kts` ever adds
  a `signingConfig` to the `release` build type, that's a sign something has gone wrong with this
  process, not a convenience worth keeping.
- **No tag push.** Pushing a release tag is what actually triggers a real, signed, publicly
  visible GitHub Release — this checklist stops short of it. It's a separate action that requires
  the person cutting the release to explicitly confirm they want to publish, after everything
  above has passed.
