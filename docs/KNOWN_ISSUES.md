# KNOWN ISSUES

Local, session-found bug log. See [`.claude/CLAUDE.md`](../.claude/CLAUDE.md)'s "Bug tracking" section for the full convention — locally sequential numbers shared with [TODO.md](TODO.md) and [DONE.md](DONE.md), independent of GitHub issue numbers, statuses of `open` or `fix applied — needs on-device validation`. Once a fix is validated on-device (or otherwise confirmed working), its entry moves to [DONE.md](DONE.md).

### LOG-1 — Stale kind-0/replaceable-event revisions can linger in EventLruCache
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-19
- **Where:** `data/repository/EventRepositoryImpl.kt` (ingestion pipeline) / `data/repository/cache/EventLruCache.kt`
- **Fix:** `domain/nip01/ReplaceableEventKey.kt` (new) + `EventRepositoryImpl`'s ingestion
  pipeline now proactively evicts a superseded replaceable-event revision (NIP-01/33 rules,
  same race-tiebreak idiom as `OwnerTagSetCache.ingest()`) the moment a newer one for the
  same pubkey+kind[+d-tag] is ingested, instead of leaving both id-keyed entries to coexist
  until the LRU reclaims the old one. See `claude/optimize-replaceable-event-cache` branch.

When a kind-0 (metadata) event arrives, `EventRepositoryImpl` does two independent
things: it parses and saves the profile into `UserRepositoryImpl` (correctly keyed by
`pubkey`, replaces in place), and it also stores the raw `Event` in `EventLruCache`,
keyed by `event.id`. Since every revision of a kind-0 event has a different `id`, the
older raw revision is never proactively removed when a newer one arrives — it only
disappears once the LRU evicts it for lack of access, which can take arbitrarily long.
Net effect: `EventLruCache.get()`/`.snapshot()` can keep returning a superseded profile
revision well after `UserRepositoryImpl` already has the correct, newer one — two
sources of truth that can disagree. Same issue applies to any other replaceable kind
that reaches `EventLruCache` (`USEFUL_PERSISTED_KINDS`: 0, 3, 10000, 10002, 10007,
10050, 10086).

Found during a cache-architecture review comparing Umbra's caching against Amethyst's
`LocalCache`/`CachePruner.prunePastVersionsOfReplaceables()`.

### LOG-2 — Image sometimes never loads until scrolled away/back or opened fullscreen
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-19
- **Where:** `ui/components/NostrImageComponents.kt` (`rememberRetryingAsyncImagePainter`) /
  `util/ImageLoadGate.kt`
- **Fix:** consolidated the `ImageLoadGate` permit's acquire/await/release lifecycle into a
  single `LaunchedEffect` with a real `try`/`finally`, replacing the prior 3-effect split
  (separate `LaunchedEffect` for acquire, `DisposableEffect` for teardown-release, another
  `LaunchedEffect` for terminal-state release). See `claude/bugs-y-features` branch.

Reported by the user: an image in a note sometimes doesn't load even though it's already
downloaded/cached; scrolling the note out of view and back, or opening the image fullscreen,
makes it load correctly. Root cause: a window between `DisposableEffect`'s synchronous
`onDispose` and the acquiring coroutine's own (suspension-point-gated) cancellation semantics
could let a permit be acquired by a coroutine that was already being torn down, with no other
effect left alive to release it — permanently shrinking the pool below
`MAX_CONCURRENT_IMAGE_LOADS`. Fullscreen (ungated `SubcomposeAsyncImage`) and scroll-away/back
(disposes and recreates all `remember` state) both "worked around" the leak rather than
avoiding it.

### LOG-3 — Inline video player is correctly sized but the rendered frame doesn't fill it
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-19
- **Where:** `data/media/Media3Wrappers.kt` (`SimplePlayer.Listener`/`ExoSimplePlayer`) /
  `ui/components/NostrVideoComponents.kt` (`InlineVideoAttachment`)
- **Fix:** threaded `VideoSize.pixelWidthHeightRatio` through `SimplePlayer.Listener.
  onVideoSizeChanged` (previously discarded) into `InlineVideoAttachment`'s aspect-ratio
  calculation. See `claude/bugs-y-features` branch.

Reported by the user: a video's player view adopts the correct size for the video's
dimensions, but the video content itself (first frame and playback) doesn't fill the whole
player. Root cause: for anamorphic (non-square-pixel) content, the container's `aspectRatio`
modifier was sized from raw pixel width/height, while `PlayerView`'s internal
`AspectRatioFrameLayout` scales the actual frame using the pixel-corrected ratio — the two
diverged, producing a letterbox/pillarbox mismatch. `FullscreenVideoDialog` never hit this
since it never resizes its container from video size at all.

### LOG-4 — Freshest relay list isn't applied to the live connection until app restart
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-19
- **Where:** `data/nostr/NostrSessionManager.kt` (`start()`'s `bootstrapJob` combine) /
  `data/repository/UserRepositoryImpl.kt` (`saveRelayList()`/`applyRelayListToLocalConfig()`)
- **Fix:** added `userPreferences.getPublicKeyFlow()` as a fourth input to the `combine(...)`
  that drives relay-set reconciliation, replacing a plain non-reactive `getPublicKey()`
  snapshot read inside the transform. See `claude/bugs-y-features` branch.
- **Fix (2026-08-21):** on-device use after the above fix surfaced a second, distinct root
  cause of relay-list flakiness the first fix didn't cover: relays would appear correctly on
  login, then several would disappear and reappear under "Discovered" seconds later. Traced to
  a TOCTOU race in `saveRelayList()` — its read-check-write against the in-memory `relayLists`
  map wasn't atomic, so two concurrent kind:10002 deliveries for the same pubkey (common during
  post-login hydration, where the same relay list can arrive from multiple relays close
  together) could both pass the staleness guard and each schedule their own
  `applyRelayListToLocalConfig()` call — whichever acquired `relayConfigMutex` *last* won,
  regardless of which event was actually newer, letting a stale list wrongly strip relays a
  fresher list had just declared (which then got silently re-added as `isDiscovered = true` the
  next time any followed author's relay list happened to reference the same URL). Fixed with
  `ConcurrentHashMap.compute()` for an atomic staleness check-and-write, plus a freshness
  re-validation immediately after acquiring `relayConfigMutex` so an already-superseded list
  can't clobber a newer one that won the race after it was scheduled. See
  `claude/umbra-relay-feed-fixes` branch.

Reported by the user: on login, the app visibly fetches the profile and NIP-65 relay list
(subscription card shows all events received, private/outbox/inbox relays appear) but doesn't
apply the freshest relay list to the live connection — only closing and reopening the app
picks it up. Root cause: `UmbraApp.onCreate()` starts the session manager once per process and
`start()` is idempotent, so the login flow's own `start()` call was always a no-op; the only
live reconcile path only re-fired on relay/filter/Tor-state emissions, never purely because
login just wrote a new pubkey — so a login/account-switch could reconcile against a stale
pubkey snapshot until something else happened to also change the relay set.

Follow-up reported by the user (2026-08-21): after the above fix, the *initial* apply now
looks correct, but several relays still visibly move to "Discovered" moments later — see the
second Fix entry above for the actual TOCTOU race behind this.

### LOG-6 — Stale replaceable-event revisions also linger in the encrypted DB (not just EventLruCache)
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-21
- **Where:** `data/db/dao/EventDao.kt` / `data/repository/EventRepositoryImpl.kt` (`scheduleInsert()`)
- **Fix:** added `EventDao.deleteSupersededReplaceableEvents()`, mirroring the existing
  `getLatestAddressableEvent()` d-tag matching, run inside `scheduleInsert()`'s transaction for
  every distinct `ReplaceableEventKey` in a batch, right after that batch's tags are persisted.
  Reuses the same `domain/nip01/ReplaceableEventKey`/`winsReplaceableRace()` helpers LOG-1's
  `EventLruCache` fix already introduced. See `claude/umbra-relay-feed-fixes` branch.

Follow-up to LOG-1: that fix covers the in-memory `EventLruCache` (deduped at ingest time via
`ReplaceableEventKey`/`winsReplaceableRace()`), but the encrypted Room DB had no equivalent —
`EventDao.insertEvents()`/`insertEvent()` are `@Upsert`, which conflict-resolves by `id` only,
and every revision of a replaceable/parameterized-replaceable event (kind 0/3, 10000-19999,
30000-39999) has a distinct `id`, so old revisions of the signed-in user's own profile, contact
list, relay lists, etc. accumulated in `events`/`event_tags` forever. Confirmed by the user:
"asegurar que en la DB cifrada no se guardan versiones no necesarias de eventos reemplazables
igual que en caché ya se comprueba esto."

### LOG-7 — Future-dated events sometimes leak into the feed, or stay hidden past their own timestamp
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-21
- **Where:** `domain/nip01/Event.kt` (`isFromFuture()`/`isTimestampFromFuture()`) /
  `ui/feed/FeedViewModel.kt`, `ui/profile/ProfileViewModel.kt`, `ui/feed/ThreadViewModel.kt`
- **Fix:** dropped the 120s clock-drift tolerance to zero (per explicit product decision — the
  feed should never show a future-dated note before its own timestamp arrives), and added
  `ui/common/FutureEventRecheckTicker.kt`, a periodic Unit-emitting flow combined into
  `FeedViewModel`/`ProfileViewModel`/`ThreadViewModel`'s respective note-filtering chains so a
  note hidden for being future-dated reliably reappears once its timestamp passes, instead of
  staying hidden until some unrelated Room write happens to re-trigger the filter. See
  `claude/umbra-relay-feed-fixes` branch.

Reported by the user: "en el feed a veces sigo viendo eventos en el futuro, asegurar que ningún
evento en el futuro pueda aparecer en el feed, aparecerá cuando le corresponda en todo caso."
The future-timestamp filter already existed and was already applied at every note-list call
site, but `computedFeedFlow` (and its Profile/Thread equivalents) only re-evaluated when the
underlying Room flow or active filters changed — nothing re-triggered the filter purely because
wall-clock time had passed, so a correctly-hidden future note could stay invisible well past
its actual timestamp in an otherwise-quiet feed.

### LOG-11 — No notes or replies rendered anywhere: feed, own profile, or other profiles
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-21
- **Where:** `ui/common/FutureEventRecheckTicker.kt`, consumed by `ui/feed/FeedViewModel.kt`,
  `ui/profile/ProfileViewModel.kt`, `ui/feed/ThreadViewModel.kt`
- **Fix:** `futureEventRecheckTicker()` now emits `Unit` immediately on subscription before
  entering its `delay`/`emit` loop, instead of only emitting for the first time after a full
  `intervalMs`. Updated `FutureEventRecheckTickerTest` to assert the immediate first emission and
  adjusted its multi-tick timing assertion accordingly. See
  `claude/fix-future-recheck-ticker-blackout` branch.

Regression introduced by LOG-7's own fix commit (not LOG-7 resurfacing with the same root cause — a
new bug in the ticker LOG-7 added). Kotlin's `combine()` cannot emit anything until every source flow
has emitted at least once; since the ticker's original implementation never emitted until 30s after
being collected, none of the three `combine()` chains it was added to could produce output until
that first tick — and `WhileSubscribed(5_000)` on the feed's `stateIn`, plus normal
per-screen-visit lifecycles for `ProfileViewModel`/`ThreadViewModel`, meant that wait routinely got
reset before completing, rather than ever finishing. Net effect: notes and replies failed to render
in the feed, the signed-in user's own profile, and other users' profiles simultaneously — the one
shared bug touching every surface the user reported as broken.

### LOG-12 — Same relay dialed multiple times concurrently ("Connecting" spam, then several "Connected")
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-21
- **Where:** `data/nostr/UmbraNostrClient.kt` (`connect()`, `onWebSocketOpen`) /
  `data/repository/EventRepositoryImpl.kt` (`connectToEnabledRelays`, `reconnectRelevantDiscoveredRelays`,
  `connectToRelayHints`) / `data/nostr/NostrSessionManager.kt` (`reconcile()`, `torCircuitRecoveryJob`)
- **Fix:** added `UmbraNostrClient.dialingRelays`, a per-relayUrl `ConcurrentHashMap.newKeySet()`
  guard acquired atomically (`.add()`) at the very top of `connect()` and released in a `finally` —
  a second concurrent `connect()` call for a relayUrl already mid-dial now no-ops instead of tearing
  down and redialing. Scoped per relayUrl only, so concurrent dials to *different* relays (the
  batched-parallel-dial pacing in `connectToEnabledRelays`) are unaffected — this only dedupes
  overlapping dials to the *same* relay. Also added an identity check to `onWebSocketOpen`
  (`data/nostr/RelayMessageHandling.kt`), mirroring the conditional-remove pattern
  `onWebSocketClosed`/`onWebSocketFailure` already used (`webSockets.remove(relayUrl, webSocket)`):
  a socket superseded by a newer dial that still completes its handshake late is now closed and
  ignored instead of being treated as the authoritative connection. See
  `claude/fix-relay-dial-race` branch.

Reported by the user: seeing many "Connecting" issues in a row for what should be one relay, then
several "Connected" issues afterward. Root cause: `EventRepositoryImpl.connectToEnabledRelays()`,
`reconnectRelevantDiscoveredRelays()`, and `connectToRelayHints()` each independently pre-check
`isConnected()`/`hasActiveSocket()` before calling `nostrClient.connect(relayUrl)` — a
check-then-act pair that isn't atomic across callers, the same shape of TOCTOU race as the
relay-list-save one closed in LOG-4's second fix. Two of these paths racing for the same
relayUrl (most likely during exactly the scenario the user was watching: several relays flapping,
where `NostrSessionManager`'s normal reconcile and its separate `torCircuitRecoveryJob` can both
fire close together) could both pass the pre-check before either recorded the relay as
tracked/connected, so both called `connect()` — each independently closing the other's socket,
creating a new one, and emitting its own `CONNECTING`. `onWebSocketOpen` had no check for whether
its socket was still the current one for that relayUrl, so even an already-superseded socket that
finished its handshake late would still emit its own `CONNECTED` — one relay showing 3-4 "Connected"
entries for what should be a single connection.

### LOG-13 — Own avatar/banner sometimes slow to appear (stuck on placeholder)
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-21
- **Where:** `ui/components/UserAvatar.kt`, `ui/profile/ProfileScreen.kt` (`ProfileHero`'s banner)
- **Fix:** added `rememberRetryingImagePainter` (`UserAvatar.kt`), reusing the same escalating retry
  schedule (`MAX_IMAGE_LOAD_RETRIES`/`IMAGE_RETRY_DELAYS_MS`, widened from `private` to `internal`
  in `NostrImageComponents.kt`) that feed/attachment images already use for the identical
  Tor-circuit-build-timeout failure mode. Wired into `UserAvatar`'s static and animated (GIF)
  branches and `ProfileHero`'s banner `Image`, replacing the plain `rememberAsyncImagePainter`/
  `AsyncImage` calls that had no retry at all. Deliberately not routed through the feed images'
  `ImageLoadGate`/Blossom-fallback machinery — an avatar/banner is a single always-visible request,
  not one of a feed's many concurrent attachment loads, so it shouldn't queue behind a concurrency
  limiter sized for bulk feed image loading. See `claude/fix-relay-dial-race` branch.

Investigated alongside LOG-12 at the user's request. The profile metadata (URL) itself is not
delayed — `UserRepository.observeProfile()`/`ProfileViewModel`/`FeedViewModel.observeCurrentUserProfile()`
all read straight from the encrypted Room DB, independent of any relay activity. The actual
bottleneck was the Coil image fetch racing Tor's cold-start circuit build: `NostrSessionManager.start()`
(and `torRuntimeManager.start()` inside it) fires at process start in parallel with `MainActivity`'s
first composition, so the avatar/banner's `ImageRequest` often dispatches before Tor is actually
usable — and unlike feed/attachment images (`NostrImageComponents.kt`'s
`rememberRetryingAsyncImagePainter`, added for this exact documented failure mode), `UserAvatar`/the
profile banner had no retry, so a lost race left them stuck on the placeholder until an unrelated
recomposition (e.g. leaving and returning to the screen) created a fresh request. Distinct from LOG-2
(a since-fixed `ImageLoadGate` permit-leak) — `UserAvatar`/the banner never went through that gate
at all, so LOG-2's bug and fix both bypassed them entirely.

### LOG-14 — Promoting a discovered relay to an owned role does nothing
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-22
- **Where:** `ui/relay/RelayConfigViewModel.kt` (`saveRelay()`)
- **Fix:** `sanitizedRelay` now forces `isDiscovered = false` right after it's computed (covers
  the direct-edit and brand-new-relay branches), and the `existingRelay.copy(...)` merge branch
  (hit when a typed/edited URL matches an already-known relay) now sets it explicitly too, since
  that branch copies from `existingRelay` rather than `sanitizedRelay`. See
  `claude/relay-screen-usability-fixes` branch.

Editing a relay from the Discovered section (via `RelayDetailsScreen` → Edit → `RelayEditDialog`
→ Save) and assigning it an owned role (Outbox/Inbox/DM/Search/Index) silently fails to move it
out of Discovered: the relay stays bucketed under Discovered and is not included when the top-bar
Send action publishes the corresponding relay-list event.

Root cause: every automatic sync path that promotes a discovered relay to an owned role (the four
`apply*RelayListToLocalConfig` methods in `data/repository/UserRepositoryImpl.kt`, used when an
incoming NIP-65/DM/search/index list syncs in) explicitly sets `isDiscovered = false` on
promotion. `saveRelay()` — the only manual edit path — never does, so the relay keeps
`isDiscovered = true` and `observeDerivedRelayState()`'s bucketing (which checks `isDiscovered`
first) and `buildRelayListEventJson()`'s bucket-sourced publish content both keep ignoring it.

### LOG-18 — Three unscrubbed log messages survive the logging migration (EventRepositoryImpl.kt x2, NegentropySyncOrchestrator.kt x1)
- **Status:** open
- **Found:** 2026-08-24
- **Where:** `data/repository/EventRepositoryImpl.kt:428` (`"FEED_NOTES EOSE from relay $relayUrl reported MORE — not advancing since watermark"`), `:486` (`"Re-applied ${channelFilters.size} channels to relay $relayUrl"`), and `data/repository/NegentropySyncOrchestrator.kt:118` (`"NIP-77 sync with relay failed: ${e.message}"`) — line numbers for the first two updated 2026-08-27 after `EventRepositoryImpl.kt` extractions shifted them from :493/:551; same unfixed sites, reconfirmed during code review

The first two sites were found during the logging-migration closeout's `find-non-lambda-logs`
audit. The third (`NegentropySyncOrchestrator.kt:118`) was missed by that same
sweep and instead caught by a related code review — the `catch`
block wraps a per-relay NIP-77 sync call, so `e` is plausibly a network/IO exception whose `.message`
commonly embeds the target hostname/URL. All three sites interpolate raw, unscrubbed content into a
`logger.d { }` message without routing it through `LogScrubber.scrubUrlForLogs()` /
`scrubThrowableMessageForLogs()`, which AUDIT.md §1.3 and the `find-non-lambda-logs` skill's Check 1
both require. Confirmed pre-existing rather than introduced by the logging migration: all three sites
already interpolated the same raw, unscrubbed content before the logging migration — the 1:1 migration correctly preserved a defect that predates
it rather than introducing a new one. All three sites are debug-level, so release builds already
filter them today, but AUDIT.md's scrubbing rule applies independent of level. Fix: wrap both
`$relayUrl` interpolations in `LogScrubber.scrubUrlForLogs(relayUrl)` (matching every other relay-URL
log site in `EventRepositoryImpl.kt`), and wrap the `NegentropySyncOrchestrator.kt` `e.message` in
`LogScrubber.scrubThrowableMessageForLogs(e)`.

### LOG-19 — NIP-09 "a"-tag deletions never take effect for a non-owned author's cached addressable event
- **Status:** open
- **Found:** 2026-08-27
- **Where:** `data/repository/EventIngestCache.kt:558-577` (`applyIncomingDeletion`)

Found during review of the repository extraction — a regression introduced by narrowing
`OwnEventArchive`, not a pre-existing gap. `applyIncomingDeletion`'s "a"-tag
branch resolves the addressable event to delete exclusively through
`ownEventArchive.getLatestAddressableEvent(...)`, which only ever queries the encrypted Room
archive — and per this class's own doc comment and AUDIT.md, Room only ever contains the
signed-in user's own events. Since the "a"-tag author must equal the deletion event's own signer,
the only case this branch can ever resolve is the current user deleting their own addressable
event; a followed/other author's NIP-09 deletion of their own addressable event (kind 30000-39999
— long-form articles, lists, live statuses, etc.) that happens to be resident in the in-memory
cache is silently ignored, and the retracted content keeps showing up in feeds/threads
indefinitely for that session. By contrast, the public `EventRepositoryImpl.getLatestAddressableEvent`
API checks both the in-memory snapshot and Room. Fix: add a self-contained in-memory lookup
(the class already owns `cachedEvents`) alongside the `ownEventArchive` one, mirroring
`EventRepositoryImpl.getLatestAddressableEvent`'s two-source resolution, and take the newer of
the two matches (bounded by the deletion's own `created_at`) before including its id in
`resolvedAddressableIds`/removal.

### LOG-20 — Silent empty catch block during `clearAllData()`'s wipe sequence
- **Status:** open
- **Found:** 2026-08-27
- **Where:** `data/repository/EventRepositoryImpl.kt:498-500` (`clearAllData`)

Found during review of the repository extraction. Confirmed pre-existing
(present before the extractions began). `try { disconnectFromAll() } catch (_: Exception) { }` — any
failure in `disconnectFromAll()` during a full data wipe (logout/account switch/factory reset) is
swallowed with zero logging. This is on a security/privacy-relevant path: if disconnect genuinely
fails, sockets could remain open and still deliver events while the rest of the wipe proceeds, and
nobody would know from the logs why. Fix: log the exception via `logger.e(e) { "disconnectFromAll
failed during clearAllData; continuing wipe" }` instead of swallowing it silently.

### LOG-21 — `snapshotEmitJob` is a plain, unsynchronized field mutated from concurrent coroutines
- **Status:** open
- **Found:** 2026-08-27
- **Where:** `data/repository/EventIngestCache.kt:203,353-373,384-389` (`scheduleSnapshotEmit`/`cancelPendingSnapshotEmit`)

Found during review of the repository extraction. Confirmed pre-existing (the same
plain, non-`AtomicReference` `var` existed on `EventRepositoryImpl` before it was relocated
verbatim). `scheduleSnapshotEmit()` is called from `EventRepositoryImpl.subscribeToEvents`'s
`flatMapMerge(concurrency = EVENT_PROCESSING_CONCURRENCY)` branches, which can run on different
real threads. `snapshotEmitJob: Job?` is a plain `var` with no `@Volatile` and no atomic wrapper,
read-then-written (`if (snapshotEmitJob?.isActive == true) return; snapshotEmitJob =
repoScope.launch {...}`) from potentially concurrent callers, and separately read/nulled from
`cancelPendingSnapshotEmit()` on yet another dispatcher — an avoidable data race on shared mutable
state, inconsistent with the sibling `insertDebounceJob`, which correctly uses
`AtomicReference<Job?>` for the exact same check-and-cancel pattern a few dozen lines away in the
same file. Failure mode is a lost/duplicate scheduled emit, not a crash. Fix: use
`AtomicReference<Job?>` for `snapshotEmitJob`, matching `insertDebounceJob`'s pattern.

### LOG-22 — ProfileViewModel.deleteEvent removes a note from the visible list before Amber confirms the delete, with no rollback
- **Status:** open
- **Found:** 2026-08-28
- **Where:** `ui/profile/ProfileViewModel.kt` (`deleteEvent`, delegated through
  `InteractionActionsCoordinator.deleteEvent`'s `onOptimisticApply` callback)

`deleteEvent` removes the target note from `state.notes` unconditionally, synchronously, before
the Amber sign round trip even resolves — not gated on the signature actually being confirmed,
and with no rollback path if signing is rejected or fails. This is inconsistent with the same
ViewModel's own `toggleMute`/`togglePin`/`toggleFollow`, all three of which record a pending
action and roll it back (`rollbackPendingMuteIfNeeded`/`rollbackPendingPinIfNeeded`/
`rollbackPendingFollowIfNeeded`) if `requestSignEvent` comes back rejected/failed. Confirmed
pre-existing — present before this note's extraction into `InteractionActionsCoordinator`, which
preserved the behavior deliberately rather than fixing it as a side effect. Fix: give delete the
same pending-action-plus-rollback treatment as mute/pin/follow (restore the removed note to
`state.notes` if the sign round trip fails).

### LOG-23 — FeedViewModel.muteUser's local-filter mute mirror is dead code
- **Status:** open
- **Found:** 2026-08-28
- **Where:** `ui/feed/FeedViewModel.kt` (`muteUser`, delegated through
  `InteractionActionsCoordinator.mirrorMuteIntoActiveFilter`'s caller-supplied
  `resolveActiveFilter` callback)

`muteUser` resolves "the currently active filter" via `feedRepository.getFilterById(activeFeedFilter.id)`
so it can mirror a mute into that filter's local `mutedPubkeys` immediately (offline-safe, ahead of
the NIP-51 mute-list publish round-tripping). `activeFeedFilter` is always assigned from
`mergeActiveFeedFilters(...)` (`domain/feed/FeedFilter.kt`), which always returns a synthetic
filter with a fixed `id = "merged_active"` — never a real, persisted filter's id. `getFilterById`
queries the Room-backed filter table by that id, which will never match a stored row, so this
lookup returns null and the local-filter mirror silently never runs; the NIP-51 mute-list publish
(`muteListRepository.mute(target)`) still succeeds independently, so muting itself works, but the
active filter's own `mutedPubkeys` never gets the immediate, offline-safe update `ProfileViewModel`'s
equivalent (`toggleMute`, which resolves the active filter via `feedRepository.getActiveFilters().first().firstOrNull()`
instead) actually receives. Confirmed pre-existing — present before this mirror call was extracted
into `InteractionActionsCoordinator.mirrorMuteIntoActiveFilter`, which preserved
`FeedViewModel`'s exact (broken) resolution strategy as a caller-supplied callback rather than
converging it onto `ProfileViewModel`'s working one. Fix: resolve the active filter the same way
`ProfileViewModel.toggleMute` does (`feedRepository.getActiveFilters().first().firstOrNull()`)
instead of `getFilterById(activeFeedFilter.id)`.

### LOG-24 — FeedViewModel.muteUser/togglePin discard the mute/pin write's success/failure result
- **Status:** open
- **Found:** 2026-08-29
- **Where:** `ui/feed/FeedViewModel.kt` (`muteUser`, `togglePin`, calling
  `InteractionActionsCoordinator.applyMuteChange`/`applyPinChange`)

`muteUser`'s and `togglePin`'s `onSigned` callbacks call `applyMuteChange`/`applyPinChange` without
inspecting the returned `Result<Unit>`. If the local mute-list/pin-list write fails after Amber has
already confirmed and published the NIP-51 update, the UI still shows the optimistic success
message even though local state is now out of sync with what was published. Confirmed pre-existing
— the original inline `muteListRepository.mute()`/`pinListRepository.pin()`/`unpin()` calls were
likewise fire-and-forget before this extraction. `ProfileViewModel`'s own `toggleMute`/`togglePin`
already check `result.isSuccess` and surface an error message on failure, so the fix is to make
`FeedViewModel`'s equivalents do the same instead of unconditionally showing success.

### LOG-26 — SettingsScreen's logout flow has the same swallowed-exception bug FeedScreen's just had fixed
- **Status:** open
- **Found:** 2026-09-02
- **Where:** `ui/settings/SettingsScreen.kt` (the logout `MenuItemRow`'s `onClick` try/catch around `loginViewModel.logout()`)

Found by the whole-codebase bug-hunt sweep, specifically its empty-catch-
block grep pass. `SettingsScreen.kt` has its own, independent logout entry point (Settings ->
Account & Security -> Log Out) with the exact same shape LOG-25 already documented and fixed for
`FeedScreen.kt`'s drawer logout entry: `try { loginViewModel.logout() } catch (_: Exception) { }`
discards any exception with zero logging, then navigates to the login screen unconditionally
regardless of whether the logout actually succeeded. `FeedScreen.kt`'s copy of this same code was
fixed to log the throwable via the project's scrubbed logging utility before navigating (see
LOG-25), but that fix only touched `FeedScreen.kt` — `SettingsScreen.kt`'s
independent copy of the identical try/catch was never updated and still swallows the exception
silently. Fix: apply the same scrubbed-throwable logging `FeedScreen.kt` already uses to
`SettingsScreen.kt`'s catch block.

### LOG-27 — LogoutUseCase's (and TrimMemoryCachesUseCase's) per-step cleanup catches discard every failure with zero logging
- **Status:** open
- **Found:** 2026-09-02
- **Where:** `domain/usecase/LogoutUseCase.kt` (every step inside `invoke()`'s `try` block — session
  stop, data wipe, per-repository cache clears, backfill-anchor clear) / `domain/usecase/TrimMemoryCachesUseCase.kt` (every step inside `invoke()`)

Found by the whole-codebase bug-hunt sweep's empty-catch-block grep pass. `LogoutUseCase`
wraps each of its seven cleanup steps (`nostrSessionController.stop()`, `eventRepository.clearAllData()`,
`userRepository.clearAll()`, `contactListRepository.clearAll()`, `muteListRepository.clearAll()`,
`pinListRepository.clearAll()`, `eventRepository.clearBackfillAnchors(pubkey)`) in its own
`catch (_: Exception) { }`, plus an outer catch on the whole sequence, all deliberately silent so
one step's failure doesn't stop the rest of the wipe from running — a reasonable best-effort
design, but the total absence of any logging anywhere in the chain means a genuine failure during
logout's key-material/profile/relay-state wipe (the same privacy-relevant path LOG-20 already
flagged for `EventRepositoryImpl.clearAllData()`'s own internal `disconnectFromAll()` catch) leaves
no trace of what happened or why. `TrimMemoryCachesUseCase` has the identical five-step shape for a
lower-stakes path (shrinking in-memory caches on OS memory pressure), same silent-catch-per-step
pattern. Fix: log each step's exception (scrubbed per AUDIT.md, since some of these steps touch
pubkey-scoped data) via the project's logging utility before moving on to the next step, matching
LOG-20's fix shape.

### LOG-28 — LoginViewModel's session-activation failures are swallowed with zero logging during both anonymous and Amber login
- **Status:** open
- **Found:** 2026-09-02
- **Where:** `ui/auth/LoginViewModel.kt` (`loginAnonymously()`'s and `savePublicKey()`'s inner
  `try { eventRepository.activateUserSession(...) } catch (_: Exception) { }`)

Found by the whole-codebase bug-hunt sweep's empty-catch-block grep pass. Both
`loginAnonymously()` and `savePublicKey()` wrap their `eventRepository.activateUserSession(...)`
call in its own inner `catch (_: Exception) { }`, nested inside the method's own outer
`try`/`catch (e: Exception)` block that otherwise does correctly log a scrubbed failure message
(`logger.d { "Anonymous login failed: ${scrubThrowableMessageForLogs(e)}" }` / the equivalent
in `savePublicKey()`). Because the inner catch swallows `activateUserSession`'s exception before
it can propagate, the outer catch's logging path can never fire for this specific failure — a
session-activation failure (which is what actually triggers backfill/hydration for the freshly
logged-in account) is completely invisible in the logs even though this exact method already has
a working, scrubbed logging path one level up that a failure here should have reached. Net effect:
a user who logs in successfully but never sees their feed populate has zero diagnostic trail
explaining why. Fix: log the caught exception (scrubbed) inside the inner catch instead of
discarding it silently, or let it propagate to the outer catch that already logs correctly.

### LOG-29 — RelayCrudCoordinator's per-role enable-flag setters can lose a concurrent update to the same relay
- **Status:** open
- **Found:** 2026-09-02
- **Where:** `ui/relay/RelayCrudCoordinator.kt` (`updateRelayRole`, shared by `setOutboxEnabled`/
  `setInboxEnabled`/`setDmEnabled`/`setSearchEnabled`/`setIndexEnabled`)

Found by the whole-codebase bug-hunt sweep's TOCTOU grep pass, with particular attention to
whether the extraction introduced it — confirmed pre-existing: `updateRelayRole`'s
body is byte-identical to the same private helper in the pre-extraction `RelayConfigViewModel.kt`
(moved verbatim, only the enclosing class changed), so this is a relocated bug, not
a regression introduced by the extraction, and is logged rather than fixed. Each of the five per-role
setters calls `updateRelayRole(relayId) { ... }`, which launches a coroutine that reads
`state.value.relays.find { it.id == relayId }` for a snapshot of the relay, computes an updated
copy via the caller-supplied mapper, and writes it back via `updateRelayUseCase(updated)` — a
classic check-then-act (read-compute-write) sequence with no per-relay lock. If two of these
setters are invoked for the *same* relay id in close succession (e.g. a user toggling two role
switches on the same relay's detail screen quickly, or an automated bulk-role-change path), both
coroutines can read the same pre-toggle relay snapshot before either write lands, and whichever
`updateRelayUseCase` write completes last overwrites the other's role-flag change entirely — the
earlier toggle's effect is silently lost even though the UI showed it as applied. Same TOCTOU
shape as the already-fixed LOG-4 (relay-list save race) and LOG-12 (relay dial race) in this
codebase. Fix: guard `updateRelayRole` with a per-relay-id `Mutex` (or route through an atomic
read-modify-write primitive), matching LOG-4/LOG-12's precedent.

### LOG-30 — NostrSessionManager's retry-scheduling and job-bookkeeping fields are plain vars racing across concurrent IO-dispatcher coroutines
- **Status:** open
- **Found:** 2026-09-02
- **Where:** `data/nostr/NostrSessionManager.kt` (`scheduleRetry()`'s `retryJob` check-then-act;
  also `bootstrapJob`/`userBackfillJob`/`autoDisableRelayJob`/`torCircuitRecoveryJob`/
  `ownProfileBootstrapWatcherJob`, all plain `var Job?` fields)

Found by the whole-codebase bug-hunt sweep's TOCTOU and unsynchronized-shared-state grep
passes. `NostrSessionManager` runs its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — a
real multi-threaded dispatcher, not confined to one thread — and `scheduleRetry()`'s guard
(`if (retryJob?.isActive == true) return; retryJob = scope.launch { ... }`) is a check-then-act
pair with no synchronization, the same shape LOG-12 already fixed for this file's relay-dial path
(`connect()`'s pre-check-then-dial race) and LOG-21 already fixed for `EventIngestCache`'s
`snapshotEmitJob`. `scheduleRetry()`'s only call site is inside the relay-connect failure handler,
which this file's own LOG-12 history already documents as reachable from two independent
concurrent paths on this same `Dispatchers.IO` scope (the normal `combine()`-driven reconcile, and
`torCircuitRecoveryJob`'s separate recovery attempt) — so two connect failures landing close
together can both pass the `retryJob?.isActive` check before either assignment lands, scheduling
two retry jobs where one was intended, and the second assignment can silently orphan the first
job's reference (never cancelled) rather than dedupe against it. The sibling `Job?` fields listed
above share the same plain-`var`-with-no-atomic-wrapper shape, mutated from several different
methods without a lock. Fix: apply `AtomicReference<Job?>` (or a `Mutex`-guarded check-and-launch)
to `retryJob` at minimum, matching `EventIngestCache.insertDebounceJob`'s existing precedent
elsewhere in the codebase; audit the sibling fields for the same treatment.

### LOG-31 — RelayCrudCoordinator.setDmEnabled marks the DM relay list dirty even when the enable is rejected
- **Status:** open
- **Found:** 2026-09-02
- **Where:** `ui/relay/RelayCrudCoordinator.kt` (`setDmEnabled`)

`setDmEnabled` unconditionally flips `dmRelayListDirty = true` before calling `updateRelayRole`,
whose mapper can reject the enable (`enabled && !isDmTransportAllowed(relay.url)`, i.e. the relay
isn't a `.onion`/`wss://` address DM transport requires) and return the relay unchanged — a no-op
write. In that rejected case the user sees the "DM requires a secure relay" error message and the
top-bar Save button now shows the DM relay list as dirty, even though nothing about it actually
changed and there's nothing new to publish. Found during the code-review pass over the
`RelayCrudCoordinator` extraction; confirmed pre-existing (carried over verbatim from the
pre-extraction `RelayConfigViewModel.setDmEnabled`), not introduced by that extraction. Fix: move
the `dmRelayListDirty = true` update inside the mapper, conditioned on the mapper actually
producing a changed `Relay`, so the dirty flag only flips on a real DM-role change.
