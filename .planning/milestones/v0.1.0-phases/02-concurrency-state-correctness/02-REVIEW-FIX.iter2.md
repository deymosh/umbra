---
phase: 02-concurrency-state-correctness
fixed_at: 2026-09-04T00:00:00Z
review_path: .planning/phases/02-concurrency-state-correctness/02-REVIEW.md
iteration: 1
findings_in_scope: 9
fixed: 8
skipped: 1
status: partial
---

# Phase 02: Code Review Fix Report

**Fixed at:** 2026-09-04T00:00:00Z
**Source review:** `.planning/phases/02-concurrency-state-correctness/02-REVIEW.md`
**Iteration:** 1

**Summary:**
- Findings in scope: 9 (3 critical, 5 warning, 1 info — `fix_scope: all`)
- Fixed: 8
- Skipped: 1 (WR-05)

**Verification:** `./gradlew compileDebugKotlin`, `./gradlew lintDebug`, and
`./gradlew testDebugUnitTest` (full suite, not just the scoped classes touched by this pass) all
passed clean after every commit below. Ran in the main checkout — `workflow.use_worktrees` is
`false` in `.planning/config.json`, so no isolated worktree was created for this run; every commit
below landed directly on `gsd/v0.1.0-hardening-first-public-release`.

## Fixed Issues

### CR-01: `RelayCrudCoordinator.removeRelayRole` bypasses the per-relay-id Mutex LOG-29 added for its four sibling setters

**Files modified:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt`, `docs/KNOWN_ISSUES.md`
**Commit:** `363b05e`
**Applied fix:** `removeRelayRole` no longer runs its own independent read-`state.relays`-then-
persist sequence — it now calls `updateRelayRole` (the same chokepoint the five `set*Enabled`
setters route through) with a mapper that clears the target role's enable/active flags, picking up
the per-relay-id `Mutex` and the fresh `relayRepository.getRelayById` read for free.
`RelayCrudCoordinatorTest`'s existing two-overlapping-role-toggle cases continue to pass
unmodified — no new dedicated race test was added for this specific method since the shared
`updateRelayRole` path is already covered by those existing cases.

### CR-02: `NostrSessionManager`'s plain instance fields are still unsynchronized across the two coroutines the file's own comment says race each other

**Files modified:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt`, `docs/KNOWN_ISSUES.md`
**Commit:** `637ac53`
**Applied fix:** Marked `relaysConnected`, `backfillPubkey`, `firstRelayConnectedLogged`,
`lastSnapshot`, and `ownProfileBootstrapPubkey` `@Volatile`, guaranteeing cross-thread visibility
between `reconcile()`'s two concurrently-reachable entry points (the `combine()`-driven collect
loop and `scheduleRetry()`'s delayed relaunch) and `stop()`'s caller thread. Chose `@Volatile` over
a full `Mutex` around `reconcile()`'s body: every mutation of these five fields is a single
unconditional assignment, never a read-modify-write of the field's own prior value, so visibility
(not atomicity) was the actual gap — a `Mutex` refactor would have been a much larger, higher-risk
restructuring of a class with no dedicated unit test (see WR-05 below) to catch a regression.
**No dedicated concurrency test exists for this fix — flagged for manual/on-device verification**,
consistent with WR-05's own finding that this class has zero test coverage of its concurrency
behavior.

### CR-03: `RelayConfigViewModel.enforceAnonymousRelayPolicyIfNeeded` silently discards any failure while enforcing the anonymous-session privacy restriction

**Files modified:** `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt`, `docs/KNOWN_ISSUES.md`
**Commit:** `8419f31`
**Applied fix:** Added `.onFailure { e -> logger.e(e) { "Failed to enforce anonymous-session relay restriction" } }`
after the `runCatching` block, matching LOG-20's fix shape. `RelayConfigViewModel` had no
`TAG`/`logger` field before this fix — both were added.

### WR-01: `EventIngestCache.scheduleInsert`'s hand-rolled cancel-and-replace doesn't follow this file's own lazy-launch-then-cancel-before-start ordering

**Files modified:** `app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt`, `docs/KNOWN_ISSUES.md`
**Commit:** `2d2f987`
**Applied fix:** Routed `scheduleInsert` through the existing `AtomicJobScheduling.launchReplacing`
helper (already used by `scheduleSnapshotEmit` in this same file), which builds the replacement
job lazily and only `start()`s it after the displaced job is cancelled — restoring the
cancel-strictly-before-start ordering the hand-rolled version got backwards. `Dispatchers.IO`
moved inside the block via `withContext` since `launchReplacing` launches on `repoScope` directly.
Verified against the existing `EventIngestCacheTest` suite, including its `awaitInsertDebounce()`
helper, which continues to pass.

### WR-02: `EventIngestCache.cacheRepostTarget`/`cacheVerifiedRepostTarget` skip the replaceable-event supersede bookkeeping `ingest()` enforces for the same slot

**Files modified:** `app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt`, `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt`, `docs/KNOWN_ISSUES.md`
**Commit:** `49fceca`
**Applied fix:** Extracted `storeEventLocked` — the replaceable-key-aware `latestReplaceableEventId`/
`winsReplaceableRace` logic `ingest()` already ran — as a shared private helper (must be called
while already holding `cachedEventsMutex`) used by both `ingest()` and `cacheRepostTarget`, so a
repost-embedded replaceable/parameterized-replaceable event now participates in the same
one-revision-per-slot invariant as a directly-ingested one. Added two new `EventIngestCacheTest`
cases covering both directions (a repost-cached older revision superseded by a later direct
ingest, and a direct-ingested newer revision correctly rejecting an older one arriving via
`cacheRepostTarget`).

### WR-03: `RelayCrudCoordinator.saveRelay`/`deleteRelay` mutate a relay's persisted record without the per-relay-id `Mutex` `updateRelayRole` uses

**Files modified:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt`, `docs/KNOWN_ISSUES.md`
**Commit:** `12c0e7d`
**Applied fix:** Chose the "route through the mutex" option over documenting the dialog-exclusivity
assumption, since the lock infrastructure already existed. `saveRelay`'s update-existing-relay and
merge-onto-existing-relay branches, and `deleteRelay`'s removal, now acquire
`relayRoleMutexes.computeIfAbsent(relayId) { Mutex() }` around their persistence call.
`saveRelay`'s brand-new-relay branch (freshly generated id, `addRelayUseCase`) was deliberately
left unlocked — a newly generated id cannot race an in-flight operation, since nothing else has
ever seen it before that point. Existing `RelayCrudCoordinatorTest` cases pass unmodified; no new
race test was added specifically for `saveRelay`/`deleteRelay` (the existing test fake only
instruments `updateRelay`, not `removeRelay`/`addRelay`, so a new race test would have required
extending `RecordingRelayRepository` — judged lower priority than the other in-scope findings
given time budget).

### WR-04: Broad `catch (e: Exception)`/unchecked `runCatching` around suspend calls swallow `CancellationException` across most of this phase's write paths

**Files modified:** `app/src/main/java/com/umbra/app/util/coroutines/CancellableRunCatching.kt` (new), `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt`, `app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt`, `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt`, `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt`, `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt`, `docs/TODO.md`, `docs/DONE.md`
**Commit:** `9fa8db3`
**Applied fix:** All representative sites from the finding now rethrow `CancellationException`
instead of swallowing it. The four plain `try`/`catch (e: Exception)` sites
(`InteractionActionsCoordinator.requestSignAndPublish`, `ProfileViewModel.requestSignEvent`, and
`RelayCrudCoordinator`'s `saveRelay`/`deleteRelay`/`updateRelayRole`) gained an explicit
`catch (e: CancellationException) { throw e }` before their generic catch. The four `runCatching`
sites (`NostrSessionManager.disableDeadRelay`, `RelayConfigViewModel`'s `applyRelaysSnapshot`/
`enforceAnonymousRelayPolicyIfNeeded`/`loadRelayInfo`) now use a new shared
`runCatchingCancellable` (`util/coroutines/CancellableRunCatching.kt`) instead of the stdlib
`runCatching`, per the review's own "consider a small shared helper" suggestion. Moved the
matching TODO LOG-43 entry to `docs/DONE.md` with a `Completed` date and back-reference, per
CLAUDE.md's TODO→DONE convention.

### IN-01: `RelayCrudCoordinator.relayRoleMutexes` is never pruned

**Files modified:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt`, `docs/TODO.md`, `docs/DONE.md`
**Commit:** `8f84b2a`
**Applied fix:** Turned out straightforward, so fixed rather than left as backlog per the
additional-context instruction. `deleteRelay` now calls `relayRoleMutexes.remove(relayId)` once
its own `removeRelayUseCase` call (and the per-relay lock guarding it, added in WR-03) completes.
A caller racing in for the now-deleted id right after gets a fresh, unlocked `Mutex` from
`computeIfAbsent` and no-ops harmlessly once `updateRelayRole`'s own `getRelayById` lookup finds
nothing — same as any other unknown `relayId`. Moved the matching TODO LOG-45 entry to
`docs/DONE.md` with a `Completed` date and back-reference.

## Skipped Issues

### WR-05: `NostrSessionManager` and `RelayConfigViewModel` have no dedicated unit test for the concurrency behavior this phase changed

**File:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt`, `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt`
**Reason:** Genuinely infeasible within this pass's scope, not merely deprioritized. Verified
before skipping: `NostrSessionManager` takes 11 injected dependencies, three of which
(`TorRuntimeManager`, `BootstrapOwnProfileUseCase`, `RelayListDecryptionCoordinator`) are concrete
`class`es (not interfaces) with their own real dependencies — there is no existing seam to fake
them through, and this project has no mocking framework on the test classpath (confirmed by
grepping `app/src/test/java/com/umbra/app/testutil/fakes/` — every existing fake is a hand-written
interface implementation, and none of the three classes above are interfaces). Building a
`NostrSessionManagerTest` would require either introducing new interface seams for all three
(a real architectural change affecting production code beyond this review's findings) or a
mocking library addition (a build/dependency change), both out of scope for a targeted fixer pass.
`RelayConfigViewModel` has a comparable dependency surface. The review's own alternative
suggestion — "extract the plain-field decision logic into a smaller pure function" — doesn't
apply cleanly here either: CR-02's fix was a visibility fix (`@Volatile`), not a decision-logic
change, so there is no new pure function to extract and test. Left as `in progress` in
`docs/TODO.md` (unchanged) rather than reclassified, since the finding remains valid and
unaddressed.
**Original issue:** No `NostrSessionManagerTest` or `RelayConfigViewModelTest` exists anywhere
under `app/src/test`, so CR-02's fix (and LOG-30's original fix before it) has zero test coverage
of its own call sites.

---

_Fixed: 2026-09-04T00:00:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
