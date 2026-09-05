# Roadmap: Umbra v0.1.0 Hardening & First Public Release

## Overview

Umbra's codebase is mature but has never shipped publicly. This milestone clears the accumulated bug debt and cuts v0.1.0. The journey runs in four horizontal layers rather than vertical feature slices, because this is hardening work on an existing app, not new capability: first make failures *visible* (promote and scrub every swallowed exception), then make concurrent and optimistic state *correct* (atomic job fields, per-relay locking, honest optimistic UI), then *close out* the ten already-fixed-but-unvalidated bugs by deciding per entry whether a test or a human eyeball is the real verifier, and finally make the app's version *consistent* and stage the v0.1.0 release right up to — but not through — the irreversible tag push.

Ordering is dependency-driven: error visibility lands first so the state fixes in Phase 2 fail loudly rather than silently; validation comes after both bug-fix phases because several of the ten "fix applied" entries sit in code those fixes touch; version/release work is last because it depends on a clean, tested tree.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Error Visibility & Log Hygiene** - Every swallowed throwable surfaces at a scrubbed, visible level (completed 2026-09-03)
- [x] **Phase 2: Concurrency & State Correctness** - Atomic job/relay mutations and optimistic UI that tells the truth (completed 2026-09-04)
- [ ] **Phase 3: Fix Validation & Test Coverage** - Ten pending fixes closed out by test evidence or explicitly handed to the user
- [ ] **Phase 4: Version Consistency & v0.1.0 Release Prep** - One true version, a dated changelog, and a release one push away

## Phase Details

### Phase 1: Error Visibility & Log Hygiene

**Goal**: Every failure path in Umbra's publish, login, logout, cleanup, and relay-transport code reports its throwable at a visible, scrubbed level instead of vanishing — so the remaining fixes in this milestone fail loudly rather than silently.
**Depends on**: Nothing (first phase)
**Requirements**: BUG-01, BUG-02, BUG-04, BUG-09, BUG-10, BUG-11
**Success Criteria** (what must be TRUE):

  1. The eight LOG-17 sites (`PublishEventUseCases.kt`, `LoginViewModel.kt`, `UmbraNostrClient.kt`, `RelayMessageHandling.kt`, `RelayWebSocketListener.kt`) log at error level with the throwable attached; no debug-level, throwable-dropping handler remains at any of them.
  2. The four previously-silent catches — `clearAllData()`'s `disconnectFromAll()`, `SettingsScreen.kt`'s logout, `LogoutUseCase`/`TrimMemoryCachesUseCase`'s per-step cleanup, and `LoginViewModel`'s inner `activateUserSession` catch — each emit a scrubbed log carrying the exception, with the login case reaching the outer handler that was already working.
  3. The three LOG-18 sites (`EventRepositoryImpl.kt` x2 relay-URL interpolation, `NegentropySyncOrchestrator.kt` x1 throwable message) route through `LogScrubber`; a release-build log of those paths contains no raw relay URL, pubkey, or unscrubbed exception text.
  4. `./gradlew compileDebugKotlin`, `lintDebug`, and `testDebugUnitTest` all pass with no new warnings (CI treats lint warnings as errors).
  5. `docs/KNOWN_ISSUES.md` entries LOG-18, LOG-20, LOG-26, LOG-27, LOG-28 read `fix applied — needs on-device validation` with a `**Fix:**` line, and `docs/TODO.md`'s LOG-17 has moved verbatim into `docs/DONE.md` with `**Completed:**` and `**From:** TODO LOG-17` lines.

**Plans**: 3/3 plans executed

Plans:
**Wave 1**

- [x] 01-01-PLAN.md — Recording-logger test double, constructor-injected logger on both cleanup use cases, and the two publish-failure sites (BUG-10, BUG-01 partial)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 01-02-PLAN.md — The six BUG-01 sites in self-constructing-logger classes, both scrubbing gaps, the silent wipe handler, and the swallowed session-activation failures (BUG-01, BUG-02, BUG-04, BUG-11)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 01-03-PLAN.md — Settings logout handler, phase-wide log-hygiene audit sweep and build gate, and the bug-tracker closeout (BUG-09 plus tracker state for all six)

### Phase 2: Concurrency & State Correctness

**Goal**: Concurrent job and relay-role mutations are atomic, cached content honours deletions, and every optimistic UI update either reflects what actually persisted or rolls itself back.
**Depends on**: Phase 1
**Requirements**: BUG-03, BUG-05, BUG-06, BUG-07, BUG-08, BUG-12, BUG-13, BUG-14
**Success Criteria** (what must be TRUE):

  1. Job-field races are closed: `EventIngestCache.snapshotEmitJob` and `NostrSessionManager.retryJob` (plus its audited sibling `Job?` fields) are `AtomicReference<Job?>` matching `insertDebounceJob`'s existing pattern, and two concurrent role toggles on the same relay through `RelayCrudCoordinator.updateRelayRole` both land instead of one being lost.
  2. A NIP-09 `"a"`-tag deletion for a non-owned author's cached addressable event actually removes it — `EventIngestCache.applyIncomingDeletion` consults the in-memory store alongside `ownEventArchive` — and `RelayCrudCoordinator.setDmEnabled` leaves the DM relay list clean when the mapper produces no changed relay.
  3. Optimistic UI tells the truth: `ProfileViewModel.deleteEvent` uses the same pending-action-plus-rollback shape as `toggleMute`/`togglePin`/`toggleFollow`, `FeedViewModel.muteUser`'s local-filter mirror resolves the active filter the way `ProfileViewModel.toggleMute` does (no longer a lookup that can never match), and `muteUser`/`togglePin` inspect the write's `Result` and surface failure rather than discarding it.
  4. New unit tests cover each fix that is unit-testable (job-field atomicity, per-relay serialization, deletion lookup, dirty-flag suppression, Result handling); `compileDebugKotlin`, `lintDebug`, and `testDebugUnitTest` all pass.
  5. `docs/KNOWN_ISSUES.md` entries LOG-19, LOG-21, LOG-22, LOG-23, LOG-24, LOG-29, LOG-30, LOG-31 read `fix applied — needs on-device validation` with a `**Fix:**` line.

**Plans**: 5/5 plans executed

Plans:
**Wave 1**

- [x] 02-01-PLAN.md — DM dirty flag scoped to the mapper, per-relay-id lock plus fresh point read in RelayCrudCoordinator, and the reusable concurrent-test harness (BUG-12, BUG-14)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 02-02-PLAN.md — Atomic snapshot-emit scheduling and two-source resolution for NIP-09 a-tag deletions in EventIngestCache (BUG-03, BUG-05)
- [x] 02-03-PLAN.md — NIP-09 delete commits only after Amber confirms, in the shared coordinator and the profile call site (BUG-06)
- [x] 02-04-PLAN.md — NostrSessionManager job-field audit: three racy fields converted to atomic holders, three documented as safe (BUG-13)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 02-05-PLAN.md — Feed mute mirror resolves the active filter correctly and mute/pin writes surface their failure (BUG-07, BUG-08)

### Phase 3: Fix Validation & Test Coverage

**Goal**: Each of the ten already-fixed-but-unvalidated bugs is either closed out with automated test evidence or explicitly, visibly handed to the user for an on-device pass — no entry is left in ambiguous limbo before the release.
**Depends on**: Phase 2
**Requirements**: VALID-01, VALID-02, VALID-03, VALID-04, VALID-05, VALID-06, VALID-07, VALID-08, VALID-09, VALID-10
**Success Criteria** (what must be TRUE):

  1. All ten entries (LOG-1, 2, 3, 4, 6, 7, 11, 12, 13, 14) carry a recorded automated-verifiable-vs-needs-human-eyeball determination with a one-line rationale each; none is left undecided.
  2. Every entry judged automated-verifiable has a named, passing unit test that exercises the fixed behaviour (either newly written, or an existing test identified and cited on the entry) — a test that would have failed against the pre-fix code.
  3. Entries backed by passing automated evidence are moved verbatim into `docs/DONE.md` with a `**Validated:** 2026-09-02`-style line; entries genuinely needing a running app stay in `docs/KNOWN_ISSUES.md`, restated so it is unambiguous they are awaiting the user's own `run-umbra` pass.
  4. `testDebugUnitTest` passes with the added tests and `lintDebug` is clean.
  5. No entry reached `DONE.md` on the strength of an emulator or device run — device validation stayed opt-in per `.claude/CLAUDE.md`, and nothing in this phase launched one.

**Plans**: 2/8 plans executed

Plans:
**Wave 1**

- [x] 03-01-PLAN.md — One entry end to end as the tracer (cite, run, move, date) plus the expanded-scope requirement ids

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 03-02-PLAN.md — Citation ledger: exact, executed, revert-tested test citations for the 15 already-covered entries
- [ ] 03-03-PLAN.md — Disposition ledger: source-read verifications, architectural blockers, and device-pass entries
- [ ] 03-04-PLAN.md — Extend the relay-coordinator test with the discovered-flag and per-relay-lock coverage three fixes never got
- [ ] 03-05-PLAN.md — New relay-client test file covering both halves of the same-relay dial-race fix
- [ ] 03-06-PLAN.md — The ten remaining injected-logger cleanup catches, plus the future-dated-events default and its feed wiring

**Wave 3** *(blocked on Wave 2 completion)*

- [ ] 03-07-PLAN.md — Move the 27 closed entries into the completed log with test or source-read citations

**Wave 4** *(blocked on Wave 3 completion)*

- [ ] 03-08-PLAN.md — Annotate the ten entries that stay, complete all 38 requirement rows, and run the phase gate

### Phase 4: Version Consistency & v0.1.0 Release Prep

**Goal**: The app reports one true version from a single source, `CHANGELOG.md` names a dated 0.1.0, a signed public release is one `git push` away, and the process is captured as a reusable project skill — with the push itself left to the user.
**Depends on**: Phase 3
**Requirements**: VERS-01, VERS-02, REL-01, REL-02, REL-03, SKILL-01
**Success Criteria** (what must be TRUE):

  1. Settings displays the version read from `BuildConfig.VERSION_NAME` (Gradle `buildConfig` feature enabled), the `settings_version_value` string resource is gone from `strings.xml`, and `app/build.gradle.kts`'s `versionName` is the only place the version number is written — a `grep` for the old `0.1.0-beta` literal returns nothing.
  2. `CHANGELOG.md` has a dated `## [0.1.0] - <date>` section in the Keep a Changelog format already used in the file, replacing `[Unreleased]`.
  3. Release readiness is verified and recorded: `lintDebug` and `testDebugUnitTest` pass, an R8-shaped release build succeeds (`assembleRelease`, or `assembleBenchmark` where local release signing keys are unavailable — noted explicitly either way), and `android-release.yml`'s trigger, secret names, and signing inputs are confirmed against the repo's actual configuration.
  4. A `v0.1.0` git tag exists locally and is confirmed absent from the remote (`git ls-remote --tags origin` shows no `v0.1.0`); pushing it is left as an explicit user action.
  5. `.claude/skills/umbra-release/SKILL.md` exists, follows the existing `umbra-*` skill convention, and documents the full release path — version bump, changelog, tag, CI signing, GitHub Release — including the mandatory explicit-confirmation gate before the tag push.

**Plans**: TBD

## Notes

**No UI-phase hint on any phase.** Phases 2 and 4 touch existing UI code (`FeedViewModel`, `ProfileViewModel`, `SettingsScreen.kt`), but this milestone introduces no new UI surface, layout, or component — Phase 2 corrects state that existing UI already renders, and Phase 4 changes where an existing settings row sources its string. A UI design contract (`/gsd-ui-phase`) would have nothing to specify.

**Project skills to load during planning/execution** (`.claude/skills/`):

- Phase 1: `find-non-lambda-logs` (log-site auditing, scrubbing, catch-block throwable loss)
- Phase 2: `kotlin-coroutines-structured-concurrency` (read its "In Umbra" section first — stored `CoroutineScope` in `@Singleton` repositories is deliberate, not a bug), `umbra-coroutines`, `umbra-relay-client`, `umbra-app-state`, `umbra-feed-patterns`, `umbra-kotlin-patterns`
- Phase 3: `umbra-app-state`, `umbra-relay-client` (cache/DB and relay-race entries), `run-umbra` only if the user explicitly opts into a device pass
- Phase 4: `umbra-gradle` (`buildConfig` feature, R8/minification-only hazards the debug build can't catch)

`AUDIT.md` takes precedence over all of the above and must be read before any change.

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Error Visibility & Log Hygiene | 3/3 | Complete    | 2026-09-03 |
| 2. Concurrency & State Correctness | 5/5 | Complete    | 2026-09-04 |
| 3. Fix Validation & Test Coverage | 2/8 | In Progress|  |
| 4. Version Consistency & v0.1.0 Release Prep | 0/TBD | Not started | - |

---
*Roadmap created: 2026-09-02*
