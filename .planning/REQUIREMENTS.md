# Requirements: Umbra v0.1.0 Hardening & First Public Release

**Defined:** 2026-09-02
**Core Value:** A trustworthy, stable first public release that upholds Umbra's TOR-only and Amber-only guarantees without regressions

## v1 Requirements

### Bug Fixes (currently `open` in docs/KNOWN_ISSUES.md, plus one TODO.md backlog item)

- [x] **BUG-01**: Promote LOG-17's 8 swallowed-exception debug-level log sites (`PublishEventUseCases.kt`, `LoginViewModel.kt`, `UmbraNostrClient.kt`, `RelayMessageHandling.kt`, `RelayWebSocketListener.kt`) to scrubbed error-level logging with the throwable attached — all 8 sites done (2 in Plan 01-01, 6 in Plan 01-02)
- [x] **BUG-02**: Scrub LOG-18's 3 unscrubbed log sites (`EventRepositoryImpl.kt` relay-URL interpolations x2, `NegentropySyncOrchestrator.kt` throwable message x1) via `LogScrubber`
- [x] **BUG-03**: Fix LOG-19 — NIP-09 `"a"`-tag deletions never take effect for a non-owned author's cached addressable event (`EventIngestCache.applyIncomingDeletion` needs an in-memory lookup alongside `ownEventArchive`)
- [x] **BUG-04**: Fix LOG-20 — log the exception currently swallowed by `clearAllData()`'s `disconnectFromAll()` catch block
- [x] **BUG-05**: Fix LOG-21 — replace `EventIngestCache.snapshotEmitJob`'s unsynchronized `var` with `AtomicReference<Job?>`, matching `insertDebounceJob`'s existing pattern
- [x] **BUG-06**: Fix LOG-22 — give `ProfileViewModel.deleteEvent` the same pending-action-plus-rollback treatment as `toggleMute`/`togglePin`/`toggleFollow`
- [x] **BUG-07**: Fix LOG-23 — `FeedViewModel.muteUser`'s local-filter mute mirror resolves the active filter the same way `ProfileViewModel.toggleMute` does, instead of a lookup that can never match
- [x] **BUG-08**: Fix LOG-24 — `FeedViewModel.muteUser`/`togglePin` check the mute/pin write's `Result` and surface failure, instead of discarding it
- [x] **BUG-09**: Fix LOG-26 — apply LOG-25's already-shipped logout exception-logging fix to `SettingsScreen.kt`'s independent logout entry point
- [x] **BUG-10**: Fix LOG-27 — log each per-step cleanup exception (scrubbed) in `LogoutUseCase` and `TrimMemoryCachesUseCase` instead of silently swallowing it
- [x] **BUG-11**: Fix LOG-28 — stop swallowing `activateUserSession`'s exception inside `LoginViewModel`'s inner catch so the outer, already-working logging path can see it
- [x] **BUG-12**: Fix LOG-29 — guard `RelayCrudCoordinator.updateRelayRole` with a per-relay lock so concurrent role toggles on the same relay can't silently lose an update
- [x] **BUG-13**: Fix LOG-30 — apply `AtomicReference<Job?>` to `NostrSessionManager.retryJob` (and audit the sibling `Job?` fields for the same treatment)
- [x] **BUG-14**: Fix LOG-31 — only mark the DM relay list dirty in `RelayCrudCoordinator.setDmEnabled` when the mapper actually produces a changed relay

### Fix Validation (currently `fix applied — needs on-device validation` in docs/KNOWN_ISSUES.md)

For each: determine whether the fix is verifiable by automated test alone, or genuinely needs visual/on-device confirmation. Add/confirm test coverage and move to `docs/DONE.md` for the former; leave as-is in `docs/KNOWN_ISSUES.md` for the user's own on-device validation for the latter. IDs VALID-11 onward cover the Phase 1/2 fixes that were never moved to `docs/DONE.md`; LOG-35 (still `open`, no fix landed) and LOG-44 (deferred in `docs/TODO.md`, needs an architectural change rather than an audit-and-cite pass) are deliberately excluded and have no id.

- [ ] **VALID-01**: Validate LOG-1 — stale kind-0/replaceable-event revisions lingering in `EventLruCache`
- [x] **VALID-02**: Validate LOG-2 — `ImageLoadGate` permit acquire/release race
- [x] **VALID-03**: Validate LOG-3 — inline video player aspect-ratio mismatch
- [x] **VALID-04**: Validate LOG-4 — relay list TOCTOU race on save/apply
- [x] **VALID-05**: Validate LOG-6 — stale replaceable-event revisions in the encrypted Room DB
- [ ] **VALID-06**: Validate LOG-7 — future-dated events filter / recheck ticker
- [ ] **VALID-07**: Validate LOG-11 — ticker's immediate-first-emission regression fix
- [x] **VALID-08**: Validate LOG-12 — same-relay concurrent dial race
- [x] **VALID-09**: Validate LOG-13 — avatar/banner retry-on-Tor-cold-start fix
- [x] **VALID-10**: Validate LOG-14 — promoting a discovered relay to an owned role
- [x] **VALID-11**: Validate LOG-18 — Three unscrubbed log messages survive the logging migration (EventRepositoryImpl.kt x2, NegentropySyncOrchestrator.kt x1)
- [ ] **VALID-12**: Validate LOG-19 — NIP-09 "a"-tag deletions never take effect for a non-owned author's cached addressable event
- [x] **VALID-13**: Validate LOG-20 — Silent empty catch block during `clearAllData()`'s wipe sequence
- [ ] **VALID-14**: Validate LOG-21 — `snapshotEmitJob` is a plain, unsynchronized field mutated from concurrent coroutines
- [ ] **VALID-15**: Validate LOG-22 — ProfileViewModel.deleteEvent removes a note from the visible list before Amber confirms the delete, with no rollback
- [ ] **VALID-16**: Validate LOG-23 — FeedViewModel.muteUser's local-filter mute mirror is dead code
- [ ] **VALID-17**: Validate LOG-24 — FeedViewModel.muteUser/togglePin discard the mute/pin write's success/failure result
- [x] **VALID-18**: Validate LOG-26 — SettingsScreen's logout flow has the same swallowed-exception bug FeedScreen's just had fixed
- [ ] **VALID-19**: Validate LOG-27 — LogoutUseCase's (and TrimMemoryCachesUseCase's) per-step cleanup catches discard every failure with zero logging
- [x] **VALID-20**: Validate LOG-28 — LoginViewModel's session-activation failures are swallowed with zero logging during both anonymous and Amber login
- [ ] **VALID-21**: Validate LOG-29 — RelayCrudCoordinator's per-role enable-flag setters can lose a concurrent update to the same relay
- [x] **VALID-22**: Validate LOG-30 — NostrSessionManager's retry-scheduling and job-bookkeeping fields are plain vars racing across concurrent IO-dispatcher coroutines
- [ ] **VALID-23**: Validate LOG-31 — RelayCrudCoordinator.setDmEnabled marks the DM relay list dirty even when the enable is rejected
- [ ] **VALID-24**: Validate LOG-34 — Logger.e() leaks the raw, unscrubbed Throwable via Android's own stack-trace formatting, bypassing LogScrubber entirely
- [x] **VALID-25**: Validate LOG-37 — RelayCrudCoordinator.removeRelayRole bypasses the per-relay Mutex LOG-29 added for its sibling setters
- [x] **VALID-26**: Validate LOG-38 — NostrSessionManager's plain instance fields are still unsynchronized across the two coroutines LOG-30's own fix comment says race each other
- [x] **VALID-27**: Validate LOG-39 — RelayConfigViewModel.enforceAnonymousRelayPolicyIfNeeded silently discards failures while enforcing the anonymous-session privacy restriction
- [ ] **VALID-28**: Validate LOG-40 — EventIngestCache.scheduleInsert's cancel-and-replace ordering lets the old and new debounce jobs run concurrently
- [ ] **VALID-29**: Validate LOG-41 — EventIngestCache.cacheRepostTarget skips the replaceable-event supersede bookkeeping ingest() enforces for the same slot
- [x] **VALID-30**: Validate LOG-42 — RelayCrudCoordinator.saveRelay/deleteRelay mutate a relay's persisted record without the per-relay Mutex updateRelayRole uses
- [ ] **VALID-31**: Validate LOG-46 — InteractionActionsCoordinator.mirrorMuteIntoActiveFilter used stdlib runCatching, swallowing CancellationException
- [ ] **VALID-32**: Validate LOG-47 — RelayCrudCoordinator.saveRelay's merge branch based its OR-merged write on a stale pre-lock snapshot
- [x] **VALID-33**: Validate LOG-49 — NostrSessionManager.maybeBootstrapOwnProfile's check-then-act guard wasn't atomic under reconcile()'s two concurrent entry points
- [x] **VALID-34**: Validate LOG-51 — NostrSessionManager's onFailure handlers logged a scrubbed message instead of the throwable
- [x] **VALID-35**: Validate LOG-52 — ownProfileBootstrapMutex only guarded maybeBootstrapOwnProfile's own body, not the other two call sites mutating the same fields
- [ ] **VALID-36**: Validate LOG-53 — RelayCrudCoordinator.saveRelay's new-relay/URL-collision decision still read the throttled state.value.relays mirror
- [x] **VALID-37**: Validate LOG-54 — InteractionActionsCoordinator still discarded the throwable in two logger.d catch/onFailure sites
- [x] **VALID-38**: Validate LOG-55 — no regression test exercised RelayCrudCoordinator.saveRelay's merge-branch fresh-read fix

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
| BUG-01 | Phase 1 | Complete |
| BUG-02 | Phase 1 | Complete |
| BUG-04 | Phase 1 | Complete |
| BUG-09 | Phase 1 | Complete |
| BUG-10 | Phase 1 | Complete |
| BUG-11 | Phase 1 | Complete |
| BUG-03 | Phase 2 | Complete |
| BUG-05 | Phase 2 | Complete |
| BUG-06 | Phase 2 | Complete |
| BUG-07 | Phase 2 | Complete |
| BUG-08 | Phase 2 | Complete |
| BUG-12 | Phase 2 | Complete |
| BUG-13 | Phase 2 | Complete |
| BUG-14 | Phase 2 | Complete |
| VALID-01 | Phase 3 | Pending |
| VALID-02 | Phase 3 | Complete |
| VALID-03 | Phase 3 | Complete |
| VALID-04 | Phase 3 | Complete |
| VALID-05 | Phase 3 | Complete |
| VALID-06 | Phase 3 | Pending |
| VALID-07 | Phase 3 | Pending |
| VALID-08 | Phase 3 | Complete |
| VALID-09 | Phase 3 | Complete |
| VALID-10 | Phase 3 | Complete |
| VALID-11 | Phase 3 | Complete |
| VALID-12 | Phase 3 | Pending |
| VALID-13 | Phase 3 | Complete |
| VALID-14 | Phase 3 | Pending |
| VALID-15 | Phase 3 | Pending |
| VALID-16 | Phase 3 | Pending |
| VALID-17 | Phase 3 | Pending |
| VALID-18 | Phase 3 | Complete |
| VALID-19 | Phase 3 | Pending |
| VALID-20 | Phase 3 | Complete |
| VALID-21 | Phase 3 | Pending |
| VALID-22 | Phase 3 | Complete |
| VALID-23 | Phase 3 | Pending |
| VALID-24 | Phase 3 | Pending |
| VALID-25 | Phase 3 | Complete |
| VALID-26 | Phase 3 | Complete |
| VALID-27 | Phase 3 | Complete |
| VALID-28 | Phase 3 | Pending |
| VALID-29 | Phase 3 | Pending |
| VALID-30 | Phase 3 | Complete |
| VALID-31 | Phase 3 | Pending |
| VALID-32 | Phase 3 | Pending |
| VALID-33 | Phase 3 | Complete |
| VALID-34 | Phase 3 | Complete |
| VALID-35 | Phase 3 | Complete |
| VALID-36 | Phase 3 | Pending |
| VALID-37 | Phase 3 | Complete |
| VALID-38 | Phase 3 | Complete |
| VERS-01 | Phase 4 | Pending |
| VERS-02 | Phase 4 | Pending |
| REL-01 | Phase 4 | Pending |
| REL-02 | Phase 4 | Pending |
| REL-03 | Phase 4 | Pending |
| SKILL-01 | Phase 4 | Pending |

**Coverage:**

- v1 requirements: 58 total
- Mapped to phases: 58 ✓
- Unmapped: 0

**Per-phase counts:** Phase 1 = 6 · Phase 2 = 8 · Phase 3 = 38 · Phase 4 = 6

---
*Requirements defined: 2026-09-02*
*Last updated: 2026-09-05 after Phase 3 Plan 03-01 minted VALID-11..VALID-38 for the D-08 scope expansion*
