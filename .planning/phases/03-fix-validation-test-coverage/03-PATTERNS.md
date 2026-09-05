# Phase 3: Fix Validation & Test Coverage - Pattern Map

**Mapped:** 2026-09-04
**Files analyzed:** 9 test-file targets (new or extended) + 3 doc-bookkeeping targets
**Analogs found:** 9 / 9 test targets have a strong analog; doc targets have no code analog (bookkeeping only)

This phase writes/extends JUnit tests only — no new production files. "File Classification"
below covers every test file the planner will assign to a plan, grouped by the RESEARCH.md
root-cause groups so the planner can batch them the same way.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `RelayCrudCoordinatorTest.kt` (extend: LOG-14, LOG-37, LOG-42) | test (ViewModel/coordinator, concurrency) | event-driven / CRUD race | itself (existing file, extend in place) | exact — same file, same harness |
| `UserRepositoryImplTest.kt` (new, LOG-4) | test (repository, concurrency) | CRUD race (TOCTOU) | `EventIngestCacheTest.kt` (8-way overlapping-coroutine pattern) | role-match (repository vs cache, same race-testing shape) |
| `UmbraNostrClientTest.kt` (new, LOG-12) | test (client/service, concurrency) | event-driven race (dedup dial) | `EventIngestCacheTest.kt` + `RelayCrudCoordinatorTest.kt`'s `gateInvocation` idea | role-match |
| `BackfillDeleteLogoutUseCaseTest.kt` (extend, LOG-27 — `LogoutUseCase` 6 remaining catch sites) | test (use case, error handling) | request-response / batch cleanup | itself (existing file, extend in place using `ThrowingClearAllDataEventRepository`-style wrapper per step) | exact |
| `TrimMemoryCachesUseCaseTest.kt` (extend, LOG-27 — `TrimMemoryCachesUseCase` 4 remaining catch sites) | test (use case, error handling) | request-response / batch cleanup | itself (existing file, extend in place using `RecordingXRepository`-style wrapper per step) | exact |
| `EventModelBehaviorTest.kt` (extend, LOG-7 default-tolerance case) | test (domain model, pure logic) | transform | itself (existing file, extend in place) | exact |
| `docs/KNOWN_ISSUES.md` (edit, all 38 entries) | docs/process | batch bookkeeping | n/a | no code analog — follow existing entry template in the file itself |
| `docs/DONE.md` (edit, append validated entries) | docs/process | batch bookkeeping | n/a | no code analog — append-only, follow existing `**Validated:**` convention |
| `.planning/REQUIREMENTS.md` (edit, add VALID-11..38) | docs/process | batch bookkeeping | n/a | no code analog — follow existing `VALID-NN` table row convention |

**Wave 0 gap confirmed this session:** no `UserRepositoryImplTest.kt` or `UmbraNostrClientTest.kt`
exists anywhere under `app/src/test/` (grep re-run, zero hits) — both are genuinely new files, not
extensions.

## Pattern Assignments

### `RelayCrudCoordinatorTest.kt` (extend for LOG-14, LOG-37, LOG-42)

**Analog:** itself — `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` (235 lines, full file already read)

This is the single best-instrumented class in the whole 38-entry set. All three of LOG-14/37/42
extend the same file using its existing `RecordingRelayRepository` fake and `gateInvocation`
mechanism — no new harness needed.

**Imports pattern** (lines 1-23): plain JUnit 4 (`org.junit.Assert.*`, `org.junit.Test`),
`kotlinx.coroutines.test.{runTest, advanceUntilIdle}`, `kotlinx.coroutines.CompletableDeferred`,
project fakes (`FakeEventRepository`, `FakeUserPreferences`) from `testutil.fakes`.

**Core "force a real race" pattern** (lines 156-181, `given two overlapping role toggles...`):
```kotlin
val gate = repository.gateInvocation(0) // holds the first updateRelay call open

coordinator.setOutboxEnabled("relayA", enabled = true)
coordinator.setSearchEnabled("relayA", enabled = true)
advanceUntilIdle()

assertEquals(listOf("enter:relayA"), repository.callLog) // second call blocked by the Mutex

gate.complete(Unit)
advanceUntilIdle()
// both role flags now set -- neither update lost
```
For **LOG-14**: no gate needed — cheap new case asserting `saveRelay(...)`'s persisted result has
`isDiscovered == false` when it should be forced, using `subject()`/`RecordingRelayRepository`
directly (no concurrency needed, unlike LOG-37/42).
For **LOG-37**: race `coordinator.removeRelayRole(...)` against a `set*Enabled` call on the same
relay id, gating invocation 0 exactly as above, asserting neither the removal nor the flag flip is
lost.
For **LOG-42**: race `coordinator.saveRelay(...)`/`deleteRelay(...)` against
`updateRelayRole(...)` on the same relay id, same gate mechanism.

**Fake/recording-class shape** (lines 77-113, `RecordingRelayRepository`): a `MutableMap`-backed
fake implementing only the `RelayRepository` members the coordinator actually calls; `callLog`
records `"enter:<id>"`/`"exit:<id>"` markers around `updateRelay`; `gateInvocation(index)` returns
a `CompletableDeferred<Unit>` that a test can `.complete(Unit)` later to release a held call. Reuse
verbatim — do not build a new fake per new test case.

**Naming convention** (all `@Test` methods): backtick given/when/then, e.g.
`` `given two overlapping role toggles on the same relay when both resolve then neither update is lost` ``.

---

### `UserRepositoryImplTest.kt` (new — LOG-4 relay-list save TOCTOU race)

**Analog:** `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt:365-391`
(D-02-grade real-race pattern) + `RelayCrudCoordinatorTest.kt`'s per-file structure for how a new
test file targeting a repository impl should be organized (`subject()` factory, nested/adjacent
fakes, no shared mutable state between tests).

**Real-race pattern to copy** (`EventIngestCacheTest.kt:365-391`):
```kotlin
@Test
fun `given eight overlapping coroutines calling scheduleSnapshotEmit concurrently when time advances then exactly one snapshot and one bundle emission occur with no event lost`() = runTest {
    val cache = subject(this)
    val snapshots = mutableListOf<List<Event>>()
    ...
    val events = (1..8).map { textNote("concurrent-$it") }
    events.forEach { cache.ingest(it, relayA, currentUserPubkey = null) }
    events.forEach { event ->
        launch {
            cache.enqueueSnapshotEvent(event)
            cache.scheduleSnapshotEmit()
        }
    }
    advanceTimeBy(300L)
    advanceUntilIdle()

    assertEquals(1, snapshots.size)
    ...
}
```
For LOG-4: launch N real coroutines (`launch { ... }` inside `runTest`, not sequential calls) each
calling `saveRelayList()`/whatever `UserRepositoryImpl` method wraps the `ConcurrentHashMap.compute()`
TOCTOU fix, with distinct relay entries, `advanceUntilIdle()`, then assert the final persisted map
contains every entry with none dropped/overwritten — matching D-02's explicit rejection of
"simpler sequential-call behavioral tests."

**File organization to copy:** `RelayCrudCoordinatorTest.kt`'s shape — a private `subject()`
factory building the class under test with fakes, plain JUnit `@Test` methods with backtick
given/when/then names, no mocking framework, no `@Before`/shared mutable fields (each test builds
its own subject).

---

### `UmbraNostrClientTest.kt` (new — LOG-12 same-relay concurrent dial race)

**Analog:** same two files as `UserRepositoryImplTest.kt` above (`EventIngestCacheTest.kt`'s
overlapping-`launch` real-race shape; `RelayCrudCoordinatorTest.kt`'s `gateInvocation` idea if the
dial path has an awaitable choke point worth gating deterministically instead of relying purely on
`advanceUntilIdle()`).

**Core pattern:** launch N real coroutines calling the dial path (whatever method guards on
`dialingRelays`) for the *same* relay id concurrently, then assert exactly one actual dial/connect
call occurred (not N) — this is the `onWebSocketOpen` identity-check fix's actual regression
surface named in RESEARCH.md. If a fake WebSocket/connector already exists elsewhere in
`testutil/fakes/`, reuse it rather than hand-rolling a new one; check before writing.

---

### `BackfillDeleteLogoutUseCaseTest.kt` (extend — LOG-27, `LogoutUseCase`'s remaining 6 catch sites)

**Analog:** itself, lines 362-387 (existing, sufficient case for the 1-of-7 site already covered).

**Injectable-logger assertion pattern to replicate per remaining site** (lines 362-387):
```kotlin
val thrown = IllegalStateException("clear all data boom")
val repo = ThrowingClearAllDataEventRepository(FakeEventRepository(oldestAuthorTimestamp = null), thrown)
val logger = FakeUmbraLogger()
val useCase = LogoutUseCase(repo, userRepo, prefs, FakeContactListRepository(), FakeMuteListRepository(), FakePinListRepository(), FakeNostrSessionController(), logger)

useCase()

assertEquals(1, logger.errorCalls.size)
assertSame(thrown, logger.errorCalls.first().throwable)
```
**Throwing-wrapper pattern to replicate per site** (lines 393-405):
```kotlin
private class ThrowingClearAllDataEventRepository(
    delegate: EventRepository,
    private val thrown: Throwable
) : EventRepository by delegate {
    override suspend fun clearAllData() {
        throw thrown
    }
}
```
Per RESEARCH.md's Code Examples section, write one such `Throwing*` wrapper per remaining step:
`nostrSessionController.stop()`, `userRepository.clearAll()`, `contactListRepository.clearAll()`,
`muteListRepository.clearAll()`, `pinListRepository.clearAll()`,
`eventRepository.clearBackfillAnchors(pubkey)` — 6 wrappers total, each delegating via `by delegate`
to the corresponding `Fake*Repository`/`FakeNostrSessionController` and overriding only the one
throwing method, mirroring `ThrowingClearAllDataEventRepository`'s shape exactly.

**Pitfall to avoid (RESEARCH.md Pitfall 3):** do not write a "still runs the remaining steps
anyway" test as if it proves the fix — that behavior (best-effort continue-on-failure) predates
the logging fix. Only `logger.errorCalls` assertions (as above) actually pin what changed.

---

### `TrimMemoryCachesUseCaseTest.kt` (extend — LOG-27, `TrimMemoryCachesUseCase`'s remaining 4 catch sites)

**Analog:** itself, full file already read (148 lines).

**Recording-wrapper pattern to replicate per remaining site** (lines 25-80):
```kotlin
private class RecordingUserRepository(
    delegate: UserRepository
) : UserRepository by delegate {
    var pruneStaleDataCalls = 0
        private set

    override suspend fun pruneStaleData() {
        pruneStaleDataCalls++
    }
}
```
Same shape already exists for `contactListRepository.trimMemory()`, `muteListRepository.trimMemory()`,
`pinListRepository.trimMemory()` (lines 49-80) — these four are the exact remaining sites RESEARCH.md
names. If a `RecordingXRepository` for a given site already exists in the file (check before
adding), extend its use in a new `@Test` following the existing
`` `given_eventRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable` ``
case (lines 85-102):
```kotlin
val thrown = IllegalStateException("trim boom")
val eventRepository = RecordingEventRepository(FakeEventRepository(), trimMemoryThrows = thrown)
val logger = FakeUmbraLogger()
val useCase = TrimMemoryCachesUseCase(eventRepository, FakeUserRepository(), FakeContactListRepository(), FakeMuteListRepository(), FakePinListRepository(), logger)

useCase(aggressive = false)

assertEquals(1, logger.errorCalls.size)
assertSame(thrown, logger.errorCalls.first().throwable)
```
**Naming convention in this file specifically:** underscore-joined given/when/then
(`` `given_X_when_Y_then_Z` ``), distinct from most of the codebase's space-separated backtick
style — match this file's own existing convention when extending it, not the space-separated one.

---

### `EventModelBehaviorTest.kt` (extend — LOG-7's zero-tolerance default-parameter case)

**Analog:** itself, lines 185-207 (existing parametric `isFromFuture`/`isTimestampFromFuture` cases).

**Existing parametric pattern** (lines 185-195):
```kotlin
assertFalse(now.isFromFuture(tolerance))
assertFalse(justPast.isFromFuture(tolerance))
assertFalse(justInsideTolerance.isFromFuture(tolerance))
assertTrue(justBeyondTolerance.isFromFuture(tolerance))
assertTrue(farFuture.isFromFuture(tolerance))
```
Add one new case that calls `Event(createdAt = now + 1).isFromFuture()` with **no explicit
tolerance argument** (using the production default parameter, currently `toleranceSeconds: Long =
0L`), asserting `true` — this is what pins the actual hardcoded-zero product decision that the
existing parametric tests (which always pass an explicit `tolerance`) don't cover. Per
RESEARCH.md Open Question 3, this is the recommended minimum; the full `ProfileViewModel`/
`ThreadViewModel` wiring-level gap is left to planning discretion.

---

## Shared Patterns

### No-mocking-framework, manual-fake convention
**Source:** `.planning/codebase/TESTING.md` (project-wide), exemplified in every analog above.
**Apply to:** every test file this phase touches. JUnit 4 only (`org.junit.Assert.*`,
`org.junit.Test`), no Mockito/MockK. Dependencies are either an existing `Fake*` from
`app/src/test/java/com/umbra/app/testutil/fakes/`, or a small `private class Recording*`/`Throwing*`
wrapper implemented via Kotlin interface delegation (`: SomeInterface by delegate`) overriding only
the one method under test — never a full hand-rolled reimplementation of the interface.

### Recording logger fake (LOG-27's two use-case extensions)
**Source:** `app/src/test/java/com/umbra/app/testutil/fakes/FakeUmbraLogger.kt` (full file, 29 lines)
```kotlin
class FakeUmbraLogger : UmbraLogger {
    data class Call(val level: String, val throwable: Throwable?, val message: String)
    private val recordedCalls = mutableListOf<Call>()
    val calls: List<Call> get() = recordedCalls
    val errorCalls: List<Call> get() = recordedCalls.filter { it.level == "e" }
    override fun d(message: () -> String) { recordedCalls += Call("d", null, message()) }
    override fun w(message: () -> String) { recordedCalls += Call("w", null, message()) }
    override fun e(throwable: Throwable, message: () -> String) { recordedCalls += Call("e", throwable, message()) }
}
```
**Apply to:** any class taking `logger: UmbraLogger` as a **constructor parameter** — `LogoutUseCase`,
`TrimMemoryCachesUseCase`. Does **not** apply to Group D's six classes (`EventRepositoryImpl`,
`NegentropySyncOrchestrator`, `LoginViewModel`, `RelayConfigViewModel`, `NostrSessionManager`,
`InteractionActionsCoordinator`) — those use a non-injected `private val logger = UmbraLog.tag(TAG)`
field and have no test seam for this fake at all (see RESEARCH.md Critical Finding 2). No test file
in this phase should attempt to inject `FakeUmbraLogger` into any of those six.

### Real-concurrency race-forcing (`TestDispatcher`/`runTest`)
**Source:** `EventIngestCacheTest.kt:365-391` (see excerpt above); also
`RelayCrudCoordinatorTest.kt:156-207`'s `gateInvocation`/`CompletableDeferred` variant for
deterministic (rather than merely "many launches") overlap.
**Apply to:** LOG-4, LOG-12 (new files) and LOG-37, LOG-42 (extend `RelayCrudCoordinatorTest.kt`) —
every D-02-designated entry. Always prefer genuinely overlapping `launch { }` coroutines plus
`advanceUntilIdle()`/`advanceTimeBy()` over sequential calls asserting only final state — RESEARCH.md
and CONTEXT.md D-02 both explicitly reject the sequential-call shortcut.

### Bug-tracker bookkeeping template
**Source:** `docs/KNOWN_ISSUES.md` and `docs/DONE.md` themselves (existing entries), plus
`.claude/CLAUDE.md`'s "Bug tracking" section.
**Apply to:** all 38 entries. A validated entry moves **verbatim** from `KNOWN_ISSUES.md` to
`DONE.md` with only a `**Validated:** <date>` line appended — no rewriting the description. For the
Group C/D "no test seam" entries, RESEARCH.md's Open Questions 1-2 recommend a third disposition
(stays in `KNOWN_ISSUES.md` with an explicit "verified by direct source read, not by a test or an
emulator" rationale line) rather than forcing a binary DONE/eyeball choice — planner must resolve
this with the user before writing the bookkeeping task.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `docs/KNOWN_ISSUES.md` / `docs/DONE.md` / `.planning/REQUIREMENTS.md` edits | docs/process | batch bookkeeping | Not code; no analog needed — follow each file's own existing entry/row template, cited in `.claude/CLAUDE.md` and the files themselves. |
| Any Group C (`NostrSessionManager`) or Group D (non-injected-logger) test attempt | test | n/a | RESEARCH.md establishes these are structurally blocked/unassertable under current architecture (no interface seam, no injectable logger) — do not attempt to force a test; use the source-read-citation disposition instead once the user resolves Open Questions 1-2. |

## Metadata

**Analog search scope:** `app/src/test/java/com/umbra/app/{data/repository,ui/relay,ui/common,
domain/usecase,domain/model,util/logging,data/nostr,testutil/fakes}` (all paths cited in
RESEARCH.md's Sources section; re-verified this session with targeted `Read`/`Bash find` calls,
no re-reads of already-loaded ranges).
**Files scanned:** 6 fully or near-fully read this session (`RelayCrudCoordinatorTest.kt` full,
`EventIngestCacheTest.kt` lines 340-420, `FakeUmbraLogger.kt` full, `BackfillDeleteLogoutUseCaseTest.kt`
lines 1-60 + 340-405, `TrimMemoryCachesUseCaseTest.kt` full, `EventModelBehaviorTest.kt` targeted
grep + lines 185-207); plus a `find` confirming no `UserRepositoryImplTest.kt`/`UmbraNostrClientTest.kt`
exist yet.
**Pattern extraction date:** 2026-09-04
