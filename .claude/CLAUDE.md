# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project identity

Umbra is a privacy-first, censorship-resistant Nostr client for Android — privacy and censorship resistance are what Nostr as a protocol is for, and Umbra's job is to not compromise either. Its defining, non-negotiable constraints: **all network traffic routes through TOR via Orbot's SOCKS5 proxy (127.0.0.1:9050) — no exceptions, no plaintext fallback**, and **all content moderation is performed by the user, never enforced by the app.** Signing is done exclusively through Amber (external signer); `nsec` never touches the device.

The moderation constraint means: muting, NSFW hiding, and feed content filters (excluded hashtags/tags/content-prefixes) are all user-owned state — editable and fully removable via `FeedConfigScreen`/`ProfileScreen`, never a fixed app-side decision about what a user is allowed to see. Umbra ships with sensible defaults (a starter set of muted-noise hashtags/tags, NSFW hidden by default) so a new install isn't a wall of spam, but every one of those defaults is just a normal, user-editable `FeedFilter` entry — nothing is hardcoded or unremovable. When adding a new content-hiding mechanism, it must be built the same way: a default the user can see and turn off, not a silent app-side rule. See `domain/feed/FeedFilter.kt`/`FilterDefaults.kt` for the existing pattern.

Single-module Gradle project (`:app`), package `com.umbra.app`.

Stack: Kotlin 2.4.10 · Jetpack Compose · MVVM + Clean Architecture · Hilt · Room · OkHttp · Media3 · Coil 3 · kotlinx.serialization · BouncyCastle (BIP-340 Schnorr).
Build: AGP 9.3+ · Gradle 9.x · JDK 17 · compileSdk 37 · minSdk 26 · jvmTarget 17.

**Before making any change, read [AUDIT.md](../AUDIT.md).** It is the master reference for security, architecture, Room, performance, and UI rules, and takes precedence over anything below. [CONTRIBUTING.md](../CONTRIBUTING.md) covers workflow/PR expectations. [.github/agents/umbra.agent.md](../.github/agents/umbra.agent.md) is GitHub Copilot's agent config for this repo — a parallel restatement of AUDIT.md's rules in that tool's own format, not additional required reading for Claude Code. This file, AUDIT.md, and the skills under `.claude/skills/` are self-sufficient; don't treat umbra.agent.md as a dependency.

## Commands

The maintainer's primary dev machine is Windows; this Claude Code sandbox and CI both run on Linux. Both wrap the same Gradle version, so the flags are identical either way — use whichever wrapper matches the shell you're actually in (`./gradlew` on Linux/macOS, `.\gradlew.bat` on Windows). Don't assume Windows by default just because CLAUDE.md historically did.

**Linux (this sandbox, CI):**

If `java`/the Android SDK aren't already on `PATH`, run `scripts/install-toolchain.sh` once — it installs a repo-local JDK 17 + Android SDK cmdline-tools under `toolchain/` (gitignored, never touches a system-wide install) and regenerates `local.properties` to point at it. Re-running is safe; already-installed pieces are skipped. Linux x86_64/aarch64 only — see the script's own header comment.

```bash
# One-time setup (skip if JAVA_HOME/SDK are already configured)
scripts/install-toolchain.sh
export JAVA_HOME="$(pwd)/toolchain/jdk-17"
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

If `java` isn't on PATH, JDK 17 must be set explicitly:
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$env:Path = $env:Path + ";$env:JAVA_HOME\bin"
```

CI (`.github/workflows/android-ci.yml`) runs `lintDebug`, `testDebugUnitTest`, and `assembleBenchmark` on every push/PR to `master`. The `benchmark` build type is release-shaped (R8 minified, `isDebuggable = false`) but debug-keystore signed so the resulting APK still installs via `adb install` — CI building it (rather than plain `assembleDebug`) is what would have caught the SQLCipher R8 keep-rule gap discovered in practice, since a debug build never exercises R8 at all. A change isn't done until compile + tests pass locally.

## Workflow

- **Emulator/device testing (the `run-umbra` skill, `installDebug`, on-device screenshots/UI driving) is opt-in only** — use it exclusively when the user explicitly asks to run/test on the emulator. Otherwise, verify changes with `compileDebugKotlin` / `lintDebug` / `testDebugUnitTest` alone; do not launch the emulator on your own initiative just because a change is UI-related.
- **When a request breaks down into multiple tasks or phases**, work through them one at a time: implement, verify with `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest`, then commit that task before starting the next (see branch/PR below for where those commits land). Don't batch unrelated tasks into one commit.
- **Branch + PR, not direct commits to `master`.** For code/feature/fix work, start from an up-to-date `master`, create a branch named `claude/<short-kebab-slug>` (e.g. `claude/fix-composer-gif-paste`), and do all of that request's commits there — one branch per request/feature, not per commit; a multi-phase request's several commits share the same branch. Once verification passes, open a PR with `gh pr create` (a real summary, not a placeholder) instead of pushing to `master` directly. This keeps `master` reviewable in PR-sized units and lets the release workflow's `generate_release_notes: true` step produce a real per-change changelog instead of a raw commit list. Leave the PR open for the user to merge — don't merge it yourself unless they explicitly ask to merge/ship it. Small doc/config housekeeping the user is directing turn-by-turn in the same conversation can still go straight to `master` if they ask for that in the moment; the default is branch+PR, not a rule with zero exceptions.
- **Commit safety:** keep commit subjects/bodies neutral, English-only, and free of any literal `@word` — not just mention-shaped text, but Kotlin/Java annotations and DI qualifiers too (`@Composable`, `@Inject`, `@Named("tor")`, ...). GitHub's markdown auto-links any `@word` on sight, so an annotation reference notifies a real account exactly like a mention would — this is the most common way the rule gets missed, since writing `@Composable` in a sentence about Compose code doesn't *feel* like mentioning someone. Fix: drop the `@` (`OptIn`, `Composable`), quote the token, or spell it out ("the `Named(\"tor\")` qualifier") — unless a real GitHub mention is genuinely intended. Before every `git commit`, scan the drafted message for `@` characters specifically; "does this look like a person's name" isn't a sufficient filter, since annotation references don't. Recovery, if a violation ships to a solo-authored unmerged branch anyway: tag the current tip as a backup, `git reset --hard` to the last clean commit, then `git cherry-pick <sha> --no-commit` + a corrected `git commit` per offending commit in order (never `git rebase -i`), confirm `git diff <backup-tag> HEAD` is empty, `git push --force-with-lease` (never plain `--force`), delete the backup tag.
- **Commit attribution:** every commit Claude Code creates — on a feature branch or, per the exception above, directly on `master` — must end its message with a `Co-Authored-By: <model name> <noreply@anthropic.com>` trailer (e.g. `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`), naming whichever Claude model actually did the work. This isn't optional per-commit; apply it every time, without being asked.
- **Comments and commits must stand on their own, independent of `.planning/` or git history.** GSD's `.planning/` phase/plan docs and git history are both workflow scaffolding, not permanent fixtures — either can be deleted, squashed, or rewritten. Anything that lives permanently in the tracked source tree (code comments) or in a commit message must therefore be understandable without either one:
  - **Source code comments** must never reference a GSD phase/plan/task identifier (e.g. "Plan 03-05 Task 2", "D-01", "per 03-RESEARCH.md Pitfall 3") or a specific commit hash / "see `git show <sha>`" for context. State the actual constraint, invariant, or behavior being preserved directly in the comment, not by pointing at an external planning doc or another commit's diff.
  - **Commit messages:** the required `{type}(phase-plan): ...` subject scope tag is fine — it's GSD's own traceability convention for `git log --grep`, not a comment referencing external context. But the body/description must still explain what changed and why in terms understandable without opening the plan or diffing another commit — avoid "matches Task 2's spec" or "see commit `<sha>` for the original extraction"; restate the actual reasoning inline instead.
  - This is the general instruction (see "Doing tasks" above) not to reference the current task/fix/callers in comments, made explicit for GSD phase-execution work, where following a detailed PLAN.md makes it easy to drift into planning-doc-shaped comments without noticing.

## Bug tracking

Bugs and backlog items found or suggested mid-session — via code review, manual testing, or the user pointing one out — are logged across three files, distinct from GitHub Issues (CONTRIBUTING.md's "Reporting bugs" section is for external contributors formally filing an issue; these are Claude Code's own running lists for items that aren't necessarily issues yet):

- **[docs/KNOWN_ISSUES.md](../docs/KNOWN_ISSUES.md)** — open bugs not yet fixed.
- **[docs/TODO.md](../docs/TODO.md)** — the general project backlog: suggested/planned tasks, features, and refactors that are *not* bugs. NIP-specific sequencing stays in `docs/nip-priority-roadmap.md` and is cross-linked from TODO.md rather than duplicated.
- **[docs/DONE.md](../docs/DONE.md)** — append-only log of completed work, fed by both of the above once an item is finished.

Each entry gets a locally sequential ID (`LOG-1`, `LOG-2`, ...) — independent of and never matching a GitHub issue/PR number, and never reused once an entry moves to DONE.md — so the user can say "fix LOG-3" or "do LOG-7" and mean one exact, unambiguous item regardless of which file it's currently in or whether it was ever filed as a GitHub issue. The `LOG-` prefix (rather than a bare `#<n>`) is deliberate: a plain `#14` in a doc or commit message is indistinguishable from a GitHub issue/PR reference and GitHub auto-links it as one, which is wrong here. This is a **single global counter shared across all three files**: before assigning a new ID, check the highest number already used across all three, and increment — there's no separately-maintained per-file counter to fall out of sync.

### Bugs (docs/KNOWN_ISSUES.md → docs/DONE.md)

- **`docs/KNOWN_ISSUES.md`** — one entry per open bug:
  ```
  ### LOG-<n> — <short title>
  - **Status:** open
  - **Found:** <YYYY-MM-DD>
  - **Where:** <file/screen/flow>

  <description — what's wrong, how to repro if known>
  ```
- When a fix is committed, update that entry's status in place to `fix applied — needs on-device validation` and add a `**Fix:**` line pointing at the commit/PR. Don't move it to DONE.md yet — an applied fix isn't confirmed working until it's actually been run.
- **Emulator/device validation stays opt-in** (see Workflow above) — Umbra doesn't run autonomous on-device test passes. A `fix applied` entry just sits in KNOWN_ISSUES.md until the user explicitly asks to validate it (e.g. via the `run-umbra` skill) or confirms it themselves.
- Once validated, move the entry verbatim from `docs/KNOWN_ISSUES.md` to `docs/DONE.md`, appending a `**Validated:** <YYYY-MM-DD>` line.

### Backlog (docs/TODO.md → docs/DONE.md)

- **`docs/TODO.md`** — one entry per backlog item:
  ```
  ### LOG-<n> — <short title>
  - **Status:** backlog | in progress | not applicable
  - **Added:** <YYYY-MM-DD>
  - **Why:** <1-2 line rationale — why this is worth doing / where it came from>

  <description — what the task/feature/refactor actually is>
  ```
- An item that gets triaged out is marked `not applicable` in place rather than deleted, so the reasoning stays on record.
- Once shipped, move the entry verbatim from `docs/TODO.md` to `docs/DONE.md`, appending a `**Completed:** <YYYY-MM-DD>` line and a `**From:** TODO LOG-<n>` back-reference. A backlog item doesn't need on-device validation the way a bug fix does (no `**Validated:**` line), though it can still get one if it was UI-facing and the user confirms it on-device.

### General

- `docs/DONE.md` is an append-only historical record — don't edit past entries beyond adding the one date line each transition calls for.
- Keep all three files updated as a normal part of the work itself — log an item the moment it's found/suggested, update its status the moment a fix or a piece of work lands — not just when the user separately asks for it.

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
- Signing flows exclusively through `AmberSignerGateway` (domain) → `AmberConnector` (data/amber) → Amber via Android intents. `canSignWithAmber()` gates every write action.
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

Implementation status per NIP: `README.md` (quick view) and [docs/nip-social-coverage.md](../docs/nip-social-coverage.md) (detailed). Sequencing/priority for unimplemented NIPs: [docs/nip-priority-roadmap.md](../docs/nip-priority-roadmap.md). Any new NIP work must preserve the TOR-only and Amber-only constraints above — they are not negotiable per-feature.

## Reference client: Amethyst

[Amethyst](https://github.com/vitorpamplona/amethyst) is the most feature-complete Nostr client on Android and is known for staying fluid under heavy feed/list load. It's also Kotlin/Compose, so it's a directly comparable reference point — not something to port wholesale, since Umbra's threat model (TOR-only, Amber-only signing, no on-device keys) is stricter than Amethyst's and must never be relaxed to match it.

Use it as a comparison point for:
- **NIP scope/breadth** — when deciding whether a NIP is worth prioritizing or how a rarer one is typically modeled as events/tags.
- **Feed and list performance** — LazyColumn item stability, recomposition avoidance, and caching strategy for a high-churn, high-volume event stream, which is the same core performance problem Umbra's feed has.
- **Event/profile caching patterns** — Umbra's non-owned-event cache is already modeled directly on Amethyst's approach (pure in-memory, no general-purpose event database, on-demand relay fetch for cache misses) rather than just compared against it — see `data/repository/EventLruCache.kt` and `EventRepository.fetchEventById()`.

Nothing about Amethyst overrides `AUDIT.md`; if a pattern conflicts with the TOR-only or Amber-only rules, the rule wins.
