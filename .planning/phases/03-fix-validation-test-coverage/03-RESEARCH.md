# Phase 3: Fix Validation & Test Coverage - Research

**Researched:** 2026-09-04
**Domain:** Test-coverage audit of 38 already-shipped bug fixes (no new production code)
**Confidence:** HIGH — every classification below is grounded in a source file actually opened this session (fix location + existing test file, where one exists), not in KNOWN_ISSUES.md's prose summary alone.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Visual/timing bugs — LOG-2, LOG-3, LOG-13 (D-01):** The project has no Compose UI test / Robolectric infrastructure, so the actual rendered pixels for LOG-2 (image sometimes never loads), LOG-3 (video aspect-ratio mismatch), and LOG-13 (avatar/banner stuck on placeholder) can never be asserted by a unit test. For all three: write a narrow unit test for whatever underlying logic IS pure/testable, but still classify all three as needing human eyeball in `KNOWN_ISSUES.md` — the test is bonus coverage, not proof the visual bug is fixed. Do not claim `DONE.md` status on the strength of a partial logic test alone. Note for implementers: `ImageLoadGateTest.kt` already exists and thoroughly covers the gate's own acquire/release/cancel-safety logic in isolation — LOG-2's actual root cause was in the Compose `LaunchedEffect` wiring in `NostrImageComponents.kt`, not in `ImageLoadGate` itself — assess during research whether `ImageLoadGateTest.kt`'s existing coverage is close enough to cite, or whether a new, narrower test is still worth attempting for the composable-level logic.

**Concurrency-bug test rigor — LOG-4, LOG-12 (D-02):** LOG-4 (relay-list save TOCTOU race) and LOG-12 (same-relay concurrent dial race) both get real-race tests — `TestDispatcher`/`runTest` launching genuinely overlapping coroutines and asserting no update is lost or no duplicate dial occurs — matching Phase 2's D-07 precedent and `EventIngestCacheTest.kt`'s existing pattern. Not simpler sequential-call behavioral tests. Reversibility: reversible.

**Existing test reuse — all 38 entries (D-03):** Several entries already have a test file from when their fix originally landed. When an existing test genuinely already exercises the fixed behavior and would demonstrably fail against pre-fix code, cite it as evidence — do not duplicate coverage by writing a second test for the same behavior. Only write a new test where no existing one qualifies, or where existing coverage is partial.

**Scope expansion — all 38 fix-applied entries, not just VALID-01..10 (D-08):** Audit all 38 `docs/KNOWN_ISSUES.md` entries at `fix applied — needs on-device validation` status. The other 28 (LOG-18/19/20/21/22/23/24/26/27/28/29/30/31/34/37/38/39/40/41/42/46/47/49/51/52/53/54/55) are Phase 1/2's own fixed bugs never moved to `DONE.md`. Add new `VALID-11` onward requirement IDs for the 28 extra entries, one-to-one with their `LOG-N`. Explicitly excluded: **LOG-44** (`docs/TODO.md`, needs an architectural change — interface seam or mocking framework — not an audit-and-cite pass) and **LOG-35** (`docs/KNOWN_ISSUES.md`, status `open`, no fix has landed). Reversibility: reversible.

### Claude's Discretion
- Exact classification (automated-verifiable vs. needs-eyeball) for each of the 28 newly-in-scope entries is left to research/planning.
- Whether `ImageLoadGateTest.kt`'s existing coverage is "close enough" to cite for LOG-2, vs. needing a new composable-level test, is left to research/implementation discretion.

### Deferred Ideas (OUT OF SCOPE)
- **LOG-44** — missing `NostrSessionManagerTest`/`RelayConfigViewModelTest` coverage. Needs an architectural change (interface seam or mocking framework), not an audit-and-cite pass. Stays in `docs/TODO.md`.
- **LOG-35** — status `open`, no fix landed, not eligible for validation-audit.

**Research finding relevant to this constraint:** LOG-44's blocker (no test class possible for `NostrSessionManager` without a new interface seam) is not unique to LOG-44 — it also blocks four of the 28 in-scope entries (LOG-30, LOG-38, LOG-49, LOG-52), all of which live inside `NostrSessionManager`. See "Critical Finding 2" below and the Open Questions section — this needs explicit user/planner resolution since it looks like scope creep back into LOG-44's excluded territory, but isn't: it's the same architectural fact discovered independently for different bug entries in the same class.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| VALID-01 | Validate LOG-1 — stale replaceable-event revisions in EventLruCache | Existing test already covers this, but not the one CONTEXT.md names — see classification table, Group A |
| VALID-02 | Validate LOG-2 — ImageLoadGate permit acquire/release race | D-01 visual bug; partial existing coverage only |
| VALID-03 | Validate LOG-3 — inline video aspect-ratio mismatch | D-01 visual bug; pure-logic bonus test already exists |
| VALID-04 | Validate LOG-4 — relay list TOCTOU race | D-02 real-race test; none exists yet, must be written |
| VALID-05 | Validate LOG-6 — stale replaceable revisions in encrypted Room DB | No DB test infrastructure exists in this repo — new classification category, see Critical Finding 3 |
| VALID-06 | Validate LOG-7 — future-dated events filter / recheck ticker | Partial coverage; wiring-level gap identified |
| VALID-07 | Validate LOG-11 — ticker immediate-first-emission fix | Existing test fully covers this |
| VALID-08 | Validate LOG-12 — same-relay concurrent dial race | D-02 real-race test; none exists yet, must be written |
| VALID-09 | Validate LOG-13 — avatar/banner retry-on-Tor-cold-start | D-01 visual bug; no pure-logic extraction possible, unlike LOG-3 |
| VALID-10 | Validate LOG-14 — promoting a discovered relay to owned role | Fix lives in an already-well-tested collaborator; cheap new test needed |

**Requirement ID mapping for the 28 expanded-scope entries (D-08), proposed for REQUIREMENTS.md:**

| New ID | LOG-N | Classification (see full table below) |
|--------|-------|----------------------------------------|
| VALID-11 | LOG-18 | Structurally unassertable (Group D) |
| VALID-12 | LOG-19 | Automated-verifiable, existing test |
| VALID-13 | LOG-20 | Structurally unassertable (Group D) |
| VALID-14 | LOG-21 | Automated-verifiable, existing test |
| VALID-15 | LOG-22 | Automated-verifiable, existing test |
| VALID-16 | LOG-23 | Automated-verifiable, existing test |
| VALID-17 | LOG-24 | Automated-verifiable, existing test |
| VALID-18 | LOG-26 | Needs eyeball (Compose-only, no ViewModel) |
| VALID-19 | LOG-27 | Automated-verifiable, partial — gap to close |
| VALID-20 | LOG-28 | Structurally unassertable (Group D) |
| VALID-21 | LOG-29 | Automated-verifiable, existing test |
| VALID-22 | LOG-30 | Blocked — same gap as excluded LOG-44 (Group C) |
| VALID-23 | LOG-31 | Automated-verifiable, existing test |
| VALID-24 | LOG-34 | Automated-verifiable, existing test |
| VALID-25 | LOG-37 | Automated-verifiable, new test needed (cheap) |
| VALID-26 | LOG-38 | Blocked — same gap as excluded LOG-44 (Group C) |
| VALID-27 | LOG-39 | Blocked + structurally unassertable (Group C+D) |
| VALID-28 | LOG-40 | Automated-verifiable, partial — cite or extend |
| VALID-29 | LOG-41 | Automated-verifiable, existing test |
| VALID-30 | LOG-42 | Automated-verifiable, new test needed (cheap) |
| VALID-31 | LOG-46 | Automated-verifiable, partial — cite or extend |
| VALID-32 | LOG-47 | Automated-verifiable, existing test |
| VALID-33 | LOG-49 | Blocked — same gap as excluded LOG-44 (Group C) |
| VALID-34 | LOG-51 | Blocked + structurally unassertable (Group C+D) |
| VALID-35 | LOG-52 | Blocked — same gap as excluded LOG-44 (Group C) |
| VALID-36 | LOG-53 | Automated-verifiable, existing test |
| VALID-37 | LOG-54 | Structurally unassertable (Group D) |
| VALID-38 | LOG-55 | Automated-verifiable, is itself the test |
</phase_requirements>

## Summary

This phase is a pure audit-and-cite/write-test pass over 38 already-merged bug fixes — no new
production behavior. The research below reads every fix location and cross-references it against
the full `app/src/test/` tree (127 test files) to answer, per entry, one question: **is there a
plain-JUnit4 test that would fail if this fix were reverted, and if not, can one be written under
this project's existing no-mocking-framework conventions?**

Three findings emerged that materially change how the planner should size this phase, beyond
what CONTEXT.md anticipated:

**Critical Finding 1 — the CONTEXT.md-suggested citations are sometimes the wrong file.**
`EventLruCacheTest.kt` (named in CONTEXT.md for LOG-1) only exercises generic LRU
eviction/capacity/stats — it has zero awareness of `ReplaceableEventKey`/`winsReplaceableRace()`,
which is what LOG-1's fix actually added. The test that actually proves LOG-1's fix is
`EventRepositoryIngestionIntegrationTest.kt`'s two "replaceable slot delivered in
ascending/descending order" cases, which exercise the real `EventRepositoryImpl` ingestion
pipeline end to end. Always verify a suggested citation opens the fix's actual root-cause file,
not just an adjacently-named test file.

**Critical Finding 2 — a fourth of the "logging visibility" fixes cannot be unit-tested at all,
for an architectural reason, not a laziness one.** `Logger.e()`/`.d()`/`.w()` gate on
`Log.isLoggable()`, which returns `false` under this project's `isReturnDefaultValues = true` JVM
unit-test config — so the call executes without crashing but produces no observable side effect a
plain JUnit test can assert. That's fine when a class takes `logger: UmbraLogger` as a
**constructor parameter** (then `FakeUmbraLogger` — already in `testutil/fakes/` — can capture the
call): `LogoutUseCase` and `TrimMemoryCachesUseCase` do this and are testable. It's a dead end when
a class instead does `private val logger = UmbraLog.tag(TAG)` internally: `EventRepositoryImpl`,
`NegentropySyncOrchestrator`, `LoginViewModel`, `RelayConfigViewModel`, `NostrSessionManager`, and
`InteractionActionsCoordinator` **all** use this second, non-injected pattern. Every
"silent-catch-now-logs" fix inside those six classes (LOG-18, 20, 28, 39, 51, 54) has **no
possible unit-test seam for the actual code change** — a test can exercise the surrounding
behavior (e.g. "the wipe still proceeds"), but that behavior was already true pre-fix, so such a
test would not satisfy the phase's own "would have failed pre-fix" bar. This is a distinct third
category from D-01's "needs eyeball because it's visual" — it needs eyeball (or a source-read
citation) because the app's own logging architecture has no test seam, independent of anything
visual. See the Open Questions section for how the planner should present this to the user.

**Critical Finding 3 — LOG-30/38/49/52 hit the exact same wall D-08 explicitly excluded LOG-44
for.** `NostrSessionManager`'s constructor takes `TorRuntimeManager` and `BackfillAnchorStore` —
both concrete classes, not interfaces, so neither has a `Fake` in `testutil/fakes/` and neither can
be substituted without the same "new interface seam or mocking framework" work LOG-44 was
deliberately deferred over. No `NostrSessionManagerTest.kt` exists anywhere in the repo. LOG-30,
LOG-38, LOG-49, and LOG-52 all live inside `NostrSessionManager` and are all four currently in
Phase 3's expanded scope (D-08) — meaning this phase, as scoped, contains four entries that need
the identical architectural change D-08 just excluded LOG-44 over. This is flagged as an open
question, not resolved here — see below.

**Primary recommendation:** Of the 38 entries, **14 already have a fully sufficient existing test**
and can move straight to `DONE.md`; **5 need a new, cheap test** using an existing, already-built
test harness (`RelayCrudCoordinatorTest.kt`'s `RecordingRelayRepository`/`gateInvocation`
machinery, mostly); **4 have partial coverage** that should be extended by a few more assertions
following an already-established in-file pattern; **1 (LOG-6)** has no possible test under current
DB test infrastructure; **4 (LOG-2/3/13/26)** are genuinely visual/UI-only per D-01; and **10
(LOG-18/20/28/30/38/39/49/51/52/54)** are blocked by one of the two architectural gaps above and
should go to the user for an explicit decision on how "verified but unassertable" gets recorded.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Test-writing for pure domain/data logic (caches, DAOs-in-theory, use cases) | Domain/Data (plain Kotlin, JVM-testable) | — | Already the project's dominant test tier; no device needed |
| Test-writing for ViewModel/Coordinator behavior | UI/ViewModel (plain Kotlin, JVM-testable via fakes) | — | ViewModels/Coordinators here take interface-only dependencies (mostly) so are unit-testable without Android framework |
| Bug-tracker bookkeeping (`KNOWN_ISSUES.md` → `DONE.md`) | Docs/process | — | No code tier; a bookkeeping-only move |
| On-device confirmation for D-01 visual bugs | Browser/Device (opt-in, out of this phase) | — | No Compose UI test / Robolectric infra exists; stays with the user's own `run-umbra` pass |

This phase touches no new architectural tier — it is entirely about which existing tier a given
fix's logic lives in (testable-in-JVM vs. Compose-rendering-only vs. no-test-seam-exists), which is
exactly the axis the classification table below is built on.

## Full Classification Table (all 38 entries)

Legend — **DONE**: automated-verifiable, existing test already sufficient, move to `DONE.md`
citing it as-is. **NEW (cheap)**: automated-verifiable, no existing test, but the harness/fakes
already exist — writing the test is small. **PARTIAL**: some coverage exists but doesn't fully
pin the fix; extend or explicitly accept the partial citation. **EYEBALL**: D-01-style, genuinely
needs on-device confirmation, no full automated substitute possible. **BLOCKED**: same
architectural gap as the excluded LOG-44 (`NostrSessionManager`'s concrete dependencies). **UNASSERTABLE**: the fix is a logging call behind a non-injected `UmbraLog.tag(TAG)` logger — no unit
test can observe it regardless of how the surrounding class is built.

| LOG-N | Verdict | Citation / Gap |
|-------|---------|----------------|
| LOG-1 | **DONE** | `EventRepositoryIngestionIntegrationTest.kt:660-697` — "given two revisions of a replaceable slot delivered in ascending/descending timestamp order then only the newer revision is retrievable". **Not** `EventLruCacheTest.kt` (generic LRU only — see Critical Finding 1). |
| LOG-2 | **EYEBALL** | `ImageLoadGateTest.kt` covers the gate's own acquire/release/cancel-safety, but the actual fix is in `NostrImageComponents.kt`'s `LaunchedEffect` consolidation — Compose-embedded, no pure-function extraction exists. Cite `ImageLoadGateTest.kt` as adjacent bonus coverage only. |
| LOG-3 | **EYEBALL** (bonus test already exists) | `VideoPlayerControllerTest.kt`: "given anamorphic pixel ratio when computing aspect ratio then pixelWidthHeightRatio is applied" — directly exercises the pure `computeVideoAspectRatio` logic behind this fix. Still needs eyeball for rendered pixels per D-01, but the bonus-test half of D-01's ask is already satisfied — no new work needed. |
| LOG-4 | **NEW** | No existing test covers `UserRepositoryImpl.saveRelayList()`'s `ConcurrentHashMap.compute()` TOCTOU fix. Needs a genuinely-overlapping-coroutines test per D-02. |
| LOG-6 | **EYEBALL** (different reason — see Critical Finding 3-adjacent note below) | `EventDao.deleteSupersededReplaceableEvents()` is Room/SQLCipher DAO logic. No Room DAO test exists anywhere in this repo (unit or instrumented) — `EventRepositoryIngestionIntegrationTest.kt`'s `FakeEventDao.deleteSupersededReplaceableEvents()` is a stub returning `0`, never exercising real SQL. There is no JVM-testable Room infrastructure in this codebase today (no Robolectric, no in-memory-db harness). This is a distinct, third non-testable reason (no DB test seam) — not visual, not a non-injected logger. |
| LOG-7 | **PARTIAL** | `EventModelBehaviorTest.kt`'s `isFromFuture(tolerance)`/`isTimestampFromFuture(...)` tests are parametric over `tolerance` and don't pin the actual product decision (`toleranceSeconds: Long = 0L` is now the hardcoded default — confirmed by reading `Event.kt:341,350`). No test asserts the default is zero, and no test exists for `ProfileViewModel`/`ThreadViewModel` (no test file for either) exercising the `combine(futureEventRecheckTicker())` wiring `FeedViewModel`/`ProfileViewModel`/`ThreadViewModel` all use. `FutureEventRecheckTickerTest.kt` covers the ticker's own emission timing only. |
| LOG-11 | **DONE** | `FutureEventRecheckTickerTest.kt`: "given ticker when collecting first tick then it emits immediately with no delay" — exactly this regression's fix, already updated when the fix landed. |
| LOG-12 | **NEW** | No existing test covers `UmbraNostrClient.dialingRelays` or `onWebSocketOpen`'s identity check (`RelayWebSocketListenerTest.kt` doesn't touch either). Needs a genuinely-overlapping-coroutines test per D-02. |
| LOG-13 | **EYEBALL** | Retry logic (`MAX_IMAGE_LOAD_RETRIES`/`IMAGE_RETRY_DELAYS_MS`) is fully inline inside `UserAvatar.kt`'s `LaunchedEffect` using Compose `mutableStateOf` for `retryAttempt` — unlike LOG-3, there is no pure function to extract and test without a production-code change (out of this validation-only phase's scope). Recommend not attempting a bonus test here. |
| LOG-14 | **NEW (cheap)** | `RelayConfigViewModel.saveRelay()` is a one-line delegate (`= relayCrudCoordinator.saveRelay(relay)`) — the actual `isDiscovered = false` forcing lives in `RelayCrudCoordinator.kt:81,118`, which already has a full test harness in `RelayCrudCoordinatorTest.kt`. No existing case asserts `isDiscovered` specifically. Cheap to add using the existing `subject()`/`RecordingRelayRepository`. Also: KNOWN_ISSUES.md's "Where" pointer (`RelayConfigViewModel.kt`) is stale post-extraction — correct it to `RelayCrudCoordinator.kt` when moving to DONE. |
| LOG-18 | **UNASSERTABLE** | `EventRepositoryImpl.kt`/`NegentropySyncOrchestrator.kt` both use `private val logger = UmbraLog.tag(TAG)` (non-injected). `LogScrubber.scrubUrlForLogs()`/`scrubThrowableMessageForLogs()` themselves are well-tested (`LogScrubberTest.kt`), but whether these 3 specific call sites actually wrap their values in them is only confirmable by reading the current source — no unit test can observe the call. |
| LOG-19 | **DONE** | `EventIngestCacheTest.kt:651,678,706` — the exact three cases (regression, ownership guard, recency guard) KNOWN_ISSUES.md's own Fix line describes. |
| LOG-20 | **UNASSERTABLE** | `EventRepositoryImpl.clearAllData()`'s `disconnectFromAll()` catch — same non-injected-logger class as LOG-18. No test of `clearAllData()` exists at all in the repo. |
| LOG-21 | **DONE** | `EventIngestCacheTest.kt:366` (8-way concurrent `scheduleSnapshotEmit`, real overlapping coroutines, D-02 grade) + `:394` (cancel-then-idempotent-recancel). |
| LOG-22 | **DONE** | `InteractionActionsCoordinatorTest.kt:317-388` — the confirmed/rejected/owner-check-fails `deleteEvent` cases, asserting `onDeleteConfirmed`/cache removal only fire after sign resolves. |
| LOG-23 | **DONE** | `FeedFilterTest.kt:48` — "given a filter with a persisted-looking id when merging then the merged id is the fixed synthetic id, never the input's" — pins the exact invariant that made the old lookup permanently dead. |
| LOG-24 | **DONE** | `FeedViewModelStateTest.kt` — `muteWriteResultMessage`/`pinWriteResultMessage` success/failure mapping tests. |
| LOG-26 | **EYEBALL** | `SettingsScreen.kt`'s logout `onClick` try/catch — pure Compose UI code, no ViewModel, no extractable pure logic. No Compose UI test / Robolectric infra exists (same infra gap D-01 already established for LOG-2/3/13). |
| LOG-27 | **PARTIAL** | `BackfillDeleteLogoutUseCaseTest.kt:363-387` asserts `logger.errorCalls` for exactly 1 of `LogoutUseCase`'s 7 per-step catches (`eventRepository.clearAllData()`); the other 6 steps have "still runs/clears anyway" tests that would pass even pre-fix (that behavior was unchanged — only the logging call was added), so they don't satisfy "would fail pre-fix." `TrimMemoryCachesUseCaseTest.kt:85-102` has the identical shape: 1 of 5 `TrimMemoryCachesUseCase` steps asserted, 4 not. Both classes take `logger: UmbraLogger` via constructor (injectable, unlike Group D above) and already have `FakeUmbraLogger`-based fixtures in place — extending is mechanical, not a new pattern. |
| LOG-28 | **UNASSERTABLE** | `LoginViewModel.kt` uses `private val logger = UmbraLog.tag(TAG)`. No `LoginViewModelTest.kt` exists at all. |
| LOG-29 | **DONE** | `RelayCrudCoordinatorTest.kt:157` — "given two overlapping role toggles on the same relay when both resolve then neither update is lost" (D-02 grade, uses `gateInvocation` to force genuine overlap) + `:184` (different-relay non-serialization). |
| LOG-30 | **BLOCKED** | `NostrSessionManager` has no test file — concrete `TorRuntimeManager`/`BackfillAnchorStore` constructor dependencies, no interface seam (identical gap to excluded LOG-44). `AtomicJobSchedulingTest.kt`'s real-thread `launchIfIdle`/`launchReplacing` race tests (lines 139, 179) cover the reusable helper LOG-30 introduced, but not `NostrSessionManager`'s actual field wiring. KNOWN_ISSUES.md's own LOG-38 entry (a residual gap in this same fix) already says "No dedicated concurrency test exists for this fix; flagged for manual verification" — i.e., the codebase itself has effectively pre-classified this as needing eyeball. |
| LOG-31 | **DONE** | `RelayCrudCoordinatorTest.kt:116,129,144` — the three `setDmEnabled` dirty-flag cases (rejected plaintext, accepted wss, unknown relayId). |
| LOG-34 | **DONE** | `LogScrubberTest.kt:46,56,66` — `scrubThrowableForLogs`'s redaction, cause-chain-dropped, and stack-frames-preserved cases. This tests the pure `LogScrubber` object directly (not a call behind a per-class internal logger), so unlike LOG-18/20/28/39/51/54 it IS fully assertable. |
| LOG-37 | **NEW (cheap)** | No existing `RelayCrudCoordinatorTest.kt` case calls `removeRelayRole` at all. Add a race test between `removeRelayRole` and a `set*Enabled` setter on the same relay id, reusing the existing `gateInvocation` harness. |
| LOG-38 | **BLOCKED** | Same `NostrSessionManager` gap as LOG-30 — `@Volatile` field-visibility fix, no test class possible without the LOG-44 architectural change. |
| LOG-39 | **BLOCKED + UNASSERTABLE** | `RelayConfigViewModel` has zero test file (though its 14 constructor params are all interfaces, unlike `NostrSessionManager` — technically buildable, just heavy) AND uses non-injected `UmbraLog.tag(TAG)` — even a full `RelayConfigViewModelTest` fixture couldn't assert the actual logging change. Not worth the heavy fixture cost for an unassertable fix. |
| LOG-40 | **PARTIAL** | The generic `AtomicJobScheduling.launchReplacing` cancel-strictly-before-start guarantee is well-tested (`AtomicJobSchedulingTest.kt:94,179`, including a real-thread race), but no `EventIngestCacheTest.kt` case specifically exercises `scheduleInsert()`'s use of it. Low regression risk (one-line migration to an already-tested helper) — cite the generic test or add one targeted assertion. |
| LOG-41 | **DONE** | `EventIngestCacheTest.kt:225,244` — both directions (repost-cached-then-superseded-by-direct-ingest, and direct-ingest-then-rejects-older-via-repost). |
| LOG-42 | **NEW (cheap)** | No existing race test between `saveRelay`/`deleteRelay` and `updateRelayRole` for the same relay id. Add using the existing `gateInvocation` harness. |
| LOG-46 | **PARTIAL** | `runCatchingCancellable`'s `CancellationException`-propagates behavior is fully tested generically (`CancellableRunCatchingTest.kt:18`). `InteractionActionsCoordinatorTest.kt:252-289` tests `mirrorMuteIntoActiveFilter`'s mute/unmute/null-resolver behavior but not cancellation specifically. Low regression risk (one-line migration) — cite the generic helper test or add one targeted coordinator-level cancellation test. |
| LOG-47 | **DONE** | `RelayCrudCoordinatorTest.kt:210` — the blank-id merge test asserts `isReadEnabled == true` survives from a fresh repository read (this is literally the test LOG-55 describes adding). |
| LOG-49 | **BLOCKED** | Same `NostrSessionManager` gap as LOG-30/38 — `ownProfileBootstrapMutex` fix, no test class possible. |
| LOG-51 | **BLOCKED + UNASSERTABLE** | Same `NostrSessionManager` gap AND uses non-injected `UmbraLog.tag(TAG)` — double gap, same as LOG-39. |
| LOG-52 | **BLOCKED** | Same `NostrSessionManager` gap as LOG-30/38/49 — `stopOwnProfileBootstrapLocked()` mutex-widening fix, no test class possible. |
| LOG-53 | **DONE** | `RelayCrudCoordinatorTest.kt:210` — same test as LOG-47 (this single test proves both fixes together — see note on LOG-47/53/55 below). |
| LOG-54 | **UNASSERTABLE** | `InteractionActionsCoordinator.kt` uses `private val logger = UmbraLog.tag("InteractionActionsCoordinator")` (non-injected) — pure `logger.d`→`logger.e` level change, unassertable regardless of the class's otherwise-good testability (it already has `InteractionActionsCoordinatorTest.kt`). |
| LOG-55 | **DONE** | `RelayCrudCoordinatorTest.kt:210` IS the fix this entry describes adding — self-referential; closing this entry means citing this test as already landed. |

**Note on LOG-47/LOG-53/LOG-55:** these three entries all point at the *same* single test
(`RelayCrudCoordinatorTest.kt:210`). LOG-55 is literally "a test didn't exist for this merge
branch" (now it does); LOG-47 and LOG-53 are the two specific correctness properties that one test
happens to prove together (fresh-read merge base, and fresh-read existence decision). The planner
should treat these as one unit of work (verify the test still exists and passes, cite it three
times) rather than three separate investigations.

## Root-Cause Groups (for batching plan tasks)

### Group A — Replaceable-event dedup (cache / DB / ingestion)
**Entries:** LOG-1, LOG-6, LOG-19, LOG-21, LOG-40, LOG-41
**Shared files:** `data/repository/EventIngestCache.kt`, `data/repository/EventRepositoryImpl.kt`, `data/db/dao/EventDao.kt`, `domain/nip01/ReplaceableEventKey.kt`
**Shared test file:** `EventIngestCacheTest.kt` (LOG-19/21/40/41), `EventRepositoryIngestionIntegrationTest.kt` (LOG-1)
**Status:** 4 of 6 already DONE (LOG-1, 19, 21, 41); LOG-40 partial; LOG-6 has no possible test (Room/SQLCipher, no DB test infra).

### Group B — Relay-lock races (RelayCrudCoordinator)
**Entries:** LOG-14, LOG-29, LOG-31, LOG-37, LOG-42, LOG-47, LOG-53, LOG-55
**Shared file:** `ui/relay/RelayCrudCoordinator.kt`
**Shared test file:** `RelayCrudCoordinatorTest.kt`
**Status:** 5 of 8 already DONE (LOG-29, 31, 47, 53, 55); 3 need cheap new tests using the existing harness (LOG-14, 37, 42). This is the single best-instrumented class in the whole 38-entry set — the planner should treat "extend `RelayCrudCoordinatorTest.kt`" as one task covering LOG-14/37/42 together.

### Group C — NostrSessionManager concurrency (blocked, same gap as LOG-44)
**Entries:** LOG-30, LOG-38, LOG-49, LOG-51, LOG-52
**Shared file:** `data/nostr/NostrSessionManager.kt`
**Status:** No test class possible without the interface-seam/mocking-framework work LOG-44 was deferred over. LOG-51 additionally uses a non-injected logger. Flag as a single decision point for the user — see Open Questions.

### Group D — Logging-visibility fixes behind a non-injected logger (structurally unassertable)
**Entries:** LOG-18, LOG-20, LOG-28, LOG-39, LOG-51, LOG-54
**Shared pattern:** `private val logger = UmbraLog.tag(TAG)` inside the class itself, rather than `logger: UmbraLogger` as a constructor parameter.
**Status:** None of these six can have their actual code change (the added `logger.e(...)` call) asserted by a plain JUnit test under this project's `isReturnDefaultValues = true` config. Flag as a single decision point for the user.

### Group E — Injectable-logger use-case fixes (partially testable, good news)
**Entries:** LOG-27 (spans two classes: `LogoutUseCase`, `TrimMemoryCachesUseCase`)
**Shared pattern:** `logger: UmbraLogger` as a constructor parameter — `FakeUmbraLogger` already substitutes cleanly.
**Status:** 2 of 12 total catch sites (1 per class) already have a would-fail-pre-fix test; the other 10 need the identical pattern extended.

### Group F — Optimistic-apply / result-handling ViewModel fixes (fully tested)
**Entries:** LOG-22, LOG-23, LOG-24
**Shared files:** `ui/common/InteractionActionsCoordinator.kt`, `ui/feed/FeedViewModel.kt`, `domain/feed/FeedFilter.kt`
**Status:** All three already DONE.

### Group G — Visual/timing fixes (D-01, needs eyeball)
**Entries:** LOG-2, LOG-3, LOG-13, LOG-26
**Status:** LOG-3 has a fully-satisfying bonus logic test already; LOG-2 has a partial/adjacent one; LOG-13 and LOG-26 have none and none is recommended (no pure logic to extract without a production-code change).

### Group H — Pure scrub-logic fix (fully tested, distinct from Group D)
**Entries:** LOG-34
**Status:** DONE — tests the `LogScrubber` object directly, no per-class logger involved.

### Ungrouped
**LOG-46** (`InteractionActionsCoordinator`'s `runCatchingCancellable` migration) doesn't fit Group D (it's a behavioral fix, not a logging-level fix) — partial coverage via the generic helper test.

## Common Pitfalls

### Pitfall 1: Citing a test by file name alone, not by what it actually asserts
**What goes wrong:** `EventLruCacheTest.kt` sounds like it should cover LOG-1 (LOG-1's bug report literally names `EventLruCache`), but it never imports or exercises `ReplaceableEventKey`.
**Why it happens:** The fix's "Where" line in `KNOWN_ISSUES.md` names the symptom location, not always the root-cause file the actual logic changed in.
**How to avoid:** Open the fix's actual diff/description and the candidate test file's method list before citing it; confirm the test would fail if the specific mechanism named in the "Fix" line were reverted.
**Warning signs:** A test file whose name matches the "Where" line but whose test method names don't mention the specific mechanism in the "Fix" line.

### Pitfall 2: Assuming "the class already has a Fake" means the fix is testable
**What goes wrong:** `RelayConfigViewModel`'s constructor is 100% interfaces and could theoretically be tested with 14 fakes — but its `enforceAnonymousRelayPolicyIfNeeded` fix is a `logger.e(...)` call behind a non-injected `UmbraLog.tag(TAG)`, so building that fixture would prove nothing about the actual fix.
**Why it happens:** "Testable class" and "testable fix" are different questions — testability of the constructor doesn't imply testability of the specific behavior that changed.
**How to avoid:** Trace the fix to its exact call site and ask whether *that line's effect* is observable through the class's public API or an injected collaborator, not just whether the class can be instantiated with fakes.
**Warning signs:** The fix's own description is "log X instead of swallowing it" — that phrase alone should trigger a check of whether the class's logger is injected or internally constructed.

### Pitfall 3: A "still runs the remaining steps" test looks like fix coverage but isn't
**What goes wrong:** `BackfillDeleteLogoutUseCaseTest.kt`'s `given_failingRepository_when_logging_then_clearsUserRepositoryAndPreferencesAnyway` proves the best-effort continue-on-failure behavior — but that behavior existed identically before LOG-27's fix (the catch blocks were already there, silently swallowing). Only the *logging* changed.
**Why it happens:** It's easy to conflate "a test exercises the code path this fix touched" with "a test would fail if this fix were reverted."
**How to avoid:** For every candidate citation, explicitly ask: if I reverted just this fix's diff (not the whole file), would this test start failing? If the test's assertions are unrelated to what actually changed, it doesn't satisfy the phase's Success Criterion #2.
**Warning signs:** The test asserts call counts or final state that a `catch (_: Exception) {}` (pre-fix) would have produced identically to `catch (e: Exception) { logger.e(e) {...} }` (post-fix).

## Code Examples

### D-02-grade real-race test pattern (already established, reuse verbatim shape)
```kotlin
// Source: app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt:366
@Test
fun `given eight overlapping coroutines calling scheduleSnapshotEmit concurrently when time advances then exactly one snapshot and one bundle emission occur with no event lost`() = runTest {
    // launches 8 real coroutines against the same subject, advances the test scheduler,
    // and asserts exactly one coalesced effect occurred — the shape LOG-4/LOG-12's new
    // tests should follow.
}
```

### RelayCrudCoordinatorTest's gate-to-force-overlap pattern (reuse for LOG-37/LOG-42)
```kotlin
// Source: app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt:157-181
val gate = repository.gateInvocation(0) // holds the first updateRelay call open

coordinator.setOutboxEnabled("relayA", enabled = true)
coordinator.setSearchEnabled("relayA", enabled = true)
advanceUntilIdle()

assertEquals(listOf("enter:relayA"), repository.callLog) // second call blocked by the Mutex

gate.complete(Unit)
advanceUntilIdle()
// both role flags now set — neither update lost
```
For LOG-37, the same `gateInvocation` mechanism races `removeRelayRole` against a `set*Enabled`
call on the same relay id. For LOG-42, race `saveRelay`/`deleteRelay` against `updateRelayRole`.

### Injectable-logger assertion pattern (reuse for LOG-27's remaining 10 sites)
```kotlin
// Source: app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt:362-387
val useCase = LogoutUseCase(repo, userRepo, prefs, contactListRepo, muteListRepo, pinListRepo, sessionController, logger)
useCase()
assertEquals(1, logger.errorCalls.size)
assertSame(thrown, logger.errorCalls.first().throwable)
```
Extend with a `Throwing*Repository` wrapper (matching `ThrowingClearAllDataEventRepository`'s
shape) per remaining step: `nostrSessionController.stop()`, `userRepository.clearAll()`,
`contactListRepository.clearAll()`, `muteListRepository.clearAll()`, `pinListRepository.clearAll()`,
`eventRepository.clearBackfillAnchors(pubkey)` (6 for `LogoutUseCase`), and
`userRepository.pruneStaleData()`, `contactListRepository.trimMemory()`,
`muteListRepository.trimMemory()`, `pinListRepository.trimMemory()` (4 for
`TrimMemoryCachesUseCase`, following `RecordingUserRepository`'s existing shape in
`TrimMemoryCachesUseCaseTest.kt`).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | `RelayConfigViewModel`'s 14 constructor params being all-interface makes a full `RelayConfigViewModelTest` fixture *possible* (just heavy) rather than blocked the way `NostrSessionManager` is — verified by reading its full constructor signature this session, but not exhaustively verified that none of those 14 interfaces have their own untestable transitive concrete dependency. | LOG-39 classification | If wrong, LOG-39's "not worth the heavy fixture cost" framing is moot — it would already be architecturally blocked like LOG-30/38/49/52, which doesn't change the recommended disposition (still unassertable either way) but would mean it belongs in Group C too. |
| A2 | `TorRuntimeManager`/`BackfillAnchorStore` being concrete `@Inject constructor` classes (not interfaces) is sufficient evidence that `NostrSessionManager` cannot be unit-tested without an interface seam — verified by reading both class declarations this session, but not by attempting to actually construct a `NostrSessionManagerTest` and confirming it fails to compile/is impractical. | Critical Finding 3, Group C | If these classes turn out to have simple enough constructors to instantiate directly with real (non-faked) instances in a test, LOG-30/38/49/52 might be less blocked than stated — worth a planner spike before fully committing to "blocked" for all four. |
| A3 | Under `testOptions.unitTests.isReturnDefaultValues = true`, `android.util.Log.isLoggable()` returns `false` and `Log.e()`/`Log.d()` are no-ops, making `Logger.e()`'s effect unobservable in a plain JUnit test — based on standard Android Gradle Plugin behavior for this flag, not verified by writing and running a probe test this session. | Critical Finding 2, Group D | If `Log.isLoggable()` actually returns `true` by default under this config (some AGP versions differ), the call would still execute but there would be no way to intercept the static `Log.e()` call without Mockito/PowerMock (which this project deliberately doesn't use) — the "unassertable" conclusion would still hold, just for a slightly different reason (can't intercept a static call, not "gated off"). Either way the practical recommendation (needs eyeball/source-read) is unchanged. |

## Open Questions

1. **How should the 6 Group D entries (LOG-18/20/28/39/51/54) and the LogoutUseCase/TrimMemoryCachesUseCase's remaining 10 sub-sites be recorded if they get a source-read verification instead of a test?**
   - What we know: the phase's own Success Criterion 3 offers only two outcomes — `DONE.md` with a `**Validated:**` line (automated test evidence), or stays in `KNOWN_ISSUES.md` awaiting the user's own `run-umbra` pass (on-device). Group D's fixes are one-line, already-merged, and trivially confirmable by reading the current source (each `KNOWN_ISSUES.md` entry's "Fix" line already documents the exact code change) — but they aren't actually "genuinely needing a running app" the way LOG-2/3/13/26 are; nobody needs to launch the emulator to see whether `logger.e(e) { }` replaced `catch (_: Exception) {}` in a file that's sitting right there.
   - What's unclear: whether the phase should treat "verified by direct source read, cited by file:line, not by a test or an emulator" as a third valid disposition, or whether the strict binary means these get lumped into `KNOWN_ISSUES.md` alongside genuinely-visual bugs (which would be misleading to a future reader of that file, since it would look like these need an emulator when they don't).
   - Recommendation: raise this explicitly with the user during planning — propose restating these 6 (+ LogoutUseCase's/TrimMemoryCachesUseCase's untested sub-sites, unless the planner extends those per Group E) with a rationale like "verified by direct source read — `logger: UmbraLogger` is not constructor-injected in this class, so no unit test can assert the logging call; behavior confirmed by inspection of `<file>:<line>`" rather than silently forcing them into either bucket.

2. **Do LOG-30/38/49/52 get moved into `docs/TODO.md` alongside LOG-44, or do they stay in `KNOWN_ISSUES.md`?**
   - What we know: they share LOG-44's exact blocker (no interface seam for `NostrSessionManager`'s concrete dependencies), and D-08 explicitly scoped LOG-44 out of this phase for that reason.
   - What's unclear: whether "the same architectural fact discovered independently for four different bug entries" should be handled identically to LOG-44 (deferred to `TODO.md`, out of Phase 3) or whether these four should stay in `KNOWN_ISSUES.md` at `fix applied — needs on-device validation` (since the fix itself is real code that works, just untested) with a note added referencing the shared blocker.
   - Recommendation: keep them in `KNOWN_ISSUES.md` (they are still deployed, working fixes — LOG-44 was about a *pre-existing* test gap, not a fix-in-need-of-validation) but add one shared note across all four (and cross-reference LOG-44) so a future reader doesn't waste time trying to write a `NostrSessionManagerTest` in isolation before that architectural work happens.

3. **LOG-7's ViewModel-wiring gap — worth a new test, or is the ticker/tolerance coverage "good enough"?**
   - What we know: `FutureEventRecheckTickerTest.kt` proves the ticker itself; `EventModelBehaviorTest.kt` proves `isFromFuture`/`isTimestampFromFuture`'s logic for any given tolerance; neither proves the `combine()` wiring in `FeedViewModel`/`ProfileViewModel`/`ThreadViewModel` actually re-filters on each tick, nor that the effective tolerance used in production is the hardcoded zero default.
   - What's unclear: whether `ProfileViewModel`/`ThreadViewModel` are practically testable (no existing test file for either — would need to assess their constructor complexity, not done this session) versus whether `FeedViewModel` (which does have extensive existing test coverage patterns) is the cheaper place to add one wiring-level test that stands in for all three call sites structurally sharing the same `combine(futureEventRecheckTicker())` idiom.
   - Recommendation: at minimum, add one cheap `EventModelBehaviorTest.kt`-style test asserting `Event(createdAt = now + 1).isFromFuture()` (using the default parameter, no explicit tolerance) returns `true` — that directly pins the zero-tolerance product decision that the existing parametric test doesn't. Defer the full ViewModel-wiring question to planning once `ProfileViewModel`/`ThreadViewModel`'s testability is assessed.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4 (no JUnit 5), `org.junit.Assert.*`, no mocking framework |
| Config file | `app/build.gradle.kts` (`testOptions.unitTests.isReturnDefaultValues = true`) |
| Quick run command | `./gradlew testDebugUnitTest --tests "com.umbra.app.<package>.<ClassName>Test"` |
| Full suite command | `./gradlew testDebugUnitTest` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|--------------------|--------------|
| VALID-01 | LOG-1 replaceable-event dedup at ingest | integration | `./gradlew testDebugUnitTest --tests "com.umbra.app.data.repository.EventRepositoryIngestionIntegrationTest"` | Yes |
| VALID-04 | LOG-4 relay-list save TOCTOU race | unit (new) | `./gradlew testDebugUnitTest --tests "com.umbra.app.data.repository.UserRepositoryImplTest"` (or wherever the new test lands) | No — Wave 0 gap |
| VALID-08 | LOG-12 same-relay dial race | unit (new) | `./gradlew testDebugUnitTest --tests "com.umbra.app.data.nostr.UmbraNostrClientTest"` (or wherever the new test lands) | No — Wave 0 gap |
| VALID-10 | LOG-14 isDiscovered forcing | unit (extend existing) | `./gradlew testDebugUnitTest --tests "com.umbra.app.ui.relay.RelayCrudCoordinatorTest"` | Yes (extend) |
| VALID-25/30 | LOG-37/LOG-42 relay-mutex coverage | unit (extend existing) | `./gradlew testDebugUnitTest --tests "com.umbra.app.ui.relay.RelayCrudCoordinatorTest"` | Yes (extend) |
| VALID-19 | LOG-27 remaining 10 logger-assertion sites | unit (extend existing) | `./gradlew testDebugUnitTest --tests "com.umbra.app.domain.usecase.BackfillDeleteLogoutUseCaseTest"` / `TrimMemoryCachesUseCaseTest` | Yes (extend) |

### Sampling Rate
- **Per task commit:** the specific extended/new test class's `testDebugUnitTest --tests` invocation.
- **Per wave merge:** `./gradlew testDebugUnitTest` (full suite — this phase touches ~10 existing test files plus a handful of new ones; regressions elsewhere are unlikely but cheap to rule out given the suite's current size, ~16,000 lines).
- **Phase gate:** full suite green + `lintDebug` clean, per the phase's own Success Criterion 4.

### Wave 0 Gaps
- No new test framework or fixture files are needed — every extension point (`FakeUmbraLogger`, `RecordingRelayRepository`, `EventIngestCacheTest`'s harness, `TestKeypair`/`buildHarness()` in `EventRepositoryIngestionIntegrationTest.kt`) already exists.
- LOG-4's new test needs a decision on which file it lands in (`UserRepositoryImpl` doesn't currently have its own dedicated test file for `saveRelayList()` — check for one before assuming it needs to be created from scratch).
- LOG-12's new test needs the same check against `UmbraNostrClient`'s existing test coverage (none found this session, but a targeted grep for `UmbraNostrClientTest` should be re-run at plan time in case one was added since this research).

## Sources

### Primary (HIGH confidence — read directly this session)
- `docs/KNOWN_ISSUES.md` (all 38 entries, full text, lines 1-932)
- `app/src/test/java/com/umbra/app/util/ImageLoadGateTest.kt`
- `app/src/test/java/com/umbra/app/data/repository/cache/EventLruCacheTest.kt`
- `app/src/test/java/com/umbra/app/ui/common/FutureEventRecheckTickerTest.kt`
- `app/src/test/java/com/umbra/app/data/repository/EventRepositoryIngestionIntegrationTest.kt` (lines 100-830)
- `app/src/test/java/com/umbra/app/domain/model/EventModelBehaviorTest.kt`
- `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` (full file)
- `app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt` (method list)
- `app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt` (method list)
- `app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt` (method list)
- `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt` (full method list)
- `app/src/test/java/com/umbra/app/domain/feed/FeedFilterTest.kt` (method list)
- `app/src/test/java/com/umbra/app/util/logging/LoggerTest.kt`, `LogScrubberTest.kt` (method lists)
- `app/src/test/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCaseTest.kt` (full file)
- `app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt` (full file)
- `app/src/main/java/com/umbra/app/domain/nip01/Event.kt` (lines 334-351, `isFromFuture`/`isTimestampFromFuture`)
- `app/src/main/java/com/umbra/app/ui/feed/ThreadViewModel.kt`, `ui/profile/ProfileViewModel.kt` (grep confirming ticker/tolerance call sites)
- `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt` (constructor, `saveRelay`/`enforceAnonymousRelayPolicyIfNeeded`, logger field)
- `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt` (`saveRelay`, `isDiscovered` sites)
- `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt` (constructor)
- `app/src/main/java/com/umbra/app/domain/usecase/BootstrapOwnProfileUseCase.kt`, `data/tor/TorRuntimeManager.kt`, `data/db/... BackfillAnchorStore.kt` (interface-vs-concrete check)
- `app/src/main/java/com/umbra/app/util/logging/UmbraLog.kt`, `Logger.kt` (the `isLoggable`-gated, non-mockable logging architecture)
- `app/src/main/java/com/umbra/app/domain/usecase/LogoutUseCase.kt` (full file)
- `app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt`, `data/repository/NegentropySyncOrchestrator.kt`, `ui/auth/LoginViewModel.kt`, `ui/common/InteractionActionsCoordinator.kt` (logger field — internal `UmbraLog.tag` vs. injected, grep-confirmed)
- `app/src/test/java/com/umbra/app/testutil/fakes/FakeUmbraLogger.kt` (full file)
- `.planning/codebase/TESTING.md`, `.planning/phases/03-fix-validation-test-coverage/03-CONTEXT.md`, `.planning/REQUIREMENTS.md`, `.planning/STATE.md`

### Secondary (MEDIUM confidence)
- None used — every claim above traces to a file opened this session.

### Tertiary (LOW confidence)
- Assumption A3 (Android's `isReturnDefaultValues=true` semantics for `Log.isLoggable`) — standard AGP behavior, not verified by running a probe test this session. See Assumptions Log.

## Metadata

**Confidence breakdown:**
- Existing-test classification (DONE/PARTIAL/NEW verdicts): HIGH — every verdict traces to an opened test file's actual method list and, where relevant, the fix's actual source location.
- Architectural blockers (Groups C and D): HIGH for the pattern identification (grep-confirmed across all 6 files), MEDIUM for the practical implication (untested claim that this makes the classes fully un-instrumentable — see A1/A2/A3).
- Root-cause groupings: HIGH — derived directly from shared file paths already read.

**Research date:** 2026-09-04
**Valid until:** Should be re-verified if any of the cited test files are modified before this phase executes (the codebase is under active development in the same session lineage) — 7 days is a reasonable ceiling given the fast-moving KNOWN_ISSUES.md/DONE.md churn visible in STATE.md's decision log.
