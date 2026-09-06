---
phase: 02-concurrency-state-correctness
reviewed: 2026-09-04T00:00:00Z
depth: standard
files_reviewed: 14
files_reviewed_list:
  - app/src/main/java/com/umbra/app/data/nostr/AtomicJobScheduling.kt
  - app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt
  - app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt
  - app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt
  - app/src/main/java/com/umbra/app/ui/feed/FeedViewModel.kt
  - app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt
  - app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt
  - app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt
  - app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt
  - app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt
  - app/src/test/java/com/umbra/app/domain/feed/FeedFilterTest.kt
  - app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt
  - app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt
  - app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt
findings:
  critical: 3
  warning: 5
  info: 1
  total: 9
status: issues_found
---

# Phase 02: Code Review Report

**Reviewed:** 2026-09-04T00:00:00Z
**Depth:** standard
**Files Reviewed:** 14
**Status:** issues_found

## Summary

`AtomicJobScheduling.kt`'s two extension functions (`launchIfIdle`/`launchReplacing`) are correct and well-tested — the deterministic and genuinely-concurrent (real-thread) test groups in `AtomicJobSchedulingTest` actually exercise the guarantees the doc comments claim, and `EventIngestCacheTest`/`RelayCrudCoordinatorTest` do the same for their respective classes' converted fields. The NIP-09 delete-confirmation ordering (`InteractionActionsCoordinator.deleteEvent`) and the feed mute/pin result-handling fixes (`FeedViewModel.muteUser`/`togglePin` + `muteWriteResultMessage`/`pinWriteResultMessage`) both match what `docs/KNOWN_ISSUES.md` (LOG-22/23/24) claims was fixed, and I could not find a regression in either.

However, the conversion to `AtomicReference`/`Mutex` was applied unevenly within this same phase's own target files: two of the exact classes called out as fixed in `docs/KNOWN_ISSUES.md` (LOG-29 for `RelayCrudCoordinator`, LOG-30 for `NostrSessionManager`) still contain sibling methods or plain fields that share the identical unsynchronized check-then-act shape the fix was supposed to eliminate — `RelayCrudCoordinator.removeRelayRole` never acquired the per-relay-id `Mutex` at all, and `NostrSessionManager`'s plain `var` state (`relaysConnected`, `backfillPubkey`, `firstRelayConnectedLogged`, `lastSnapshot`, `ownProfileBootstrapPubkey`) is still read/written from the two concurrently-reachable coroutines the file's own comment acknowledges exist. Neither of these two classes has any dedicated unit test, unlike every other converted class in this phase, which is likely why both slipped through. A separate, unrelated privacy-relevant silent-failure gap was also found in `RelayConfigViewModel.enforceAnonymousRelayPolicyIfNeeded`.

## Critical Issues

### CR-01: `RelayCrudCoordinator.removeRelayRole` bypasses the per-relay-id Mutex LOG-29 added for its four sibling setters, reintroducing the identical lost-update race

**File:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt:174-220`

**Issue:** `docs/KNOWN_ISSUES.md`'s LOG-29 entry documents fixing exactly this shape of bug — a check-then-act (`read state.value.relays` → `compute updated copy` → `updateRelayUseCase(updated)`) with no lock — for `setOutboxEnabled`/`setInboxEnabled`/`setDmEnabled`/`setSearchEnabled`/`setIndexEnabled`, all of which now route through `updateRelayRole`, which acquires `relayRoleMutexes[relayId]` and re-reads the relay fresh from `relayRepository.getRelayById(relayId)` rather than the 300ms-throttled `state.value.relays` mirror (per `updateRelayRole`'s own comment at line ~326, explaining why the lock alone isn't sufficient).

`removeRelayRole` is functionally a sixth role-mutating setter (it clears one role's enable/active flags, exactly like the other five clear-or-set) but was never folded into `updateRelayRole`. It has its own independent sequence:

```kotlin
fun removeRelayRole(relayId: String, role: RelayRole) {
    scope.launch {
        val relay = state.value.relays.find { it.id == relayId } ?: return@launch
        ...
        val updatedRelay = when (role) { ... }.let { it.copy(isEnabled = it.hasAnyActiveRole()) }
        state.update { ... }
        try {
            updateRelayUseCase(updatedRelay)
            ...
        } catch (e: Exception) { ... }
    }
}
```

It (a) reads from the stale `state.value.relays` mirror instead of `relayRepository.getRelayById`, and (b) acquires no `Mutex` at all. A `removeRelayRole` call racing against any of the five `updateRelayRole`-routed setters — or against a second `removeRelayRole` call — for the *same* relay id can silently lose one write, exactly the bug LOG-29 fixed for the other five methods. This is realistic: `RelayDetailsScreen` exposes both role-remove actions and role-toggle switches for the same relay in the same view.

**Fix:** Route `removeRelayRole` through `updateRelayRole` (or an equivalent per-relay-`Mutex` + fresh-`getRelayById` sequence), e.g.:

```kotlin
fun removeRelayRole(relayId: String, role: RelayRole) {
    if ((role == RelayRole.INBOX || role == RelayRole.DM) && userPreferences.isAnonymousSession()) {
        state.update { it.copy(errorMessage = UiMessage.Res(
            if (role == RelayRole.INBOX) R.string.error_inbox_anonymous_disabled else R.string.error_dm_anonymous_disabled
        )) }
        return
    }
    state.update {
        when (role) {
            RelayRole.OUTBOX, RelayRole.INBOX -> it.copy(relayListDirty = true)
            RelayRole.DM -> it.copy(dmRelayListDirty = true)
            RelayRole.SEARCH -> it.copy(searchListDirty = true)
            RelayRole.INDEX -> it.copy(indexListDirty = true)
        }
    }
    updateRelayRole(relayId) { relay ->
        when (role) {
            RelayRole.OUTBOX -> relay.copy(isWriteEnabled = false, isWriteActive = false)
            RelayRole.INBOX -> relay.copy(isReadEnabled = false, isReadActive = false)
            RelayRole.DM -> relay.copy(isDmEnabled = false, isDmActive = false, dmRequiresAuth = false)
            RelayRole.SEARCH -> relay.copy(isSearchEnabled = false, isSearchActive = false)
            RelayRole.INDEX -> relay.copy(isIndexEnabled = false, isIndexActive = false)
        }.let { it.copy(isEnabled = it.hasAnyActiveRole()) }
    }
}
```

Add a `RelayCrudCoordinatorTest` case mirroring the existing "two overlapping role toggles on the same relay" test, but racing `removeRelayRole` against a setter.

### CR-02: `NostrSessionManager`'s plain instance fields are still unsynchronized across the two coroutines the file's own comment says race each other

**File:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt:150-171` (field declarations), `:311-398` (`reconcile`), `:449-460` (`startUserHistoryBackfill` head), `:596-603` (`scheduleRetry`), `:271-287` (`stop`)

**Issue:** The comment above the field block explicitly states:

> "neither field is ever read or reassigned from `reconcile()`'s concurrently-reachable paths (**the combine()-driven collect and retryJob's own delayed relaunch**)"

This confirms two genuinely concurrent entry points into `reconcile()` exist on `scope` (`CoroutineScope(SupervisorJob() + Dispatchers.IO)` — a real, multi-threaded dispatcher, not confined to one thread): the `bootstrapJob`'s `combine(...).collect { state -> reconcile(state, previous) }` loop, and `scheduleRetry()`'s own `retryJob.launchIfIdle(scope) { delay(...); reconcile(snapshot, snapshot) }`. The LOG-30 fix wrapped `retryJob`/`userBackfillJob`/`ownProfileBootstrapWatcherJob` in `AtomicReference<Job?>`, which correctly prevents *double-scheduling* the same job slot — but `reconcile()`'s body itself reads and writes several plain, non-`@Volatile`, non-atomic fields that are reachable from both of those concurrent paths:

- `relaysConnected` (read + written throughout `reconcile()`)
- `backfillPubkey` (read + written in `startUserHistoryBackfill`, called from `reconcile()`)
- `firstRelayConnectedLogged` (read + written in `reconcile()`'s success branch)
- `lastSnapshot` (written by the main collect loop after every `reconcile()` call; read directly by `scheduleRetry()`'s own job body and by `torCircuitRecoveryJob`'s collector)
- `ownProfileBootstrapPubkey` (read + written in `maybeBootstrapOwnProfile`/`stopOwnProfileBootstrap`, both reachable from `reconcile()`)

None of these are protected by a `Mutex`, `AtomicReference`, or `@Volatile`. Two `reconcile()` invocations racing on different threads (e.g. a relay-set change lands from the `combine()` flow at the same moment an 8-second retry fires) can interleave reads/writes of these fields — e.g. `relaysConnected` flips to `false` from the retry path just after the main path set it `true`, or `backfillPubkey` gets clobbered mid-check in `startUserHistoryBackfill`'s `if (userBackfillJob.get()?.isActive == true && backfillPubkey == normalized && !resyncFromNow) return` guard, causing a spurious duplicate backfill restart or a missed one. `stop()` compounds this: it mutates several of the same fields (`backfillPubkey = null`, `relaysConnected = false`) from whatever thread calls it (not necessarily `scope`'s dispatcher), and only *requests* cancellation (`bootstrapJob?.cancel()`) rather than joining — leaving a window where an in-flight `reconcile()` on `scope` can still be executing concurrently with `stop()`'s own field writes.

This is precisely the class of bug LOG-30 set out to close (and did close for the `Job` references), left half-done for the plain state the same functions mutate.

**Fix:** Either (a) confine all `reconcile()`-reachable mutable state behind a single `Mutex` acquired for the duration of each `reconcile()`/`scheduleRetry()`-retry/`stop()` field-touching section, or (b) migrate the listed fields to `AtomicReference`/`@Volatile` with compare-and-set semantics matching the `AtomicJobScheduling.kt` pattern already established in this same file for the `Job` fields. At minimum, `lastSnapshot` needs `@Volatile` for cross-thread visibility since it is written on one thread and read on two others.

### CR-03: `RelayConfigViewModel.enforceAnonymousRelayPolicyIfNeeded` silently discards any failure while enforcing the anonymous-session privacy restriction

**File:** `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt:346-367`

**Issue:**

```kotlin
private fun enforceAnonymousRelayPolicyIfNeeded(relays: List<Relay>) {
    if (!userPreferences.isAnonymousSession()) return

    relays.asSequence()
        .filter { it.isReadEnabled || it.isDmEnabled }
        .forEach { relay ->
            viewModelScope.launch {
                runCatching {
                    updateRelayUseCase(
                        relay.copy(
                            isReadEnabled = false, isReadActive = false,
                            isDmEnabled = false, isDmActive = false, dmRequiresAuth = false,
                            isEnabled = relay.isWriteActive
                        )
                    )
                }
            }
        }
}
```

This is the mechanism that turns off read/DM relay roles for an anonymous session — a genuine privacy control (Umbra's anonymous mode is meant to prevent read/DM relay usage from being tied to an identity). The `runCatching { ... }` result is never inspected — no `.onFailure { }`, no logging, nothing. If `updateRelayUseCase` throws for any reason (Room write failure, cancellation, anything), the anonymous-session restriction silently fails to apply for that relay, with zero diagnostic trail, and the caller has no way to know the enforcement didn't take effect. This is the same class of "silent catch on a privacy-relevant path" bug already logged and fixed at LOG-20/LOG-27/LOG-28 elsewhere in this codebase, but this specific site is not covered by any of those fixes and is not in `docs/KNOWN_ISSUES.md`.

**Fix:** Log the failure (scrubbed, since `relay.url` shouldn't reach logs raw) and consider surfacing a user-visible error, matching LOG-20's fix shape:

```kotlin
viewModelScope.launch {
    runCatching { updateRelayUseCase(relay.copy(...)) }
        .onFailure { e ->
            logger.e(e) { "Failed to enforce anonymous-session relay restriction" }
        }
}
```

## Warnings

### WR-01: `EventIngestCache.scheduleInsert`'s hand-rolled cancel-and-replace doesn't follow this file's own lazy-launch-then-cancel-before-start ordering

**File:** `app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt:513-531`

**Issue:** `AtomicJobScheduling.launchReplacing`'s doc comment (and `scheduleSnapshotEmit`'s own CAS-based implementation in this same file) both go out of their way to guarantee the *old* job is cancelled strictly before the *new* one starts running, specifically to avoid two overlapping bodies executing concurrently. `scheduleInsert` implements the same "cancel-and-replace one debounce job" shape manually, but gets the ordering backwards:

```kotlin
val newJob = repoScope.launch(Dispatchers.IO) {   // starts running immediately (not LAZY)
    delay(INSERT_DEBOUNCE_MS)
    ...
}
insertDebounceJob.getAndSet(newJob)?.cancel()      // old job cancelled AFTER the new one is already running
```

Because `launch` here uses the default (eager) start, the new debounce job's 200ms delay begins before the previous job is cancelled, so there is a — narrow, but real — window where both the superseded and superseding debounce coroutines are simultaneously alive. `ConcurrentLinkedQueue.poll()` prevents this from losing data (each drains whatever's left), but it can produce two separate `ownEventArchive.writeBatch()` transactions instead of the intended one coalesced batch, defeating the debounce's purpose under a tight burst.

**Fix:** Use the same `launchReplacing` helper this file already imports the pattern from (adjusted for the `Dispatchers.IO` context), or manually cancel-then-lazily-start to match:

```kotlin
insertDebounceJob.launchReplacing(repoScope) {
    withContext(Dispatchers.IO) {
        delay(INSERT_DEBOUNCE_MS)
        val batch = buildList { while (pendingInserts.isNotEmpty()) pendingInserts.poll()?.let { add(it) } }
        if (batch.isNotEmpty()) ownEventArchive.writeBatch(batch)
    }
}
```

### WR-02: `EventIngestCache.cacheRepostTarget`/`cacheVerifiedRepostTarget` skip the replaceable-event supersede bookkeeping `ingest()` enforces for the same slot

**File:** `app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt:278-283`, `:492-498`

**Issue:** `ingest()` maintains `latestReplaceableEventId` and runs `winsReplaceableRace()` so only one revision per `ReplaceableEventKey` slot is ever retrievable (the LOG-1/LOG-6 fix this class's doc comment references). `cacheRepostTarget` — used by NIP-18's `cacheVerifiedRepostTarget` to cache an already-verified repost's embedded original event — bypasses all of that:

```kotlin
suspend fun cacheRepostTarget(target: Event) {
    cachedEventsMutex.withLock {
        cachedEngagementIndex.add(target)
        cachedEvents.put(target)     // id-keyed put only; no replaceableKey()/latestReplaceableEventId update
    }
}
```

If `target` is itself a replaceable or parameterized-replaceable event (e.g. a NIP-18 repost of a long-form article, a list, or a live-status event — all addressable kinds), caching it this way never updates `latestReplaceableEventId`, so a subsequently-ingested direct revision of the same slot won't know about this cached id when computing `supersededId`, and vice versa: an older revision arriving via a repost after a newer one was already ingested directly will get cached under its own id with no race check against the newer one at all, silently coexisting with it (the exact bug LOG-1 was written to close for the direct-ingest path).

**Fix:** Route `cacheRepostTarget` through the same replaceable-key-aware logic `ingest()` uses (or call a shared private helper the two can both use), so a repost-embedded replaceable event participates in the same one-revision-per-slot invariant.

### WR-03: `RelayCrudCoordinator.saveRelay`/`deleteRelay` mutate a relay's persisted record without the per-relay-id `Mutex` `updateRelayRole` uses

**File:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt:51-143` (`saveRelay`), `:145-172` (`deleteRelay`)

**Issue:** `updateRelayRole` (backing the five role setters) serializes writes to a given relay id via `relayRoleMutexes.computeIfAbsent(relayId) { Mutex() }`. `saveRelay` (the add/edit dialog's Save action) and `deleteRelay` write/remove the same underlying `Relay` record via `updateRelayUseCase`/`removeRelayUseCase` without acquiring that same mutex. A role toggle in flight for a relay that is simultaneously being edited (`saveRelay`) or deleted (`deleteRelay`) can race: whichever `updateRelayUseCase`/`removeRelayUseCase` call lands last wins, silently discarding the other. This is a narrower version of CR-01 — same missing coordination, different call site — and lower likelihood in practice since the add/edit dialog typically has focus while open, but it's still an unguarded write path to relay records that the rest of this class treats as needing per-relay serialization.

**Fix:** If the UI genuinely can't produce this race (dialog exclusivity), document that assumption explicitly in the class doc comment; otherwise route `saveRelay`/`deleteRelay`'s persistence calls through the same `relayRoleMutexes[relayId]` guard.

### WR-04: Broad `catch (e: Exception)` / unchecked `runCatching` around suspend calls swallow `CancellationException` across most of this phase's write paths

**Files (representative, not exhaustive):**
- `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt:90-95` (`requestSignAndPublish`)
- `app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt:611-616` (`requestSignEvent`)
- `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt:134-141, 163-170, 214-218, 337-352` (`saveRelay`, `deleteRelay`, `removeRelayRole`, `updateRelayRole`)
- `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt:302-308` (`disableDeadRelay`)
- `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt:331-333, 352-365, 520-521` (`applyRelaysSnapshot`, `enforceAnonymousRelayPolicyIfNeeded`, `loadRelayInfo`)

**Issue:** In Kotlin, `CancellationException` is a subtype of `Exception`, so `catch (e: Exception) { ... }` (and unchecked `runCatching { ... }`, which internally does the same) catches and — in every site listed — does **not** rethrow it. This project's own `kotlin-coroutines-structured-concurrency` skill documents this exact anti-pattern and states plainly that this rule "still applies at full strength" independent of Umbra's singleton-scope convention. Each of these sites wraps a suspend call (`amberSignerGateway.signEvent`, `updateRelayUseCase`/`addRelayUseCase`/`removeRelayUseCase`, `relayRepository.updateRelay`, `relayInfoRepository.fetchAndPersist`) inside a `viewModelScope`/coordinator-`scope`-launched coroutine. If the owning scope is cancelled (screen closed, ViewModel cleared) while one of these suspend calls is in flight, the resulting `CancellationException` is caught, logged/ignored as an ordinary failure, and the coroutine proceeds to run its post-catch code (state updates, `onFailure` UI messages, etc.) instead of unwinding — defeating structured cancellation and doing work on behalf of a scope that has already been torn down.

**Fix:** Add a `catch (e: CancellationException) { throw e }` clause before the generic `catch (e: Exception)` at each site (or switch to `runCatching` variants that check `result.exceptionOrNull() is CancellationException` and rethrow). Given how many sites share this shape, consider a small shared helper (e.g. a `suspend fun <T> signSafely(block: suspend () -> T): T?` wrapper) rather than fixing each site independently, to keep the fix consistent.

### WR-05: `NostrSessionManager` and `RelayConfigViewModel` have no dedicated unit test for the concurrency behavior this phase changed

**Files:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt`, `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt`

**Issue:** `AtomicJobSchedulingTest`, `EventIngestCacheTest`, and `RelayCrudCoordinatorTest` all contain genuinely-concurrent (real-thread) regression tests for the specific fields/methods this phase converted. No `NostrSessionManagerTest` or `RelayConfigViewModelTest` exists anywhere under `app/src/test` (confirmed via search) — so LOG-30's claimed fix for `NostrSessionManager` has zero test coverage of its own call sites (only the generic `AtomicJobScheduling` extension functions are tested in isolation), and CR-02 above was therefore never exercised in CI.

**Fix:** Add a focused test that races `reconcile()`'s two concurrently-reachable invocation paths (or, more practically, a smaller pure-function extraction of the plain-field decision logic that can be tested deterministically without spinning up the full class).

## Info

### IN-01: `RelayCrudCoordinator.relayRoleMutexes` is never pruned

**File:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt:49`

**Issue:** `ConcurrentHashMap<String, Mutex>()` gains one entry per distinct relay id ever toggled through `updateRelayRole`, for the lifetime of the coordinator (i.e. the `RelayConfigViewModel`'s lifecycle). Practically bounded by the number of relays a user ever interacts with in one screen session, so this is unlikely to matter in practice, but it's an unbounded-growth structure with no removal path.

**Fix:** Not urgent; if ever revisited, an LRU-bounded map or removing an entry once a relay is deleted (`deleteRelay`) would close it.

---

_Reviewed: 2026-09-04T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
