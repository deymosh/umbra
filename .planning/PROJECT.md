# Umbra — v0.1.0 Hardening & First Public Release

## What This Is

Umbra is a privacy-first, censorship-resistant Nostr client for Android: all network traffic routes through TOR via Orbot's SOCKS5 proxy with no exceptions, all content moderation is user-owned, and signing is exclusively via Amber (no `nsec` ever touches the device). The codebase is mature — Clean Architecture (UI → domain → data), Compose, Hilt, Room+SQLCipher, broad NIP coverage — and has now completed its v0.1.0 hardening milestone: the accumulated bug backlog is cleared, the app reports one true version, and the release is staged (checklist run, runbook written) right up to the tag push. It still has never shipped a public release — that last, irreversible step (creating and pushing the `v0.1.0` tag) is deliberately left to the user's own separate, explicit action.

## Core Value

A trustworthy, stable first public release that upholds Umbra's TOR-only and Amber-only guarantees without regressions — a first release undermined by known concurrency races, silent exception swallowing, or an inconsistent version number damages trust before a single user has even opened the app. Still the right priority: shipping this milestone didn't surface a different core value, it validated this one — every phase's own gate (compile/lint/full test suite, plus a real R8-shaped release build in Phase 4) held throughout, and none of Umbra's non-negotiable constraints (TOR-only, Amber-only, no on-device `nsec`) needed to move to get here.

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
- ✓ Local bug-tracking convention already established (`docs/KNOWN_ISSUES.md`/`TODO.md`/`DONE.md`, shared `LOG-N` counter, currently at LOG-55) — existing
- ✓ Every failure path in publish/login/logout/cleanup/relay-transport code logs its throwable at a visible, scrubbed level instead of vanishing silently, and `Logger.e()`'s scrubbing is now airtight end-to-end (message string *and* the `Throwable` object itself, closing a leak in the wrapper predating this milestone) — Phase 1
- ✓ Concurrent job/relay-role mutations are atomic and optimistic UI tells the truth: `EventIngestCache.snapshotEmitJob`/`NostrSessionManager`'s racy job fields are CAS-scheduled, `RelayCrudCoordinator.updateRelayRole` closes the per-relay lost-update race, NIP-09 "a"-tag deletions resolve against the in-memory cache, `deleteEvent` commits only after Amber confirms, and `FeedViewModel.muteUser`/`togglePin` surface write failures instead of discarding them — Phase 2 (LOG-19/21/22/23/24/29/30/31, BUG-03/05/06/07/08/12/13/14)
- ✓ All 38 `fix applied` bug-tracker entries carry a recorded, independently re-checkable determination — no entry left in ambiguous limbo: 22 closed to `docs/DONE.md` on an executed, revert-tested unit test citation, 6 closed on a direct source-read citation (fixes behind a non-injected logger, structurally unassertable by any unit test), and 10 stay in `docs/KNOWN_ISSUES.md` self-annotated with a `**Validation:**` bullet naming their specific blocker (5 awaiting the user's own device pass, 5 blocked by `NostrSessionManager`'s architectural seam gap) — Phase 3 (VALID-01..38)
- ✓ `app/build.gradle.kts`'s `versionName` is the single source of truth for the version the app displays — `BuildConfig.VERSION_NAME` wired into `SettingsScreen.kt`, the drifted `settings_version_value` string resource retired — v0.1.0 (VERS-01, VERS-02)
- ✓ `CHANGELOG.md` names a dated `[0.1.0]` release (Keep a Changelog format) carrying a reader-facing summary of this milestone's own hardening work, and `docs/RELEASE_CHECKLIST.md` records this session's actual observed results for every release-readiness gate (lint, 930 unit tests, R8-shaped `assembleRelease`, CI signing secrets confirmed present, remote tag unclaimed) — v0.1.0 (REL-01, REL-02)
- ✓ `.claude/skills/umbra-release/SKILL.md` — a numbered, reusable release runbook with a structurally-enforced stop-and-confirm gate before the tag push, matching the existing `umbra-*` skill catalog — v0.1.0 (SKILL-01)

### Active

- [ ] LOG-44: `NostrSessionManager`/`RelayConfigViewModel` still have no dedicated unit test for the concurrency behavior Phase 2 changed — deliberately deferred (no mocking framework on the test classpath, 3 concrete-class dependencies with no interface seam; a real architectural change, not a targeted-fix-pass task). Phase 3 confirmed this blocker is still real (re-verified the constructor dependencies from source) and it now also blocks LOG-4/30/38/49/52 from getting their own tests.
- [ ] LOG-56: `FeedStateMergeCoordinatorTest`'s real-dispatcher bridge helper uses a fixed 300ms delay rather than a deterministic wait — CI-flakiness risk under load, found during Phase 3's code review
- [ ] LOG-57: `UmbraNostrClientTest`'s `LatchBlockingWebSocket` background thread isn't released via try/finally on an assertion failure — up to 5s of hung teardown, found during Phase 3's code review
- [ ] Push the `v0.1.0` git tag (create it locally, then `git push origin v0.1.0` per `.claude/skills/umbra-release/SKILL.md`'s stop-and-confirm gate) — the one action v0.1.0 deliberately does not include yet; the user creates and pushes it themselves, on their own schedule, as a standalone action outside any milestone workflow

### Out of Scope

- New features or new NIP implementations — this milestone is stability + release, not feature work (see `docs/nip-priority-roadmap.md` for future feature sequencing)
- Autonomous on-device/emulator validation — stays opt-in per `.claude/CLAUDE.md`; genuinely visual fixes are left for the user to validate via the `run-umbra` skill on their own schedule
- Creating or pushing the v0.1.0 git tag as part of any GSD workflow (phase execution or milestone completion) — reasoning strengthened, not just held, during this milestone: the user explicitly declined the tag twice (once mid-Phase-4, once again when offered as part of milestone close) and wants full manual control over the timing of that one irreversible, publicly-visible step
- Play Store / F-Droid listing — v0.1.0 ships via GitHub Releases only (`android-release.yml`'s existing distribution channel)

## Context

- Single-module Android app (`:app`), package `com.umbra.app`. Kotlin 2.4.10, Compose, Hilt, Room 2.8.4+SQLCipher, Media3, Coil 3, AGP 9.3+/Gradle 9.x/JDK 17, compileSdk 37, minSdk 26.
- `AUDIT.md` is the master rulebook (security/architecture/Room/performance/UI) and takes precedence over everything else; `.claude/CLAUDE.md` covers workflow.
- Bug tracking: `docs/KNOWN_ISSUES.md` (open), `docs/TODO.md` (backlog), `docs/DONE.md` (append-only completed log) — one shared `LOG-N` counter, independent of GitHub issue numbers.
- `.github/workflows/android-release.yml` already exists: triggers on `v*` tag push or manual dispatch, builds `assembleRelease`, signs via `r0adkll/sign-android-release` using repo secrets (`SIGNING_KEY`/`KEY_ALIAS`/`KEY_STORE_PASSWORD`/`KEY_PASSWORD`), creates a GitHub Release with `generate_release_notes: true`. Never actually run yet — `docs/RELEASE_CHECKLIST.md` (new in v0.1.0) confirms all four signing secrets are present on the repo and the workflow's inputs match its actual YAML, but the tag push itself hasn't happened.
- v0.1.0 release-readiness, as actually observed during Phase 4: `./gradlew lintDebug` and `./gradlew testDebugUnitTest` (930 tests, 0 failures) both green; `./gradlew assembleRelease` succeeds unsigned locally (the `release` build type has no local signing config by design — signing is CI-only) producing an R8-minified, resource-shrunk APK. No `v0.1.0` tag exists yet, locally or on the remote — intentionally, per the user's own decision to keep tag creation a separate action.
- `.claude/skills/umbra-release/SKILL.md` (new in v0.1.0) is the canonical, reusable runbook for cutting any future release — version bump, changelog move, verification, checklist, tag, the stop-and-confirm gate, the scoped push, and workflow_dispatch-based recovery. Read it before doing any of that manually.
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
| Fix all 13 open bugs plus LOG-17 in this milestone, not deferred | User explicitly wants full bug-debt cleanup before the first public release | ✓ Shipped Phases 1-2 |
| Validate "fix applied" bugs via automated tests where possible, rather than requiring on-device confirmation for all 10 | Keeps emulator/device testing opt-in per `.claude/CLAUDE.md` while still closing out the entries that don't actually need a human eyeball | ✓ Shipped Phase 3 |
| Expanded Phase 3's audit scope from the ROADMAP-stated 10 entries to all 38 `fix applied` entries in `docs/KNOWN_ISSUES.md` | The other 28 were Phase 1/2's own fixed bugs that were never moved to `docs/DONE.md` — same audit methodology, just applied to the full backlog instead of a subset, closing the gap in one pass rather than deferring it to a later phase | ✓ Shipped Phase 3 |
| Treat "verified by direct source read, cited by file:line" as a third valid closing disposition alongside test-evidence and needs-eyeball | 6 entries are fixes behind a non-constructor-injected logger — structurally unassertable by any unit test, but not genuinely "needs a running app" either; forcing them into one of the two existing buckets would misrepresent either the evidence or the blocker | ✓ Shipped Phase 3 |
| LOG-30/38/49/52 (all inside `NostrSessionManager`) stay in `docs/KNOWN_ISSUES.md` with one shared blocker note, not moved to `docs/TODO.md` | They are real, deployed, working fixes — the fix itself has nothing wrong with it, only no way to unit-test it yet until LOG-44's architectural seam work lands; `docs/TODO.md` is for backlog work, not shipped fixes awaiting a test seam | ✓ Shipped Phase 3 |
| Fix `versionName` drift by wiring `BuildConfig.VERSION_NAME` instead of hand-syncing a second string in `strings.xml` | Single source of truth in `build.gradle.kts` prevents this exact drift from recurring | ✓ Shipped Phase 4 |
| Author a new project skill for Umbra's release process | User asked to adopt/learn a release skill; best captured as a reusable project skill under `.claude/skills/`, consistent with the existing `umbra-*` skill catalog | ✓ Shipped Phase 4 |
| Prepare the v0.1.0 tag locally but require explicit confirmation before pushing it | Pushing triggers a real signed GitHub Release via CI — irreversible and publicly visible | ⚠️ Revisit — superseded mid-Phase-4: when asked for that exact confirmation, the user went further and declined local tag creation entirely for this milestone, not just the push. See the next row. |
| Descope local `v0.1.0` tag creation from Phase 4 and from milestone completion entirely, not just gate its push | Standing project policy: tag/release actions always need per-moment authorization, not blanket plan/milestone approval. Asked twice (once mid-Phase-4, once again at milestone close) and declined both times — the user wants to trigger tag creation themselves, on their own schedule, as a fully separate action | ✓ Confirmed twice — v0.1.0 ships with no local or remote tag; `REL-03` intentionally left unchecked in the archived requirements |
| Reuse the pre-existing `.planning/config.json` instead of re-running Step 5's preference questions | It was already committed with sensible settings in the initial commit; re-asking would just duplicate existing intent | ✓ Shipped — used unchanged across all 4 phases |
| Fixed `Logger.e()`'s throwable-scrub gap (LOG-34) inside Phase 1 rather than deferring it | Phase 1's code review found `Logger.e()` passed the raw `Throwable` to `Log.e()`, whose own stack-trace formatting bypasses `LogScrubber` — since Phase 1's entire purpose was promoting ~24 call sites from debug (filtered in release) to error (always printed), leaving this unfixed would have shipped a wider version of exactly the leak the phase was meant to close | ✓ Shipped Phase 1 |
| Moved `LogScrubber.kt` from `util/` into `util/logging/` alongside `Logger`/`UmbraLog` | User request while fixing LOG-34 — it exists purely to serve the logging wrapper, so it belongs in the same package | ✓ Shipped Phase 1 |
| Ran a 3-iteration code-review/auto-fix chain on Phase 2 rather than deferring findings to backlog | User explicitly asked to fix code-review findings within the same phase; each iteration surfaced genuinely new, narrowing-severity gaps (3 critical → 0 critical/4 warning → 0 critical/2 warning), so continuing to the auto-fix loop's 3-pass cap was worth it rather than stopping at "good enough" | ✓ Shipped Phase 2 |
| Left LOG-44 (missing `NostrSessionManagerTest`/`RelayConfigViewModelTest`) open rather than force-fixing it | Both classes have concrete-class dependencies with no interface seam and this project has no mocking framework — closing the gap would require an architectural change (new seams or a new test dependency) out of scope for a targeted code-review fix pass | — Deferred, carried into next milestone's backlog |

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
*Last updated: 2026-09-06 after v0.1.0 milestone*
