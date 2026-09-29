# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project identity

Umbra is a privacy-first, censorship-resistant Nostr client for Android — privacy and censorship resistance are what Nostr as a protocol is for, and Umbra's job is to not compromise either. Its defining, non-negotiable constraints: **all network traffic routes through TOR via Orbot's SOCKS5 proxy (127.0.0.1:9050) — no exceptions, no plaintext fallback**, and **all content moderation is performed by the user, never enforced by the app.** Signing is done exclusively through an external NIP-55 signer app (Amber is the suggested one; any installed signer works); `nsec` never touches the device.

The moderation constraint means: muting, NSFW hiding, and feed content filters (excluded hashtags/tags/content-prefixes) are all user-owned state — editable and fully removable via `FeedConfigScreen`/`ProfileScreen`, never a fixed app-side decision about what a user is allowed to see. Umbra ships with sensible defaults (a starter set of muted-noise hashtags/tags, NSFW hidden by default) so a new install isn't a wall of spam, but every one of those defaults is just a normal, user-editable `FeedFilter` entry — nothing is hardcoded or unremovable. When adding a new content-hiding mechanism, it must be built the same way: a default the user can see and turn off, not a silent app-side rule. See `domain/feed/FeedFilter.kt`/`FilterDefaults.kt` for the existing pattern.

Single-module Gradle project (`:app`), package `com.umbra.app`.

Stack: Kotlin 2.4.10 · Jetpack Compose · MVVM + Clean Architecture · Hilt · Room · OkHttp · Media3 · Coil 3 · kotlinx.serialization · BouncyCastle (BIP-340 Schnorr).
Build: AGP 9.3+ · Gradle 9.x · JDK 21 (Gradle runtime; Robolectric snapshots need it) · compileSdk 37 · minSdk 26 · jvmTarget 17.

**Before making any change, read [AUDIT.md](../AUDIT.md).** It is the master reference for security, architecture, Room, performance, and UI rules, and takes precedence over anything below. [CONTRIBUTING.md](../CONTRIBUTING.md) covers workflow/PR expectations. [.github/agents/umbra.agent.md](../.github/agents/umbra.agent.md) is GitHub Copilot's agent config for this repo — a parallel restatement of AUDIT.md's rules in that tool's own format, not additional required reading for Claude Code. This file, AUDIT.md, and the skills under `.claude/skills/` are self-sufficient; don't treat umbra.agent.md as a dependency.

## Commands

The maintainer's primary dev machine is Windows; this Claude Code sandbox and CI both run on Linux. Both wrap the same Gradle version, so the flags are identical either way — use whichever wrapper matches the shell you're actually in (`./gradlew` on Linux/macOS, `.\gradlew.bat` on Windows). Don't assume Windows by default just because CLAUDE.md historically did.

**Linux (this sandbox, CI):**

If `java`/the Android SDK aren't already on `PATH`, run `scripts/install-toolchain.sh` once — it installs a repo-local JDK 21 + Android SDK cmdline-tools under `toolchain/` (gitignored, never touches a system-wide install) and regenerates `local.properties` to point at it. Re-running is safe; already-installed pieces are skipped. Linux x86_64/aarch64 only — see the script's own header comment.

```bash
# One-time setup (skip if JAVA_HOME/SDK are already configured)
scripts/install-toolchain.sh
export JAVA_HOME="$(pwd)/toolchain/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"

# Fast iterative compile check (use this most often while editing)
./gradlew compileDebugKotlin

# Run all unit tests
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.umbra.app.domain.usecase.GetAllRelaysUseCaseTest"

# Run a single test method
./gradlew testDebugUnitTest --tests "com.umbra.app.domain.usecase.GetAllRelaysUseCaseTest.given relays exist when invoked then returns relay list"

# Lint (CI treats warnings as errors)
./gradlew lintDebug

# Build and install debug APK on a connected device/emulator
./gradlew installDebug

# Assemble debug APK without installing
./gradlew assembleDebug

# UI snapshots (Roborazzi/Robolectric, no device needed) — see docs/UI_SNAPSHOTS.md
./gradlew recordRoborazziDebug   # (re)write goldens in app/src/test/snapshots/
./gradlew verifyRoborazziDebug   # fail on visual diffs against the goldens
```

**Windows:**

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
.\gradlew.bat testDebugUnitTest --tests "com.umbra.app.domain.usecase.GetAllRelaysUseCaseTest"
.\gradlew.bat testDebugUnitTest --tests "com.umbra.app.domain.usecase.GetAllRelaysUseCaseTest.given relays exist when invoked then returns relay list"
.\gradlew.bat lintDebug
.\gradlew.bat installDebug
.\gradlew.bat assembleDebug
```

If `java` isn't on PATH, JDK 21 must be set explicitly:
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21-hotspot"
$env:Path = $env:Path + ";$env:JAVA_HOME\bin"
```

CI (`.github/workflows/android-ci.yml`) runs `lintDebug`, `testDebugUnitTest`, and `assembleBenchmark` on every push/PR to `master`. The `benchmark` build type is release-shaped (R8 minified, `isDebuggable = false`) but debug-keystore signed so the resulting APK still installs via `adb install` — CI building it (rather than plain `assembleDebug`) is what would have caught the SQLCipher R8 keep-rule gap discovered in practice, since a debug build never exercises R8 at all. A change isn't done until compile + tests pass locally.

## Workflow

- **Emulator/device testing is opt-in only** (`run-umbra`, `installDebug`, on-device UI driving) — only when the user explicitly asks. Otherwise verify with `compileDebugKotlin` / `lintDebug` / `testDebugUnitTest`, plus Roborazzi snapshots for UI.
- **Multi-part requests:** one task at a time — implement, verify, commit — before the next. Don't batch unrelated changes into one commit.
- **Branch + PR, not `master`.** Start from an up-to-date `master` on `<type>/<short-kebab-slug>` — `type` is one of `feat`, `fix`, `docs`, `build`, `ci`, `chore`, `refactor`, `perf`, `test` (e.g. `feat/add-zap-button`, `fix/nip05-badge-display`) — one branch per request, all its commits there, open a PR with a real summary once verification passes, and leave it for the user to merge unless they ask you to. Small doc/config housekeeping the user directs turn-by-turn may go to `master` if they say so.
- **No literal `@word` in commit messages** — GitHub turns `@Composable`, `@Inject`, `@Named("tor")` into mentions exactly like a username. Drop the `@`, quote it, or spell it out; scan every drafted message for `@` before committing. If one ships on a solo, unmerged branch: tag a backup, `git reset --hard` to the last clean commit, `git cherry-pick <sha> --no-commit` + corrected commit for each (never `rebase -i`), check `git diff <backup> HEAD` is empty, `git push --force-with-lease`, delete the tag. Keep messages neutral and English.
- **Attribution:** every commit ends with a `Co-Authored-By: <model name> <noreply@anthropic.com>` trailer naming the Claude model that did the work.
- **Comments and commit bodies stand alone.** Never explain code by citing a planning-doc id, ticket number or commit hash — state the constraint or reason itself, since those references go stale.

## UI and design

All UI work follows the `umbra-design` skill (tokens, components, copy, and the snapshot review
loop in `docs/UI_SNAPSHOTS.md`). Verify visual changes with `recordRoborazziDebug` and look at
the images — no emulator needed.

## Architecture

Strict Clean Architecture, one-directional dependencies:

```
ui/screen → ui/viewmodel → domain/usecase → domain/repository (interface)
                                           → domain/model
data/ implements domain/repository interfaces, maps data entities ↔ domain models
```

- `domain/` never imports from `data/` or `android.*`/`androidx.*` — it's pure Kotlin, testable without a device.
- `ui/` ViewModels import only from `domain/`, never `data.*` directly.
- Nostr NIPs are implemented as their own `domain/nipXX/` packages (nip01, nip05, nip11, nip17, nip19, nip22, nip25, nip30, nip44, nip45, nip65, ...) rather than being scattered across generic model/usecase files — when implementing a new NIP, follow this per-NIP package convention.
- State: `StateFlow<UiState>` exclusively (no `LiveData`), updated via `_state.update { it.copy(...) }`. UI state data classes are `@Immutable`.
- Side effects (navigation, Amber signing) go through `SharedFlow`, never mutable callback vars or direct `startActivity()` from a ViewModel.
- All network access funnels through a single `@Named("tor") OkHttpClient` from `NetworkModule` — Coil's `ImageLoader` and Media3's `OkHttpDataSource.Factory` both reuse it. There is intentionally no code path that constructs a second client.
- Signing flows exclusively through `AmberSignerGateway` (domain) → `AmberConnector` (data/amber) → the NIP-55 signer chosen at login via Android intents. `canSignWithAmber()` gates every write action.
- Persistence: a single encrypted (SQLCipher) Room database — there is no second, unencrypted one. Only the signed-in user's own events persist there; everyone else's content lives only in an in-memory, access-order `EventLruCache` (`data/repository/EventLruCache.kt`) and is re-fetched from relays as needed (matching Amethyst's pure in-memory event graph — see `EventRepository.fetchEventById()`). `EventCrypto.verifyEvent()` (event ID integrity + BIP-340 Schnorr) runs before anything is persisted; failed verification is dropped silently.
- Reusable Compose components live in `ui/components/` (e.g. `UserAvatar`, `NostrTextRenderer`, `AmberSignEffect`, `ExternalUrlWarningDialog`) — check there before writing a new composable; duplicating one is a review flag.

`AUDIT.md` Part 6 has the full directory-by-directory layer map if you need it; don't re-derive it here, read that file.

## Absolute constraints (do not suggest workarounds)

- No network path bypassing the TOR proxy; no `HttpURLConnection`/`InetAddress.getByName()`/`DownloadManager`; no trust-all TLS.
- No `nsec` or private key material anywhere in code, state, or logs (outside the two allow-listed detection/scrub sites documented in AUDIT.md §1.2).
- Logs must be scrubbed of relay URLs, pubkeys, and profile/event content in release builds (`LogScrubber` helpers), gated behind `Log.isLoggable`.
- Every externally-opened URL shows `ExternalUrlWarningDialog` first (except Amber intents).
- `@UnstableApi` (Media3) confined to the single file that instantiates `ExoPlayer`/`PlayerView` — never propagated upward.
- `jvmTarget`/`compileSdk` must not be downgraded to work around a build issue — fix the root cause.
- No hardcoded, non-user-editable content moderation: any new filter that hides/excludes content by hashtag, author, keyword, or similar must be a `FeedFilter`-style default the user can see and turn off (see `domain/feed/FilterDefaults.kt`), never an unconditional app-side rule.

See `AUDIT.md` for the complete rule set and the exact "what to flag" checklist per topic — this file only summarizes what changes review outcomes.

## NIP coverage

Implementation status per NIP: `README.md` (quick view) and [docs/nip-social-coverage.md](../docs/nip-social-coverage.md) (detailed). Sequencing/priority for unimplemented NIPs: [docs/nip-priority-roadmap.md](../docs/nip-priority-roadmap.md). Any new NIP work must preserve the TOR-only and external-signer-only constraints above — they are not negotiable per-feature.

## Reference client: Amethyst

[Amethyst](https://github.com/vitorpamplona/amethyst) is the most feature-complete Nostr client on Android and is known for staying fluid under heavy feed/list load. It's also Kotlin/Compose, so it's a directly comparable reference point — not something to port wholesale, since Umbra's threat model (TOR-only, external-signer-only signing, no on-device keys) is stricter than Amethyst's and must never be relaxed to match it.

Use it as a comparison point for:
- **NIP scope/breadth** — when deciding whether a NIP is worth prioritizing or how a rarer one is typically modeled as events/tags.
- **Feed and list performance** — LazyColumn item stability, recomposition avoidance, and caching strategy for a high-churn, high-volume event stream, which is the same core performance problem Umbra's feed has.
- **Event/profile caching patterns** — Umbra's non-owned-event cache is already modeled directly on Amethyst's approach (pure in-memory, no general-purpose event database, on-demand relay fetch for cache misses) rather than just compared against it — see `data/repository/EventLruCache.kt` and `EventRepository.fetchEventById()`.

Nothing about Amethyst overrides `AUDIT.md`; if a pattern conflicts with the TOR-only or external-signer-only rules, the rule wins.
