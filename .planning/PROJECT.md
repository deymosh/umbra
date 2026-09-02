# Umbra — v0.1.0 Hardening & First Public Release

## What This Is

Umbra is a privacy-first, censorship-resistant Nostr client for Android: all network traffic routes through TOR via Orbot's SOCKS5 proxy with no exceptions, all content moderation is user-owned, and signing is exclusively via Amber (no `nsec` ever touches the device). The codebase is mature — Clean Architecture (UI → domain → data), Compose, Hilt, Room+SQLCipher, broad NIP coverage — but has never shipped a public release. This milestone is not new features: it's clearing the accumulated bug backlog and cutting the first public release, v0.1.0.

## Core Value

A trustworthy, stable first public release that upholds Umbra's TOR-only and Amber-only guarantees without regressions — a first release undermined by known concurrency races, silent exception swallowing, or an inconsistent version number damages trust before a single user has even opened the app.

## Requirements

### Validated

- ✓ TOR-only networking enforced across every network path via a single `@Named("tor") OkHttpClient` — existing
- ✓ Amber-only signing gateway (`AmberSignerGateway`/`AmberConnector`), no local key material — existing
- ✓ User-owned content moderation (mute lists, NSFW hiding, `FeedFilter` hashtag/tag/prefix exclusions, all editable/removable) — existing
- ✓ Core Nostr experience: feed, profile, thread view, relay browser/config, composer, DMs (backend), search — existing
- ✓ Broad NIP/BUD coverage (NIP-01/05/11/17/18/25/30/42/44/45/50/51/55/65, Blossom BUD-01..12, more per `docs/nip-social-coverage.md`) — existing
- ✓ Encrypted SQLCipher Room persistence scoped to the signed-in user's own events only; everyone else's content lives in an in-memory `EventLruCache` — existing
- ✓ CI pipeline (`android-ci.yml`): lint, unit tests, `assembleBenchmark` (R8-shaped) on every push/PR — existing
- ✓ Tag-triggered signed release workflow scaffold (`android-release.yml`) — existing but never exercised end-to-end
- ✓ Local bug-tracking convention already established (`docs/KNOWN_ISSUES.md`/`TODO.md`/`DONE.md`, shared `LOG-N` counter, currently at LOG-31) — existing

### Active

- [ ] Fix all 13 currently-`open` bugs in `docs/KNOWN_ISSUES.md`: LOG-18, 19, 20, 21, 22, 23, 24, 26, 27, 28, 29, 30, 31
- [ ] Fix LOG-17 (`docs/TODO.md`): promote 8 sites that swallow throwables at debug level to scrubbed error-level logging
- [ ] Review the 10 `fix applied — needs on-device validation` entries (LOG-1, 2, 3, 4, 6, 7, 11, 12, 13, 14): for each, determine whether it's verifiable by automated test alone or genuinely needs visual/on-device confirmation. Add/confirm tests and move to `DONE.md` for the testable ones; leave the rest in `KNOWN_ISSUES.md` for the user's own on-device validation later
- [ ] Fix `versionName` drift: `app/build.gradle.kts`'s `versionName = "0.1.0"` is the source of truth; `strings.xml`'s hardcoded `settings_version_value` ("0.1.0-beta") disagrees. Enable `buildConfig` and read `BuildConfig.VERSION_NAME` in `SettingsScreen.kt` instead of a second hand-maintained string
- [ ] Update `CHANGELOG.md`: convert the `[Unreleased]` section into a dated `[0.1.0]` section (Keep a Changelog format, already followed in this file)
- [ ] Prepare the v0.1.0 release: local git tag created and everything else ready, but the actual `git push` of the tag (which triggers `android-release.yml`'s signed build + GitHub Release) requires the user's explicit go-ahead
- [ ] Author a new project skill (`.claude/skills/umbra-release/`) documenting Umbra's release process, matching the existing `umbra-*` skill convention, so future releases follow the same steps

### Out of Scope

- New features or new NIP implementations — this milestone is stability + release, not feature work (see `docs/nip-priority-roadmap.md` for future feature sequencing)
- Autonomous on-device/emulator validation — stays opt-in per `.claude/CLAUDE.md`; genuinely visual fixes are left for the user to validate via the `run-umbra` skill on their own schedule
- Actually pushing the v0.1.0 git tag / letting CI run — prepared and staged, but not executed without explicit user confirmation, since it's an irreversible, publicly-visible action
- Play Store / F-Droid listing — v0.1.0 ships via GitHub Releases only (`android-release.yml`'s existing distribution channel)

## Context

- Single-module Android app (`:app`), package `com.umbra.app`. Kotlin 2.4.10, Compose, Hilt, Room 2.8.4+SQLCipher, Media3, Coil 3, AGP 9.3+/Gradle 9.x/JDK 17, compileSdk 37, minSdk 26.
- `AUDIT.md` is the master rulebook (security/architecture/Room/performance/UI) and takes precedence over everything else; `.claude/CLAUDE.md` covers workflow.
- Bug tracking: `docs/KNOWN_ISSUES.md` (open), `docs/TODO.md` (backlog), `docs/DONE.md` (append-only completed log) — one shared `LOG-N` counter, independent of GitHub issue numbers.
- `.github/workflows/android-release.yml` already exists: triggers on `v*` tag push or manual dispatch, builds `assembleRelease`, signs via `r0adkll/sign-android-release` using repo secrets (`SIGNING_KEY`/`KEY_ALIAS`/`KEY_STORE_PASSWORD`/`KEY_PASSWORD`), creates a GitHub Release with `generate_release_notes: true`. Never actually run yet.
- Repo has a GitHub remote (`origin` → `github.com/deymosh/umbra`).
- Branch/PR workflow per `.claude/CLAUDE.md`: work happens on `claude/<slug>` branches with PRs against `master`, not direct commits (except turn-by-turn doc/config housekeeping the user explicitly directs).
- `.planning/config.json` already existed pre-seeded in the initial commit (not created by this run) — `mode: yolo`, `granularity: coarse`, workflow agents (research/plan_check/verifier) all on, `code_review: standard`, `plan_review.source_grounding: true`. Reused as-is for this project.

## Constraints

- **Security**: TOR-only networking, Amber-only signing, no `nsec` on-device — non-negotiable, enforced by `AUDIT.md`/`.claude/CLAUDE.md`; no bug fix or release change may weaken these.
- **Moderation**: any content-hiding mechanism must remain a user-editable `FeedFilter`-style default, never a hardcoded app-side rule.
- **Toolchain**: `jvmTarget`/`compileSdk` must not be downgraded to route around a build issue.
- **Process**: git tag push (the release trigger) is an irreversible, publicly-visible action — always requires explicit user confirmation before executing, per the answer captured during questioning.
- **Commit hygiene**: commit messages must stay free of literal `@word` tokens (annotations auto-link as GitHub mentions) and must carry a `Co-Authored-By` trailer naming the acting Claude model.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Fix all 13 open bugs plus LOG-17 in this milestone, not deferred | User explicitly wants full bug-debt cleanup before the first public release | — Pending |
| Validate "fix applied" bugs via automated tests where possible, rather than requiring on-device confirmation for all 10 | Keeps emulator/device testing opt-in per `.claude/CLAUDE.md` while still closing out the entries that don't actually need a human eyeball | — Pending |
| Fix `versionName` drift by wiring `BuildConfig.VERSION_NAME` instead of hand-syncing a second string in `strings.xml` | Single source of truth in `build.gradle.kts` prevents this exact drift from recurring | — Pending |
| Author a new project skill for Umbra's release process | User asked to adopt/learn a release skill; best captured as a reusable project skill under `.claude/skills/`, consistent with the existing `umbra-*` skill catalog | — Pending |
| Prepare the v0.1.0 tag locally but require explicit confirmation before pushing it | Pushing triggers a real signed GitHub Release via CI — irreversible and publicly visible | — Pending |
| Reuse the pre-existing `.planning/config.json` instead of re-running Step 5's preference questions | It was already committed with sensible settings in the initial commit; re-asking would just duplicate existing intent | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-09-02 after initialization*
