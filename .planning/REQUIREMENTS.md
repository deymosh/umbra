# Requirements: Umbra v0.1.0 Hardening & First Public Release

**Defined:** 2026-09-02
**Core Value:** A trustworthy, stable first public release that upholds Umbra's TOR-only and Amber-only guarantees without regressions

## v1 Requirements

### Bug Fixes (currently `open` in docs/KNOWN_ISSUES.md, plus one TODO.md backlog item)

- [ ] **BUG-01**: Promote LOG-17's 8 swallowed-exception debug-level log sites (`PublishEventUseCases.kt`, `LoginViewModel.kt`, `UmbraNostrClient.kt`, `RelayMessageHandling.kt`, `RelayWebSocketListener.kt`) to scrubbed error-level logging with the throwable attached
- [ ] **BUG-02**: Scrub LOG-18's 3 unscrubbed log sites (`EventRepositoryImpl.kt` relay-URL interpolations x2, `NegentropySyncOrchestrator.kt` throwable message x1) via `LogScrubber`
- [ ] **BUG-03**: Fix LOG-19 — NIP-09 `"a"`-tag deletions never take effect for a non-owned author's cached addressable event (`EventIngestCache.applyIncomingDeletion` needs an in-memory lookup alongside `ownEventArchive`)
- [ ] **BUG-04**: Fix LOG-20 — log the exception currently swallowed by `clearAllData()`'s `disconnectFromAll()` catch block
- [ ] **BUG-05**: Fix LOG-21 — replace `EventIngestCache.snapshotEmitJob`'s unsynchronized `var` with `AtomicReference<Job?>`, matching `insertDebounceJob`'s existing pattern
- [ ] **BUG-06**: Fix LOG-22 — give `ProfileViewModel.deleteEvent` the same pending-action-plus-rollback treatment as `toggleMute`/`togglePin`/`toggleFollow`
- [ ] **BUG-07**: Fix LOG-23 — `FeedViewModel.muteUser`'s local-filter mute mirror resolves the active filter the same way `ProfileViewModel.toggleMute` does, instead of a lookup that can never match
- [ ] **BUG-08**: Fix LOG-24 — `FeedViewModel.muteUser`/`togglePin` check the mute/pin write's `Result` and surface failure, instead of discarding it
- [ ] **BUG-09**: Fix LOG-26 — apply LOG-25's already-shipped logout exception-logging fix to `SettingsScreen.kt`'s independent logout entry point
- [ ] **BUG-10**: Fix LOG-27 — log each per-step cleanup exception (scrubbed) in `LogoutUseCase` and `TrimMemoryCachesUseCase` instead of silently swallowing it
- [ ] **BUG-11**: Fix LOG-28 — stop swallowing `activateUserSession`'s exception inside `LoginViewModel`'s inner catch so the outer, already-working logging path can see it
- [ ] **BUG-12**: Fix LOG-29 — guard `RelayCrudCoordinator.updateRelayRole` with a per-relay lock so concurrent role toggles on the same relay can't silently lose an update
- [ ] **BUG-13**: Fix LOG-30 — apply `AtomicReference<Job?>` to `NostrSessionManager.retryJob` (and audit the sibling `Job?` fields for the same treatment)
- [ ] **BUG-14**: Fix LOG-31 — only mark the DM relay list dirty in `RelayCrudCoordinator.setDmEnabled` when the mapper actually produces a changed relay

### Fix Validation (currently `fix applied — needs on-device validation` in docs/KNOWN_ISSUES.md)

For each: determine whether the fix is verifiable by automated test alone, or genuinely needs visual/on-device confirmation. Add/confirm test coverage and move to `docs/DONE.md` for the former; leave as-is in `docs/KNOWN_ISSUES.md` for the user's own on-device validation for the latter.

- [ ] **VALID-01**: Validate LOG-1 — stale kind-0/replaceable-event revisions lingering in `EventLruCache`
- [ ] **VALID-02**: Validate LOG-2 — `ImageLoadGate` permit acquire/release race
- [ ] **VALID-03**: Validate LOG-3 — inline video player aspect-ratio mismatch
- [ ] **VALID-04**: Validate LOG-4 — relay list TOCTOU race on save/apply
- [ ] **VALID-05**: Validate LOG-6 — stale replaceable-event revisions in the encrypted Room DB
- [ ] **VALID-06**: Validate LOG-7 — future-dated events filter / recheck ticker
- [ ] **VALID-07**: Validate LOG-11 — ticker's immediate-first-emission regression fix
- [ ] **VALID-08**: Validate LOG-12 — same-relay concurrent dial race
- [ ] **VALID-09**: Validate LOG-13 — avatar/banner retry-on-Tor-cold-start fix
- [ ] **VALID-10**: Validate LOG-14 — promoting a discovered relay to an owned role

### Version Consistency

- [ ] **VERS-01**: Enable Gradle `buildConfig` and read `BuildConfig.VERSION_NAME` in `SettingsScreen.kt` instead of the hardcoded `strings.xml` value, making `app/build.gradle.kts`'s `versionName` the single source of truth
- [ ] **VERS-02**: Retire the now-redundant `settings_version_value` string resource ("0.1.0-beta", currently drifted from the real `0.1.0`) once `BuildConfig` wiring lands

### Release

- [ ] **REL-01**: Update `CHANGELOG.md` — convert the `[Unreleased]` section into a dated `[0.1.0]` section (Keep a Changelog format already in use)
- [ ] **REL-02**: Verify release readiness end-to-end — lint/unit tests/`assembleRelease` succeed, signing config present, `android-release.yml` inputs correct — before asking for the tag push
- [ ] **REL-03**: Prepare the `v0.1.0` git tag locally, ready for the user to push and trigger `android-release.yml`'s signed build + GitHub Release

### Release Skill

- [ ] **SKILL-01**: Author `.claude/skills/umbra-release/SKILL.md` documenting Umbra's release process (version bump, changelog, tag, CI signing, GitHub Release), matching the existing `umbra-*` skill catalog

## v2 Requirements

None — this milestone is deliberately scoped to stability + release, not new feature work.

## Out of Scope

| Feature | Reason |
|---------|--------|
| New features / new NIP implementations | This milestone is bug-debt cleanup + release, not feature work — see `docs/nip-priority-roadmap.md` for future sequencing |
| Autonomous on-device/emulator validation | Stays opt-in per `.claude/CLAUDE.md`; visually-dependent fixes are left for the user's own `run-umbra` validation pass |
| Actually pushing the `v0.1.0` tag / triggering CI | Prepared and staged only — an irreversible, publicly-visible action requires the user's explicit go-ahead |
| Play Store / F-Droid listing | v0.1.0 ships via GitHub Releases only, matching the existing `android-release.yml` distribution channel |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| BUG-01 | Phase 1 | Pending |
| BUG-02 | Phase 1 | Pending |
| BUG-04 | Phase 1 | Pending |
| BUG-09 | Phase 1 | Pending |
| BUG-10 | Phase 1 | Pending |
| BUG-11 | Phase 1 | Pending |
| BUG-03 | Phase 2 | Pending |
| BUG-05 | Phase 2 | Pending |
| BUG-06 | Phase 2 | Pending |
| BUG-07 | Phase 2 | Pending |
| BUG-08 | Phase 2 | Pending |
| BUG-12 | Phase 2 | Pending |
| BUG-13 | Phase 2 | Pending |
| BUG-14 | Phase 2 | Pending |
| VALID-01 | Phase 3 | Pending |
| VALID-02 | Phase 3 | Pending |
| VALID-03 | Phase 3 | Pending |
| VALID-04 | Phase 3 | Pending |
| VALID-05 | Phase 3 | Pending |
| VALID-06 | Phase 3 | Pending |
| VALID-07 | Phase 3 | Pending |
| VALID-08 | Phase 3 | Pending |
| VALID-09 | Phase 3 | Pending |
| VALID-10 | Phase 3 | Pending |
| VERS-01 | Phase 4 | Pending |
| VERS-02 | Phase 4 | Pending |
| REL-01 | Phase 4 | Pending |
| REL-02 | Phase 4 | Pending |
| REL-03 | Phase 4 | Pending |
| SKILL-01 | Phase 4 | Pending |

**Coverage:**
- v1 requirements: 30 total
- Mapped to phases: 30 ✓
- Unmapped: 0

**Per-phase counts:** Phase 1 = 6 · Phase 2 = 8 · Phase 3 = 10 · Phase 4 = 6

---
*Requirements defined: 2026-09-02*
*Last updated: 2026-09-02 after roadmap creation (traceability filled in)*
