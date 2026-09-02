# Codebase Concerns

**Analysis Date:** 2026-09-02

## Tech Debt

**LOG-17 — Publish failure logs drop throwable, emit at debug level:**
- Issue: Eight sites across three packages (`domain/usecase/PublishEventUseCases.kt`, `ui/auth/LoginViewModel.kt`, `data/nostr/UmbraNostrClient.kt`, `data/nostr/RelayMessageHandling.kt`, `data/nostr/RelayWebSocketListener.kt`) lost throwable attachment during the logging migration. All call pre-migration `Log.d(TAG, message, e)` sites that were migrated to `logger.d { message }` (which has no throwable parameter). Stack-trace loss is invisible in release (debug-level filtered out), but violates AUDIT.md's logging rules.
- Files: `domain/usecase/PublishEventUseCases.kt` (2 sites), `ui/auth/LoginViewModel.kt` (3 sites), `data/nostr/UmbraNostrClient.kt`, `data/nostr/RelayMessageHandling.kt`, `data/nostr/RelayWebSocketListener.kt` (each 1 site)
- Impact: Silent debugging information loss; no stack trace context when publishes fail
- Fix approach: Promote each site individually to `logger.e(throwable) { ... }` (three-argument exception overload), with per-site review of whether ERROR level is appropriate for release visibility

**LOG-18 — Three unscrubbed log messages survive the logging migration:**
- Issue: Three sites interpolate raw, unscrubbed relay URLs/throwable messages into debug-level logs without routing through `LogScrubber` helpers
- Files: `data/repository/EventRepositoryImpl.kt:428` (`$relayUrl` in "FEED_NOTES EOSE"), `data/repository/EventRepositoryImpl.kt:486` (`$relayUrl` in "Re-applied channels"), `data/repository/NegentropySyncOrchestrator.kt:118` (`e.message` in catch block)
- Impact: Pre-existing defect, not introduced by the logging migration; already filtered in release, but violates AUDIT.md §1.3
- Fix approach: Wrap `$relayUrl` in `LogScrubber.scrubUrlForLogs(relayUrl)` (matching every other relay-URL log site), and wrap `e.message` in `LogScrubber.scrubThrowableMessageForLogs(e)`

## Known Bugs

**LOG-19 — NIP-09 "a"-tag deletions never take effect for non-owned cached addressable event:**
- Symptoms: When a followed/other author publishes a NIP-09 deletion targeting their own addressable event (kind 30000-39999: long-form articles, lists, live statuses), if that event is resident in the in-memory `EventLruCache`, the deletion is silently ignored and the retracted content keeps showing up in feeds/threads
- Files: `data/repository/EventIngestCache.kt:558-577` (`applyIncomingDeletion`'s "a"-tag branch)
- Trigger: Author A publishes an addressable event (e.g., kind-30023 article); it reaches the cache; Author A later deletes it; the deletion event arrives at the client
- Root cause: Regression introduced by narrowing `OwnEventArchive`. The "a"-tag deletion resolution path queries **only** `ownEventArchive.getLatestAddressableEvent(...)`, which only ever contains the signed-in user's own events (Room archive). A followed/other author's deletion can only match an addressable event they authored; by design this can never be the current user's own event, so the lookup always returns null
- Safe modification: Add a self-contained in-memory lookup (the class already owns `cachedEvents`) alongside the `ownEventArchive` one, mirroring `EventRepositoryImpl.getLatestAddressableEvent`'s two-source resolution pattern, and take the newer of the two matches (bounded by deletion's own `created_at`) before including its id in `resolvedAddressableIds`/removal
- Test coverage: Not covered by existing unit tests; `EventIngestCacheTest` would need an "a"-tag deletion scenario for non-owned cached events

**LOG-20 — Silent empty catch block during `clearAllData()`'s wipe sequence:**
- Symptoms: Any failure in `disconnectFromAll()` during logout/account-switch/factory-reset is swallowed with zero logging
- Files: `data/repository/EventRepositoryImpl.kt:498-500`
- Trigger: A relay disconnect fails (network error, socket timeout, etc.) during the privacy-relevant wipe sequence
- Root cause: `try { disconnectFromAll() } catch (_: Exception) { }` with no logging. If disconnect genuinely fails, sockets could remain open and still deliver events while the rest of the wipe proceeds, leaving no diagnostic trail
- Impact: High — affects privacy-critical path; silent failure could mask incomplete cleanup
- Fix approach: Log the exception via `logger.e(e) { "disconnectFromAll failed during clearAllData; continuing wipe" }` instead of swallowing silently

**LOG-21 — `snapshotEmitJob` is a plain, unsynchronized field mutated from concurrent coroutines:**
- Symptoms: Potential loss or duplicate scheduled emit, not a crash
- Files: `data/repository/EventIngestCache.kt:203,353-373,384-389`
- Root cause: `snapshotEmitJob: Job?` is a plain `var` with no `@Volatile` and no atomic wrapper, read-then-written (`if (snapshotEmitJob?.isActive == true) return; snapshotEmitJob = repoScope.launch {...}`) from potentially concurrent callers (via `EventRepositoryImpl.subscribeToEvents`'s `flatMapMerge(concurrency = EVENT_PROCESSING_CONCURRENCY)`), and separately read/nulled from `cancelPendingSnapshotEmit()` on yet another dispatcher — a classic data race on shared mutable state
- Precedent: Sibling `insertDebounceJob` correctly uses `AtomicReference<Job?>` for the exact same check-and-cancel pattern in the same file
- Safe modification: Use `AtomicReference<Job?>` for `snapshotEmitJob`, matching `insertDebounceJob`'s proven pattern
- Impact: Medium — silent loss/duplicate emit, not a crash, but breaks event snapshot delivery guarantees

**LOG-22 — ProfileViewModel.deleteEvent removes note before Amber confirms the delete:**
- Symptoms: A note disappears from the user's own profile immediately, even if Amber signing is rejected or fails
- Files: `ui/profile/ProfileViewModel.kt` (`deleteEvent` via `InteractionActionsCoordinator.deleteEvent`'s `onOptimisticApply`)
- Root cause: Removes the target note from `state.notes` unconditionally, synchronously, before the Amber sign round trip even resolves — no rollback if signing is rejected/fails
- Inconsistency: Same ViewModel's own `toggleMute`/`togglePin`/`toggleFollow` all record pending actions and roll them back on failure
- Safe modification: Give delete the same pending-action-plus-rollback treatment as mute/pin/follow (restore the removed note to `state.notes` if sign round trip fails)
- Impact: Medium — visual state mismatch with actual backend; confusing UX if delete fails silently

**LOG-23 — FeedViewModel.muteUser's local-filter mute mirror is dead code:**
- Symptoms: Muting works via NIP-51 publish, but the active feed filter's own `mutedPubkeys` never gets the immediate offline-safe update that the profile screen's equivalent does
- Files: `ui/feed/FeedViewModel.kt` (`muteUser` → `InteractionActionsCoordinator.mirrorMuteIntoActiveFilter`)
- Root cause: Resolves "the currently active filter" via `feedRepository.getFilterById(activeFeedFilter.id)`, but `activeFeedFilter` is always a synthetic filter (merged active state) with fixed `id = "merged_active"` — never a real persisted filter id. The lookup returns null, so the mirror silently never runs
- Inconsistency: `ProfileViewModel.toggleMute` resolves the active filter correctly via `feedRepository.getActiveFilters().first().firstOrNull()`
- Safe modification: Use `ProfileViewModel`'s correct resolution strategy instead of `getFilterById(activeFeedFilter.id)`
- Impact: Low — the main mute (NIP-51 publish) still works, but lost the offline-safe immediate visual update

**LOG-24 — FeedViewModel.muteUser/togglePin discard write result:**
- Symptoms: If the local mute-list/pin-list write fails after Amber has already confirmed and published the NIP-51 update, the UI shows optimistic success even though local state is now out of sync with what was published
- Files: `ui/feed/FeedViewModel.kt` (`muteUser`, `togglePin` → `InteractionActionsCoordinator.applyMuteChange`/`applyPinChange`)
- Root cause: `onSigned` callbacks call these helpers without inspecting the returned `Result<Unit>`. Loss of sync between local state and published NIP-51
- Precedent: `ProfileViewModel`'s own `toggleMute`/`togglePin` already check `result.isSuccess` and surface error messages on failure
- Safe modification: Check `result.isSuccess` and surface error message on failure, matching ProfileViewModel
- Impact: Medium — potential data loss or confusion; user thinks a pin/mute is persisted when it's only local

**LOG-26 — SettingsScreen's logout flow has the same swallowed-exception bug FeedScreen's just had fixed:**
- Symptoms: Any logout exception is swallowed; user navigates to login screen unconditionally regardless of whether logout succeeded
- Files: `ui/settings/SettingsScreen.kt` (logout `MenuItemRow`'s `onClick`)
- Root cause: `try { loginViewModel.logout() } catch (_: Exception) { }` with zero logging, then unconditional navigation. Same shape LOG-25 already documented and fixed for `FeedScreen.kt`'s drawer logout
- Precedent: `FeedScreen.kt`'s identical code was fixed to log via `scrubbed logging utility` before navigating
- Safe modification: Apply the same scrubbed-throwable logging `FeedScreen.kt` already uses to `SettingsScreen.kt`'s catch block
- Impact: Low — logout likely succeeds in practice, but silent failure masks cleanup issues on the privacy-relevant path

**LOG-27 — LogoutUseCase's and TrimMemoryCachesUseCase's per-step cleanup catches discard every failure with zero logging:**
- Symptoms: Genuine failure during logout's key-material/profile/relay-state wipe (privacy-relevant path) leaves no trace of what happened or why
- Files: `domain/usecase/LogoutUseCase.kt` (7 cleanup steps with `catch (_: Exception) { }`), `domain/usecase/TrimMemoryCachesUseCase.kt` (5 steps)
- Root cause: Each cleanup step wrapped in its own silent catch (reasonable best-effort design, so one step's failure doesn't stop the rest of wipe), but total absence of logging means genuine failures are invisible
- Impact: High — privacy-critical path with zero diagnostic trail on failure
- Safe modification: Log each step's exception (scrubbed per AUDIT.md, since some steps touch pubkey-scoped data) via the project's logging utility before moving on to the next step, matching LOG-20's fix shape

**LOG-28 — LoginViewModel's session-activation failures are swallowed with zero logging:**
- Symptoms: A user who logs in successfully but never sees their feed populate has zero diagnostic trail explaining why (session activation is what triggers backfill/hydration)
- Files: `ui/auth/LoginViewModel.kt` (`loginAnonymously()` and `savePublicKey()`, inner `try { eventRepository.activateUserSession(...) } catch (_: Exception) { }`)
- Root cause: Inner catch swallows `activateUserSession`'s exception before it can propagate to the outer catch that would log it correctly (outer catch has working, scrubbed logging path)
- Impact: High — session-activation failures (hydration triggers) are completely invisible in logs even though this method already has a working logging path one level up
- Safe modification: Log the caught exception (scrubbed) inside the inner catch instead of discarding it, or let it propagate to the outer catch that already logs correctly

## Test Coverage Gaps

**EventIngestCache NIP-09 "a"-tag deletions:**
- What's not tested: Deletion of a non-owned author's addressable event when that event is resident in the in-memory cache (but not in Room)
- Files: `data/repository/EventIngestCache.kt`
- Risk: Addressed in LOG-19 — followed authors' deletions are silently ignored for cached addressable events
- Priority: High — directly affects content accuracy in feed

**Concurrent relay role state mutations:**
- What's not tested: Multiple rapid role-toggle operations on the same relay via `RelayCrudCoordinator.setDmEnabled`/`setInboxEnabled`/etc. (LOG-29)
- Files: `ui/relay/RelayCrudCoordinator.kt`
- Risk: Second toggle can overwrite the first's effect due to TOCTOU race
- Priority: High — affects relay configuration correctness

**Session activation error paths:**
- What's not tested: Failures in `eventRepository.activateUserSession(...)` during login (LOG-28)
- Files: `ui/auth/LoginViewModel.kt`, `domain/usecase/*` (backfill/hydration entry points)
- Risk: Users with failed activation see no diagnostic feedback and feed stays empty indefinitely
- Priority: High — critical user-facing flow

**Future-event re-filtering timing:**
- What's not tested: FutureEventRecheckTicker's immediate-first-emission behavior and cascading effects on dependent `combine()` chains (LOG-7/LOG-11)
- Files: `ui/common/FutureEventRecheckTicker.kt`, `ui/feed/FeedViewModel.kt`, `ui/profile/ProfileViewModel.kt`, `ui/feed/ThreadViewModel.kt`
- Risk: Recent fix (immediate emit requirement) is fragile; regression would re-introduce the "no notes anywhere" bug
- Priority: Medium — already fixed but regression-prone

## Fragile Areas

**EventRepositoryImpl.kt (2375 lines) — Critical event management:**
- Files: `data/repository/EventRepositoryImpl.kt`
- Why fragile: Monolithic central hub managing WebSocket connections, event ingestion, feed resolution, relay routing, TOCTOU-prone state mutations (relay connect checks, relay list saves, negentropy sync). Large file with multiple responsibility domains (network lifecycle, cache management, database persistence, feed hydration logic)
- Known races: LOG-4 (relay-list save TOCTOU — fixed), LOG-12 (relay dial race — fixed), LOG-20 (silent disconnect failure — open)
- Safe modification: Isolate relay-connection state behind a dedicated coordinating class with atomic operations (or mutex per relay). Extract feed-resolution logic into separate concern. Add comprehensive logging to all critical paths
- Associated bugs: LOG-20 (silent exception in critical clearAllData path), LOG-18 (unscrubbed logging)

**NostrSessionManager.kt (630 lines) — Job lifecycle coordination:**
- Files: `data/nostr/NostrSessionManager.kt`
- Why fragile: Manages seven concurrent `Job?` fields (all plain `var`, no synchronization) mutated from multiple concurrent IO-dispatcher coroutines. Central orchestration point for relay reconnect, backfill, Tor recovery, relay auto-disable, and profile bootstrap — each with independent scheduling/cancellation logic
- Known races: LOG-12 (relay dial pre-check race — fixed, but same pattern appears here), LOG-30 (job-bookkeeping field races — open across six fields)
- Safe modification: Audit and convert all `Job?` fields to `AtomicReference<Job?>`, or introduce a mutex-guarded helper for check-and-launch pattern
- Associated bugs: LOG-30

**EventIngestCache.kt (612 lines) — In-memory event cache:**
- Files: `data/repository/EventIngestCache.kt`
- Why fragile: Central cache for all non-owned events; replaceable-event revision eviction logic (LOG-1 — fixed but recent), NIP-09 deletion resolution (LOG-19 — open), and pending-emit scheduling (LOG-21 — open). Handles Race between `scheduleSnapshotEmit`/`cancelPendingSnapshotEmit` from concurrent flatMapMerge branches
- Known races: LOG-21 (`snapshotEmitJob` unsynchronized — open)
- Known gaps: LOG-19 (a-tag deletion doesn't check in-memory cache — open)
- Safe modification: Convert `snapshotEmitJob` to `AtomicReference<Job?>`. Fix LOG-19's two-source address resolution for a-tag deletions
- Associated bugs: LOG-1 (fixed), LOG-19 (open), LOG-21 (open)

**RelayConfigViewModel.kt / RelayCrudCoordinator.kt (547 + ~200 lines delegated) — Relay state mutations:**
- Files: `ui/relay/RelayConfigViewModel.kt` (547 lines), `ui/relay/RelayCrudCoordinator.kt` (extracted per-role setters)
- Why fragile: Multiple entry points for relay state changes (edit screen, detail screen, card toggles, role setters) with check-then-act patterns (LOG-4 — fixed for relay list saves, LOG-29 — open for per-role toggles, LOG-31 — open for DM enable dirty flag)
- Known races: LOG-29 (per-role enable TOCTOU — open), LOG-31 (dirty flag set before validation — open)
- Safe modification: Guard all relay-id-specific mutations with per-relay-id `Mutex`. Move dirty-flag logic into the actual enable/disable mappers, not before
- Associated bugs: LOG-14 (fixed), LOG-29 (open), LOG-31 (open)

**FeedViewModel.kt (1098 lines) — Feed state and interactions:**
- Files: `ui/feed/FeedViewModel.kt`
- Why fragile: Complex state composition with multiple data sources (feed filters, local mute mirrors, pending operations, relay state), delegating interaction-action logic to `InteractionActionsCoordinator`. Contains dead-code paths (LOG-23) and fire-and-forget result handling (LOG-24)
- Known issues: LOG-23 (dead local-filter mirror for mute — open), LOG-24 (discard write result — open)
- Safe modification: Remove unused `getFilterById` call path for mute mirror. Inspect results from `applyMuteChange`/`applyPinChange` and surface error messages on failure
- Associated bugs: LOG-23 (open), LOG-24 (open)

**LoginViewModel.kt (414 lines) — Authentication flow:**
- Files: `ui/auth/LoginViewModel.kt`
- Why fragile: Two independent nested try/catch blocks around `activateUserSession`, where inner catch (swallowing) prevents outer catch (logging) from ever firing for this specific failure path (LOG-28)
- Known issues: LOG-28 (session-activation failures invisible — open)
- Safe modification: Remove inner catch or let it log and propagate
- Associated bugs: LOG-28 (open)

## Scaling Limits

**EventLruCache size vs. replaceable-event staleness:**
- Current capacity: Limited by `EventLruCache`'s fixed-size LRU (implementation detail, exact number varies by memory pressure)
- Limit: Stale replaceable-event revisions can linger indefinitely if older revisions are never evicted before the cache is trimmed (LOG-1 — fixed via proactive eviction), though the in-memory cache's size constraint is tighter than Room's persistence limit
- Scaling path: Already addressed (LOG-1 fixed with proactive eviction per `ReplaceableEventKey`). Monitor Room-persisted replaceable events separately (LOG-6 — now fixed with `EventDao.deleteSupersededReplaceableEvents()`)

**Concurrent relay connections and reconnect storms:**
- Current capacity: Unbounded concurrent dials can spike during Tor circuit failures or broad network issues (especially with multiple independent reconnect paths: normal reconcile + `torCircuitRecoveryJob`)
- Limit: Socket exhaustion, Tor resource limits, or relay rate-limiting if too many dials land at once
- Scaling path: Already addressed for dial deduping (LOG-12 — fixed with `dialingRelays` guard). Monitor for retry-storm behavior; `RelayBackoffPolicy` should gate reconnect escalation

**ImageLoadGate concurrency limiter:**
- Current capacity: `MAX_CONCURRENT_IMAGE_LOADS` limit (fixed pool size)
- Limit: Feed images that should load in parallel get queued if concurrency cap is hit
- Scaling path: Already addressed (LOG-2 — fixed `ImageLoadGate` permit leak). Monitor via `EventCacheStats.concurrentImageLoads` if added

## Dependencies at Risk

**BouncyCastle 1.85.2 (BIP-340 Schnorr signing):**
- Risk: Heavy cryptographic dependency; any vulnerability affects core signing validation
- Impact: Breaks event integrity verification if signature validation logic is compromised
- Mitigation: Already in use as part of event-crypto pipeline; `EventCrypto.verifyEvent()` runs before any persistence
- Monitoring: Security advisories from Bouncy Castle project; upgrade path via Gradle dependency management

**OkHttp 5.4.0 (HTTP/WebSocket/TOR proxy client):**
- Risk: Single point of network-traffic enforcement; any proxy-bypass vulnerability or TLS issue undermines TOR-only guarantee
- Impact: Catastrophic — TOR-only constraint is non-negotiable; any bypass leaks user identity
- Mitigation: SOCKS5 proxy mandatory in every client instantiation; `NetworkModule` guards against client creation outside that module; AUDIT.md §1.1 enforces this at code-review level
- Monitoring: Security advisories from OkHttp; review any version bump

**Coil 3.5.0 + Coil OkHttp integration:**
- Risk: Image loading pipeline; if not configured with `@Named("tor")` client, could leak image loads outside TOR
- Impact: Privacy leak via image URL resolution and request
- Mitigation: Global `ImageLoader` from Hilt uses `@Named("tor")` client by design; no app-level image loading outside Hilt
- Monitoring: Code review ensures no new `ImageLoader.Builder()` instantiation outside `NetworkModule`

**SQLCipher via Zetetic (Room encryption):**
- Risk: Encrypted database library; any crypto weakness exposes all persisted events and user data
- Impact: Data at rest not protected; device compromise reads all history
- Mitigation: Room integration prevents plaintext fallback; `EncryptedUmbraDatabase` only instantiation
- Monitoring: Zetetic security advisories; Room version updates

**Media3 1.11.0 (video playback with OkHttpDataSource):**
- Risk: Video streaming through TOR; if not wired to `@Named("tor")` OkHttpClient, could leak URLs
- Impact: Privacy leak via video URL resolution and metadata leaks
- Mitigation: `OkHttpDataSource.Factory` in `MediaModule` uses `@Named("tor")` client by design
- Monitoring: Code review of any new Media3 feature integration

## Architectural Concerns

**Multiple concurrent job-scheduling/cancellation patterns without standardization:**
- Issue: Job lifecycle management spread across `NostrSessionManager`, `EventRepositoryImpl`, `EventIngestCache`, `TorRuntimeManager`, `TrackReferencedAuthorUseCase` using mix of plain `var Job?` (unsafe), `AtomicReference<Job?>` (safe), and manual cancellation logic
- Impact: Introduces TOCTOU races (LOG-30, LOG-21), orphaned jobs, duplicate scheduling
- Recommendation: Introduce a reusable `SafeJobScheduler` utility wrapping `AtomicReference<Job?>` and `Mutex`-based check-and-launch, or migrate all to structured concurrency with channel-based coordination

**Check-then-act patterns for relay state (dial pre-check, relay-list save staleness guard, per-role enable toggles):**
- Issue: Multiple independent check-then-act sequences in `EventRepositoryImpl`, `UserRepositoryImpl`, `RelayCrudCoordinator`, and `NostrSessionManager` without atomic guards (LOG-4 first fix, LOG-12, LOG-29)
- Impact: TOCTOU races where stale or duplicate writes occur because check and act aren't atomic across concurrent callers
- Recommendation: Isolate relay state behind a dedicated, mutex-guarded `RelayStateCoordinator` with atomic read-modify-write operations. Same pattern should apply to per-role enable setters

**Silent exception handling on privacy-critical paths:**
- Issue: Logout (`EventRepositoryImpl.clearAllData`, `LogoutUseCase`), session activation (`LoginViewModel`), and data wipe (`TrimMemoryCachesUseCase`) all have silent catch blocks with zero logging (LOG-20, LOG-27, LOG-28)
- Impact: High — failures on privacy-critical paths leave no diagnostic trail; genuine cleanup failures (sockets still open, data not wiped) are invisible
- Recommendation: Implement structured error aggregation on each critical path, logging and continuing per-step on failure (as LOG-27 suggests), or surfacing aggregated failure to user with diagnostic context

**Dead/incorrect resolution paths for feed state queries:**
- Issue: `FeedViewModel.muteUser` resolves active filter incorrectly (LOG-23); similar patterns likely exist elsewhere
- Impact: Silent operational failures where some state mutations succeed (NIP-51 publish) but local cache stays out of sync
- Recommendation: Audit all "resolve active filter" call sites and standardize on one correct pattern (`feedRepository.getActiveFilters().first().firstOrNull()`)

---

*Concerns audit: 2026-09-02*
