# Phase 2: Concurrency & State Correctness - Pattern Map

**Mapped:** 2026-09-03
**Files analyzed:** 5 (all modified, none newly created)
**Analogs found:** 5 / 5 (all analogs are intra-file or sibling-method precedents already in the same codebase — this phase fixes bugs by bringing outlier code sites into line with the dominant pattern already present elsewhere)

## File Classification

| File to Modify | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `ui/common/InteractionActionsCoordinator.kt` (`deleteEvent`) | service (shared ViewModel helper) | request-response (Amber sign round trip) | `requestSignAndPublish`'s own `onSigned`/`onRejected` shape (same file) | exact — same file, same class, sibling method |
| `ui/profile/ProfileViewModel.kt` (`deleteEvent`) | controller/viewmodel | request-response | `ProfileViewModel.toggleMute`/`togglePin` (same file, lines ~660-730) | exact |
| `ui/feed/FeedViewModel.kt` (`muteUser`, `togglePin`) | controller/viewmodel | request-response | `ProfileViewModel.toggleMute`/`togglePin` (cross-file precedent, same shape already partially followed) | exact |
| `data/nostr/NostrSessionManager.kt` (6 `Job?` fields) | service (singleton orchestrator) | event-driven | `EventIngestCache.insertDebounceJob` (`AtomicReference<Job?>`) | role-match (job-bookkeeping pattern, different class) |
| `data/repository/EventIngestCache.kt` (`snapshotEmitJob`) | service/cache | event-driven | `EventIngestCache.insertDebounceJob` (same file, lines 203/353-389 vs 213/496-514) | exact — same file, same class, sibling field |
| `data/repository/EventIngestCache.kt` (`applyIncomingDeletion`'s "a"-tag branch) | service/cache | CRUD (delete resolution) | `EventRepositoryImpl.getLatestAddressableEvent` (lines 1408-1418) | exact — documented two-source resolution precedent |
| `ui/relay/RelayCrudCoordinator.kt` (`updateRelayRole`, `setDmEnabled`) | service/coordinator | CRUD (check-then-act relay mutation) | `EventIngestCache.cachedEventsMutex` `withLock` pattern | role-match (per-key/per-resource Mutex pattern, different granularity) |

## Pattern Assignments

### `ui/common/InteractionActionsCoordinator.kt` — `deleteEvent` (D-01, D-02)

**Analog:** the same class's own `requestSignAndPublish` overloads (lines 67-116), whose `onSigned`/`onRejected` callback shape is exactly the mechanism `toggleMute`/`togglePin` already build on.

**Current (buggy) shape** (lines 157-169):
```kotlin
fun deleteEvent(
    event: Event,
    currentUserHex: String,
    onOptimisticApply: () -> Unit = {},
    onCacheRemoveFailure: () -> Unit = {}
) {
    val eventJson = deleteNoteUseCase(event, currentUserHex).getOrElse { return }
    requestSignAndPublish(eventJson, currentUserHex)
    onOptimisticApply()
    scope.launch {
        removeDeletedNoteFromCacheUseCase(event.id).onFailure { onCacheRemoveFailure() }
    }
}
```
Note `onOptimisticApply()` and the cache-removal `scope.launch` both fire unconditionally, synchronously, before `requestSignAndPublish`'s async Amber round trip resolves — this is the bug (LOG-22/D-01/D-02).

**Target shape — copy `requestSignAndPublish`'s `onSigned` callback wiring** (lines 83-103):
```kotlin
fun requestSignAndPublish(
    buildEventJson: suspend () -> String,
    currentUserHex: String?,
    onSigned: suspend () -> Unit = {},
    onRejected: suspend () -> Unit = {}
) {
    scope.launch {
        val signedEvent = try {
            amberSignerGateway.signEvent(buildEventJson(), currentUserHex)
        } catch (e: Exception) {
            logger.d { "Error requesting signed event: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
        if (signedEvent != null) {
            onSigned()
            publishSignedEvent(signedEvent)
        } else {
            onRejected()
        }
    }
}
```
Fix shape: `deleteEvent` should pass its state-mutation (`onOptimisticApply` — rename/repurpose as a plain commit callback invoked only inside `onSigned`) and the `removeDeletedNoteFromCacheUseCase` call both into the `onSigned` lambda of the existing `requestSignAndPublish(eventJson, currentUserHex, onSigned = {...})` overload (lines 67-72), instead of firing them synchronously before that call. No new callback machinery needed — reuse the overload already defined two methods above.

### `ui/profile/ProfileViewModel.kt` — `deleteEvent` (D-01)

**Analog:** `toggleMute`/`togglePin` in the same file (lines 660-730) — the "commit only after Amber confirms" pattern this phase brings `deleteEvent` into line with.

**Core commit-after-sign pattern** (lines 699-730, `togglePin`):
```kotlin
fun togglePin(event: Event) {
    if (!userPreferences.canSignWithAmber()) {
        _state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
        return
    }

    val eventId = event.id.lowercase()
    viewModelScope.launch {
        val wasPinned = pinListRepository.isPinned(eventId)

        interactionActionsCoordinator.requestSignAndPublish(
            buildEventJson = {
                val currentPinned = pinListRepository.getCurrentPinnedEventIds()
                NostrEventBuilder.pinList(if (wasPinned) currentPinned - eventId else currentPinned + eventId)
            },
            currentUserHex = userPreferences.getPublicKey(),
            onSigned = {
                val result = interactionActionsCoordinator.applyPinChange(eventId, pin = !wasPinned)
                if (!result.isSuccess) {
                    _state.update { state ->
                        state.copy(
                            errorMessage = UiMessage.ResWithArgs(
                                if (wasPinned) R.string.error_unpin_note else R.string.error_pin_note,
                                result.exceptionOrNull()?.message ?: ""
                            )
                        )
                    }
                }
            }
        )
    }
}
```

**Current (buggy) `deleteEvent`** (lines 635-647 — comment at 631-634 explicitly flags this as the known bug being fixed):
```kotlin
// NOTE: deletion is preserved exactly as-is below — the notes-list removal is unconditional
// (not gated on Amber confirming the delete signature) and never rolled back on a
// rejected/failed sign, unlike toggleMute/togglePin/toggleFollow's pending-action-plus-
// rollback pattern on this same ViewModel. Tracked as a known bug, not fixed here.
fun deleteEvent(event: Event) {
    val currentUserPubkey = userPreferences.getPublicKey()?.lowercase() ?: return
    interactionActionsCoordinator.deleteEvent(
        event = event,
        currentUserHex = currentUserPubkey,
        onOptimisticApply = {
            _state.update { state -> state.copy(notes = state.notes.filter { it.id != event.id }) }
        },
        onCacheRemoveFailure = {
            _state.update { state -> state.copy(errorMessage = UiMessage.Res(R.string.error_delete_note_failed)) }
        }
    )
}
```
Fix shape (per D-01): once `InteractionActionsCoordinator.deleteEvent` moves its optimistic-apply callback to fire only inside `onSigned` (see above), this call site's `onOptimisticApply` lambda body (`state.notes = state.notes.filter { ... }`) doesn't need to change — only *when* it runs changes, which is entirely the coordinator's responsibility. Remove the stale "tracked as a known bug, not fixed here" comment once fixed.

### `ui/feed/FeedViewModel.kt` — `muteUser`, `togglePin` (D-04, LOG-24)

**Analog:** `ProfileViewModel.toggleMute`'s `result.isSuccess` check (lines 678-688) — direct precedent for the exact error-string reuse D-04 locks.

**`ProfileViewModel.toggleMute`'s error-check pattern to copy** (lines 674-689):
```kotlin
onSigned = {
    interactionActionsCoordinator.mirrorMuteIntoActiveFilter(target, mute) {
        feedRepository.getActiveFilters().first().firstOrNull()
    }
    val result = interactionActionsCoordinator.applyMuteChange(target, mute)
    if (!result.isSuccess) {
        _state.update { state ->
            state.copy(
                errorMessage = UiMessage.ResWithArgs(
                    if (mute) R.string.error_mute_author else R.string.error_unmute_author,
                    result.exceptionOrNull()?.message ?: ""
                )
            )
        }
    }
}
```

**Current (buggy) `FeedViewModel.muteUser`** (lines 806-820) discards the `Result<Unit>`:
```kotlin
onSigned = {
    interactionActionsCoordinator.applyMuteChange(target, mute = true)
    interactionActionsCoordinator.mirrorMuteIntoActiveFilter(target, mute = true) {
        feedRepository.getFilterById(activeFeedFilter.id)
    }
    _uiState.update {
        it.copy(errorMessage = UiMessage.Res(R.string.user_muted_success), errorRelayId = null)
    }
}
```
Fix shape: capture `applyMuteChange`'s return into `val result = ...`, check `result.isSuccess`, and on failure use `UiMessage.ResWithArgs(R.string.error_mute_author, result.exceptionOrNull()?.message ?: "")` — copied verbatim from `ProfileViewModel`, per D-04's explicit "do not invent new copy" instruction. `FeedViewModel`'s `muteUser` only ever mutes (no unmute branch visible at this call site — confirm before deciding whether `error_unmute_author` is reachable here), so the ternary may not be needed; `togglePin` (lines 838-856) needs the same treatment using `error_pin_note`/`error_unpin_note`, mirroring `ProfileViewModel.togglePin` (lines 716-726) exactly.

Note: LOG-23 (the `getFilterById(activeFeedFilter.id)` dead-lookup bug inside `muteUser`'s `mirrorMuteIntoActiveFilter` call) is a separate bug in the same method — its own fix approach (use `feedRepository.getActiveFilters().first().firstOrNull()`, matching `ProfileViewModel.toggleMute`'s resolver at line 676) is the same analog, same file region, applied alongside D-04's fix.

### `data/repository/EventIngestCache.kt` — `snapshotEmitJob` (D-05/LOG-21)

**Analog:** the same file's own `insertDebounceJob`, an `AtomicReference<Job?>` solving the identical check-and-cancel race one field below.

**Proven precedent to copy** (lines 213, 496-514):
```kotlin
private val insertDebounceJob = AtomicReference<Job?>(null)

fun scheduleInsert(
    entity: EventEntity,
    tags: List<EventTagEntity> = emptyList(),
    replaceableKey: ReplaceableEventKey? = null
) {
    if (isWiping()) return
    if (!isCurrentUserPubkey(entity.pubkey)) return
    pendingInserts.add(PendingEventInsert(entity = entity, tags = tags, replaceableKey = replaceableKey))
    val newJob = repoScope.launch(Dispatchers.IO) {
        delay(INSERT_DEBOUNCE_MS)
        val batch = buildList {
            while (pendingInserts.isNotEmpty()) pendingInserts.poll()?.let { add(it) }
        }
        if (batch.isNotEmpty()) {
            ownEventArchive.writeBatch(batch)
        }
    }
    insertDebounceJob.getAndSet(newJob)?.cancel()
}

fun cancelPendingInserts() {
    insertDebounceJob.getAndSet(null)?.cancel()
    pendingInserts.clear()
}
```

**Current (buggy) `snapshotEmitJob`** (lines 203, 353-389) — plain `var Job?`, unsynchronized:
```kotlin
private var snapshotEmitJob: Job? = null
...
fun scheduleSnapshotEmit() {
    snapshotEmitPending = true
    if (snapshotEmitJob?.isActive == true) return

    snapshotEmitJob = repoScope.launch {
        delay(SNAPSHOT_BATCH_MS)
        if (!snapshotEmitPending) return@launch
        snapshotEmitPending = false
        val snapshot = cachedEventsMutex.withLock { cachedEvents.snapshot() }
        val bundle = buildSet {
            while (pendingSnapshotEvents.isNotEmpty()) {
                addAll(pendingSnapshotEvents)
                pendingSnapshotEvents.clear()
            }
        }
        _cachedEventsFlow.tryEmit(snapshot)
        if (bundle.isNotEmpty()) {
            _cachedEventBundles.tryEmit(bundle)
        }
    }
}

fun cancelPendingSnapshotEmit() {
    snapshotEmitJob?.cancel()
    snapshotEmitJob = null
    snapshotEmitPending = false
    pendingSnapshotEvents.clear()
}
```
Fix shape: change `private var snapshotEmitJob: Job? = null` to `private val snapshotEmitJob = AtomicReference<Job?>(null)` (import already present — `java.util.concurrent.atomic.AtomicReference` is imported at line 17). Replace the `if (snapshotEmitJob?.isActive == true) return` / `snapshotEmitJob = repoScope.launch {...}` pair with the same `getAndSet`-based check-and-cancel `insertDebounceJob` uses. Note the semantic difference to preserve: `scheduleSnapshotEmit` is "skip relaunch if active" (no cancel-and-replace, unlike `scheduleInsert`'s coalesce-into-newest) — keep that check-then-launch semantic, just make the read/write pair atomic via `compareAndSet` or an equivalent atomic idiom rather than blindly copying `insertDebounceJob`'s cancel-and-replace shape verbatim. `cancelPendingSnapshotEmit` mirrors `cancelPendingInserts`'s `getAndSet(null)?.cancel()` shape directly.

### `data/nostr/NostrSessionManager.kt` — 6 `Job?` fields (D-05/LOG-30)

**Analog:** same `insertDebounceJob`/`AtomicReference<Job?>` precedent above, applied per-field after the audit D-05 mandates.

**Fields in scope** (lines 150-164): `bootstrapJob`, `retryJob`, `userBackfillJob`, `autoDisableRelayJob`, `torCircuitRecoveryJob`, `ownProfileBootstrapWatcherJob` — all plain `var Job? = null`, all mutated from `start()`, `stop()`, `reconcile()`, `scheduleRetry()`, `startUserHistoryBackfill()`, `maybeBootstrapOwnProfile()`/`stopOwnProfileBootstrap()`, all running on `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.

Per D-05, do not convert all 6 speculatively — audit each field's actual call sites for a genuine concurrent check-then-act race (a read of `xJob?.isActive`/`xJob = ...` from two different coroutines that can interleave), matching `retryJob`'s existing shape:
```kotlin
private fun scheduleRetry() {
    if (retryJob?.isActive == true) return
    retryJob = scope.launch {
        delay(RETRY_DELAY_MS)
        if (!isActive) return@launch
        val snapshot = lastSnapshot ?: return@launch
        reconcile(snapshot, snapshot)
    }
}
```
Any field found to only ever be touched from the single serialized `bootstrapJob`'s `collect { ... }` coroutine (e.g. fields only reassigned inside `reconcile()`, which itself only ever runs from one `collect` at a time) stays a plain `var`, with an explicit comment stating that single-caller invariant — do not leave the decision undocumented per D-05's explicit requirement.

### `ui/relay/RelayCrudCoordinator.kt` — `updateRelayRole` (D-06/LOG-29, LOG-31)

**Analog for the per-key-Mutex shape:** `EventIngestCache`'s `cachedEventsMutex` (a single shared `Mutex` guarding several related mutable structures) is the closest existing `Mutex` usage in the codebase, but D-06 requires a *per-relay-id* lock, not one global lock — so the shape to copy is the `Mutex`-guarded critical-section idiom itself (`mutex.withLock { ... }`), not the single-shared-instance structure:
```kotlin
private val cachedEventsMutex = Mutex()
...
suspend fun ingest(event: Event, relayUrl: String, currentUserPubkey: String?): IngestOutcome {
    return if (shouldStoreInMemoryCache(event.pubkey, currentUserPubkey)) {
        cachedEventsMutex.withLock {
            ...
        }
    } else { ... }
}
```
For D-06, wrap this in a `ConcurrentHashMap<String, Mutex>` keyed by `relayId` (left to implementer discretion per CONTEXT.md), obtaining/creating the per-id `Mutex` via `computeIfAbsent` and wrapping the existing body of `updateRelayRole` in `.withLock { ... }`.

**Current (buggy) `updateRelayRole`** (lines 309-329):
```kotlin
private fun updateRelayRole(relayId: String, mapper: (Relay) -> Relay) {
    scope.launch {
        val relay = state.value.relays.find { it.id == relayId } ?: return@launch
        try {
            val updated = mapper(relay)
            updateRelayUseCase(updated)
            if (!relay.isEnabled && updated.isEnabled) {
                eventRepository.resetRelayFailureCount(relay.url)
            }
            if (relay.isEnabled && !updated.isEnabled) {
                eventRepository.disconnectRelay(relay.url)
            }
        } catch (e: Exception) {
            state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_update_relay, listOf(e.message ?: ""))) }
        }
    }
}
```
Note per D-06/CONTEXT.md: `state.value.relays` is read at the top, outside any lock today — confirm the per-relay Mutex actually closes the race given how/when `state` resyncs after `updateRelayUseCase` persists (the whole read-mapper-write sequence, including the `state.value.relays.find { ... }` read, must be inside the per-relay-id `withLock` block, not just the `updateRelayUseCase` call).

**LOG-31 (`setDmEnabled` dirty-flag bug, same coordinator)** — current buggy shape (lines 239-262) sets `dmRelayListDirty = true` unconditionally before validation:
```kotlin
fun setDmEnabled(relayId: String, enabled: Boolean) {
    if (userPreferences.isAnonymousSession()) { ... ; return }

    state.update { it.copy(dmRelayListDirty = true) }   // <-- set before validation/no-op check
    updateRelayRole(relayId) { relay ->
        if (enabled && !isDmTransportAllowed(relay.url)) {
            state.update { it.copy(errorMessage = UiMessage.Res(R.string.relay_dm_wss_required)) }
            return@updateRelayRole relay   // <-- mapper is a no-op here, but dirty flag already set above
        }
        relay.copy(isDmActive = enabled, dmRequiresAuth = if (enabled) true else false, isEnabled = ...)
    }
}
```
Per CONTEXT.md's discretion note (and CONCERNS.md's fix approach), move the `dmRelayListDirty = true` set into the mapper lambda, after the no-op (`isDmTransportAllowed` failure) check — i.e. only mark dirty on the branch that actually returns a changed `relay.copy(...)`, not on the branch that returns `relay` unchanged. `setOutboxEnabled`/`setInboxEnabled`/`setSearchEnabled`/`setIndexEnabled` (lines 212-282) don't have this bug (no early-return-unchanged branch inside their mappers) and are useful as the "what correct looks like" comparison — they set their dirty flag unconditionally because their mapper always actually changes the relay.

## Shared Patterns

### Commit-only-after-Amber-confirms (applies to InteractionActionsCoordinator.deleteEvent, ProfileViewModel.deleteEvent)
**Source:** `ProfileViewModel.toggleMute`/`togglePin`/`toggleFollow` (lines 660-750-ish), routed through `InteractionActionsCoordinator.requestSignAndPublish`'s `onSigned` callback (lines 83-103).
**Apply to:** `InteractionActionsCoordinator.deleteEvent`, `ProfileViewModel.deleteEvent`.
```kotlin
interactionActionsCoordinator.requestSignAndPublish(
    buildEventJson = { /* ... */ },
    currentUserHex = userPreferences.getPublicKey(),
    onSigned = {
        // state mutation / cache removal goes here — never before this callback fires
    }
)
```

### Result-checking + ResWithArgs error surfacing (applies to FeedViewModel.muteUser/togglePin)
**Source:** `ProfileViewModel.toggleMute`/`togglePin` (lines 678-688, 716-726).
**Apply to:** `FeedViewModel.muteUser`, `FeedViewModel.togglePin`.
```kotlin
val result = interactionActionsCoordinator.applyMuteChange(target, mute)  // or applyPinChange
if (!result.isSuccess) {
    _uiState.update { state ->
        state.copy(
            errorMessage = UiMessage.ResWithArgs(
                if (mute) R.string.error_mute_author else R.string.error_unmute_author,
                result.exceptionOrNull()?.message ?: ""
            )
        )
    }
}
```

### AtomicReference<Job?> check-and-cancel (applies to EventIngestCache.snapshotEmitJob, NostrSessionManager's audited fields)
**Source:** `EventIngestCache.insertDebounceJob` (lines 213, 496-514).
**Apply to:** `EventIngestCache.snapshotEmitJob`, whichever `NostrSessionManager` fields D-05's audit selects.
```kotlin
private val xJob = AtomicReference<Job?>(null)
// ... on schedule:
val newJob = scope.launch { /* ... */ }
xJob.getAndSet(newJob)?.cancel()
// ... on cancel:
xJob.getAndSet(null)?.cancel()
```

### Two-source (in-memory + Room) resolution for own-vs-non-owned events (applies to EventIngestCache.applyIncomingDeletion's "a"-tag branch)
**Source:** `EventRepositoryImpl.getLatestAddressableEvent` (lines 1408-1418).
```kotlin
override suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): Event? =
    withContext(Dispatchers.IO) {
        val cached = eventIngestCache.snapshot().asSequence()
            .filter { it.kind == kind && it.pubkey.equals(pubkey, ignoreCase = true) }
            .filter { it.getTagValue("d") == identifier }
            .maxByOrNull { it.createdAt }
        val encrypted = encryptedEventDao.getLatestAddressableEvent(kind, pubkey, identifier)
            ?.toDomain()
            ?.takeIf { isCurrentUserPubkey(it.pubkey) }
        listOfNotNull(cached, encrypted).maxByOrNull { it.createdAt }
    }
```
**Apply to:** `EventIngestCache.applyIncomingDeletion`'s "a"-tag branch (lines 558-577) — today it queries only `ownEventArchive.getLatestAddressableEvent(...)`. Add a self-contained in-memory lookup using `cachedEvents` (the class already owns this field directly — no need to go through the narrower `OwnEventArchive` interface for the in-memory half), filtered the same way (`kind`, `pubkey` case-insensitive match, `d`-tag identifier match, `maxByOrNull { createdAt }`), then take the newer of the in-memory and Room matches — both still bounded by `it.createdAt <= deletionEvent.createdAt` exactly as today's single-source check already enforces.

### Mutex.withLock critical-section idiom (applies to RelayCrudCoordinator's per-relay-id lock)
**Source:** `EventIngestCache.cachedEventsMutex` (lines 172, 226-260 e.g.).
```kotlin
private val cachedEventsMutex = Mutex()
...
cachedEventsMutex.withLock { /* read-modify-write on shared mutable state */ }
```
**Apply to:** `RelayCrudCoordinator.updateRelayRole`, keyed per-relay-id (e.g. `ConcurrentHashMap<String, Mutex>().computeIfAbsent(relayId) { Mutex() }`) rather than one shared instance — see D-06.

## No Analog Found

None — every file this phase touches has a directly usable, already-existing pattern elsewhere in the same file or a sibling file (this phase is explicitly "bring the outlier in line with the dominant pattern," not new-pattern design work).

## Testing Pattern (D-07)

**Source:** `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt` — uses `kotlinx.coroutines.test.runTest` throughout (imported at line 13; every test function is `= runTest { ... }`, e.g. lines 191, 210, 225, 239, 260, 278, 304, 330...). `app/src/test/java/com/umbra/app/util/MainDispatcherRule.kt` is the existing `TestDispatcher` rule to reuse for ViewModel-level tests (`ProfileViewModel`/`FeedViewModel` deleteEvent/muteUser/togglePin fixes).
**Apply to:** New/modified tests for D-01/D-02 (`ProfileViewModel`/`FeedViewModel` delete/mute/pin), D-05 (`NostrSessionManagerTest`, if one needs creating — check for an existing file first), D-06 (`RelayCrudCoordinatorTest`, likely needs a genuinely concurrent `launch` pair inside `runTest` racing two `setDmEnabled`/`setInboxEnabled` calls on the same vs. different relay ids to assert serialization only happens same-id).

## Metadata

**Analog search scope:** `ui/common/InteractionActionsCoordinator.kt`, `ui/profile/ProfileViewModel.kt`, `ui/feed/FeedViewModel.kt`, `data/nostr/NostrSessionManager.kt`, `data/repository/EventIngestCache.kt`, `data/repository/EventRepositoryImpl.kt`, `ui/relay/RelayCrudCoordinator.kt`, `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt`.
**Files scanned:** 8 read directly (all files this phase touches, plus the two cited canonical precedent files) — no broader Glob/Grep sweep was needed since CONTEXT.md's `<canonical_refs>` and `<code_context>` sections already named every analog precisely.
**Pattern extraction date:** 2026-09-03
