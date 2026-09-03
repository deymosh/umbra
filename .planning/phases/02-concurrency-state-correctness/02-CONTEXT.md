# Phase 2: Concurrency & State Correctness - Context

**Gathered:** 2026-09-03
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase fixes 8 already-cataloged bugs (BUG-03, BUG-05, BUG-06, BUG-07, BUG-08, BUG-12, BUG-13, BUG-14 — LOG-19, 21, 22, 23, 24, 29, 30, 31) so that concurrent job/relay-role mutations are atomic, cached content honors deletions from non-owned authors, and every optimistic UI update either reflects what actually persisted or never applied in the first place. Root causes and suggested fix approaches for every bug are already documented in `.planning/codebase/CONCERNS.md` — this is fixing known, scoped correctness bugs, not open design work.

This is state-correctness work only — no new behavior beyond what's needed to fix these 8 bugs, no UI redesign, no unrelated optimistic-UI patterns touched (see D-03 below for the explicit scope boundary).

</domain>

<decisions>
## Implementation Decisions

### Delete UI/state model (LOG-22 / BUG-06)
- **D-01:** `ProfileViewModel.deleteEvent` moves off the "optimistic apply, no rollback" pattern entirely — there is no optimistic-UI-plus-rollback for this fix. Instead, delete adopts the same commit-after-sign pattern `toggleMute`/`togglePin`/`toggleFollow` already use elsewhere in the same ViewModel: `state.notes` is only filtered once Amber has actually confirmed the delete signature (inside the `onSigned` callback), never before. Concretely, `InteractionActionsCoordinator.deleteEvent`'s `onOptimisticApply` parameter (currently invoked synchronously before `requestSignAndPublish`'s async result is known) must move to run only from `requestSignAndPublish`'s `onSigned` callback. No rollback state machinery is needed because nothing is applied before confirmation. — **Reversibility:** reversible — this only changes when the removal callback fires, not the removal logic itself.
- **D-02:** Cache/archive removal (`removeDeletedNoteFromCacheUseCase`, which today runs unconditionally and deletes from both the encrypted Room archive `encryptedEventDao`/`encryptedEventTagDao` AND the in-memory `EventIngestCache` via `eventIngestCache.removeCachedEvent`) becomes conditional on Amber confirming the delete too, matching D-01: if Amber rejects/fails the delete, neither the visible UI state nor the cache/archive changes at all. Note for implementers: a user's own posts ARE resident in the in-memory `EventIngestCache` in addition to Room (they flow through the same ingest pipeline as everyone else's when rendered in feeds), so this is a real, non-trivial removal on both fronts — not a no-op for the Room-only case.
- **D-03 (scope boundary):** Only `InteractionActionsCoordinator.deleteEvent`'s `onOptimisticApply` is in scope for this "commit only after Amber confirms" fix. Two other code sites use the word "optimistic" but are a different category and are explicitly OUT of scope for this phase: `OwnerTagSetCache.updateCache` (already only runs after Amber signs — it races the relay echo, not Amber confirmation) and `RelayConfigScreen`'s local switch toggle (`optimisticChecked` — no Amber signing involved at all; it's a local Room write with its own `LaunchedEffect` resync-on-failure mechanism). Do not touch either of these as part of this phase.

### Mute/pin write-failure UX (LOG-24 / BUG-08)
- **D-04:** When `FeedViewModel.muteUser`/`togglePin`'s local write (`applyMuteChange`/`applyPinChange`) fails after Amber has already confirmed and published, reuse `ProfileViewModel`'s existing error strings and pattern exactly: `R.string.error_mute_author`/`error_unmute_author` for mute, `R.string.error_pin_note`/`error_unpin_note` for pin, via `UiMessage.ResWithArgs(errorRes, result.exceptionOrNull()?.message ?: "")`. Do not invent new copy describing the publish-succeeded-but-local-write-failed distinction — one error vocabulary for the same failure class across both ViewModels.

### Job-field atomicity scope (LOG-30 / BUG-13)
- **D-05:** `NostrSessionManager` has 6 plain `var Job?` fields (`retryJob`, `bootstrapJob`, `userBackfillJob`, `autoDisableRelayJob`, `torCircuitRecoveryJob`, `ownProfileBootstrapWatcherJob`) that could all in principle race across its own multi-threaded `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Do NOT convert all 6 to `AtomicReference<Job?>` speculatively. Instead, audit each field's actual call sites first; convert only fields where a genuine concurrent check-then-act race can be demonstrated (mirroring `retryJob`'s and `EventIngestCache.insertDebounceJob`'s proven `AtomicReference<Job?>` pattern). Fields found to only ever be touched from a single coroutine/call path stay as plain `var`, with an explicit code comment noting why conversion wasn't needed — not silently left unaudited. — **Reversibility:** reversible — converting a field later if a race is found post-hoc is additive, doesn't require undoing anything.

### Relay role-toggle lock granularity (LOG-29 / BUG-12)
- **D-06:** `RelayCrudCoordinator.updateRelayRole` gets a **per-relay-id** `Mutex`, not one global lock across all relay-role writes. Two concurrent role toggles on the SAME relay (e.g. tapping DM-enable then Inbox-enable in quick succession, before the first write's state resync lands) must serialize; toggles on two DIFFERENT relays must continue to run concurrently without waiting on each other. This matches `.planning/codebase/CONCERNS.md`'s suggested fix and avoids adding artificial latency when managing a relay list with many rows. Research/planning should account for the fact that `updateRelayRole` reads its base `relay` snapshot from `state.value.relays` — confirm the per-relay lock actually closes the race given how/when `state` resyncs after `updateRelayUseCase` persists, not just that the two calls no longer interleave.

### Test rigor for concurrency fixes
- **D-07:** For the Mutex/AtomicReference fixes specifically (LOG-21, LOG-29, and whichever LOG-30 fields end up converted per D-05), tests should force real concurrent races where feasible — using `kotlinx-coroutines-test`'s `TestDispatcher`/`runTest` to launch overlapping coroutines and assert no update is lost — rather than settling for simpler sequential-call behavioral tests. The codebase already has this test infrastructure available (`kotlinx-coroutines-test` dependency, `MainDispatcherRule`, and a precedent in `EventIngestCacheTest.kt`) — no new test tooling needs to be introduced.

### Claude's Discretion
- Exact `Mutex` scoping/lifetime for D-06's per-relay-id lock (e.g. a `ConcurrentHashMap<String, Mutex>` vs. another structure) is left to planner/researcher discretion.
- Which specific `NostrSessionManager` fields end up converted per D-05's audit is left to research/implementation — this context only locks the *decision rule* ("audit first, convert only demonstrable races"), not the specific field list.
- Whether LOG-19's (BUG-03) in-memory addressable-event lookup and LOG-31's (BUG-14) dirty-flag-only-on-actual-change fix need any UX-facing decision was not raised during discussion — `.planning/codebase/CONCERNS.md`'s documented fix approach for both was not contested and can proceed as described there (add an in-memory lookup alongside `ownEventArchive` for LOG-19; move the dirty-flag set into the mapper, after the no-op check, for LOG-31).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Bug catalog and fix approaches (primary source — read first)
- `.planning/codebase/CONCERNS.md` — root-cause analysis and suggested fix approach for every bug this phase addresses (LOG-19, 21, 22, 23, 24, 29, 30, 31), plus the broader "Multiple concurrent job-scheduling patterns" and "Check-then-act patterns for relay state" cross-cutting sections. Treat as more precise than the one-line descriptions in KNOWN_ISSUES.md.
- `docs/KNOWN_ISSUES.md` — canonical open-bug entries (LOG-19, 21, 22, 23, 24, 29, 30, 31) with `**Status:**`/`**Found:**`/`**Where:**` fields; update these to `fix applied — needs on-device validation` as each fix lands, per CLAUDE.md's Bug tracking section.
- `.planning/REQUIREMENTS.md` — BUG-03/05/06/07/08/12/13/14 definitions and Phase 2 success criteria.
- `.planning/phases/01-error-visibility-log-hygiene/01-CONTEXT.md` — Phase 1's context; establishes the `logger.e(throwable) { }` scrubbed-logging convention every new catch block in this phase's fixes should already be using (this phase adds no new silent catches).

### Concurrency conventions
- `.claude/skills/kotlin-coroutines-structured-concurrency/SKILL.md` — read its "In Umbra" section FIRST: stored `CoroutineScope` in `@Singleton` repositories is a deliberate, existing convention, not the anti-pattern to flag.
- `.claude/skills/umbra-coroutines/SKILL.md` — WebSocket-to-Flow bridge and debounce/conflate conventions relevant to `EventIngestCache`/`NostrSessionManager`.
- `app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt` — `insertDebounceJob`'s existing `AtomicReference<Job?>` pattern (line ~203 area) is the proven precedent D-05/LOG-21's fix must match.

### Code sites this phase touches
- `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt` — shared `deleteEvent`/`requestSignAndPublish` primitives (D-01/D-02).
- `app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt` — `deleteEvent` (LOG-22), `toggleMute`/`togglePin` as the reference pattern for commit-after-sign and error-string reuse (D-01, D-04).
- `app/src/main/java/com/umbra/app/ui/feed/FeedViewModel.kt` — `muteUser`/`togglePin` (LOG-23/24), `deleteEvent` (does not currently pass `onOptimisticApply`, only `onCacheRemoveFailure` — D-02 still applies to it via the shared coordinator).
- `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt` — 6 `Job?` fields (LOG-30/D-05).
- `app/src/main/java/com/umbra/app/data/repository/EventIngestCache.kt` — `snapshotEmitJob` (LOG-21), `applyIncomingDeletion`'s "a"-tag branch (LOG-19).
- `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt` — `updateRelayRole` and all five per-role setters (LOG-29/D-06), `setDmEnabled` (LOG-31).

### Testing conventions
- `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt` — existing precedent for `kotlinx-coroutines-test` usage in this codebase (D-07).
- `app/src/test/java/com/umbra/app/util/MainDispatcherRule.kt` — existing test dispatcher rule, reuse rather than reinvent.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `InteractionActionsCoordinator.requestSignAndPublish`'s `onSigned`/`onRejected` callback shape — already exactly the mechanism D-01 needs; `deleteEvent` just needs to route its state-mutation callback through it instead of firing synchronously.
- `EventIngestCache.insertDebounceJob`'s `AtomicReference<Job?>` — direct precedent for D-05's conversions.
- `ProfileViewModel.toggleMute`/`togglePin`'s `result.isSuccess` check + `UiMessage.ResWithArgs(errorRes, exceptionMessage)` — direct precedent for D-04.

### Established Patterns
- "Commit state only after Amber confirms the signature" is already the *dominant* pattern in this codebase (toggleMute, togglePin, toggleFollow all follow it) — `deleteEvent`'s pre-confirmation optimistic apply was the outlier, not the norm. D-01 brings it into line with the existing majority pattern rather than inventing a new one.
- `UiMessage.Res(...)` / `UiMessage.ResWithArgs(...)` via `_state.update { it.copy(errorMessage = ...) }` is the one error-surfacing channel both `ProfileViewModel` and `FeedViewModel` already use — no new error-display mechanism needed anywhere in this phase.

### Integration Points
- `InteractionActionsCoordinator.deleteEvent` is called from both `ProfileViewModel.deleteEvent` (passes `onOptimisticApply` + `onCacheRemoveFailure`) and `FeedViewModel.deleteEvent` (passes only `onCacheRemoveFailure`) — D-01/D-02's fix in the shared coordinator affects both call sites; `FeedViewModel`'s call site doesn't need its own signature change since it never used `onOptimisticApply`, but its cache-removal timing changes per D-02 regardless.
- `RelayCrudCoordinator.updateRelayRole` is the single private chokepoint for all 6 public per-role setters (`setOutboxEnabled`/`setInboxEnabled`/`setDmEnabled`/`setSearchEnabled`/`setIndexEnabled`/`setDiscoveredRelayEnabled`) plus `setDmEnabled`'s own dirty-flag bug (LOG-31) — the D-06 Mutex wraps this one function, fixing the race for all six callers at once.

</code_context>

<specifics>
## Specific Ideas

No specific UI/visual references — this phase is behavioral/correctness only. The concrete implementation commitments locked in discussion are D-01 through D-07 above; the most consequential one is D-01/D-03: the user explicitly rejected optimistic-UI-with-rollback as the fix shape for delete, in favor of eliminating the pre-confirmation state mutation entirely — and explicitly scoped that principle to only the one Amber-signing site that actually violates it (deleteEvent), not to the two superficially-similar "optimistic" sites elsewhere that don't race Amber.

</specifics>

<deferred>
## Deferred Ideas

None raised — discussion stayed within Phase 2's 8-bug scope. The two "optimistic UI" sites explicitly ruled out of scope (`OwnerTagSetCache.updateCache`, `RelayConfigScreen`'s switch toggle) are not deferred to a future phase either — they were evaluated and determined not to be bugs, not backlog items.

### Reviewed Todos (not folded)
None — `todo.match-phase` returned zero matches for Phase 2.

</deferred>

---

*Phase: 2-Concurrency & State Correctness*
*Context gathered: 2026-09-03*
