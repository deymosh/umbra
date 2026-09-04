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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-24
- **Where:** `data/repository/EventRepositoryImpl.kt:428` (`"FEED_NOTES EOSE from relay $relayUrl reported MORE — not advancing since watermark"`), `:486` (`"Re-applied ${channelFilters.size} channels to relay $relayUrl"`), and `data/repository/NegentropySyncOrchestrator.kt:118` (`"NIP-77 sync with relay failed: ${e.message}"`) — line numbers for the first two updated 2026-08-27 after `EventRepositoryImpl.kt` extractions shifted them from :493/:551; same unfixed sites, reconfirmed during code review
- **Fix:** `EventRepositoryImpl.kt`'s two relay-URL interpolations (the feed-EOSE and channel-reapply
  debug logs) now wrap `relayUrl` in `LogScrubber.scrubUrlForLogs()` before logging; `NegentropySyncOrchestrator.kt`'s
  NIP-77 per-relay sync-failure log now wraps the caught exception's message in
  `LogScrubber.scrubThrowableMessageForLogs()` instead of interpolating `e.message` raw. Log level
  is unchanged at all three sites — this was a scrubbing-only fix.

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-27
- **Where:** `data/repository/EventIngestCache.kt:558-577` (`applyIncomingDeletion`)
- **Fix:** `applyIncomingDeletion`'s "a"-tag branch now resolves against both an in-memory
  candidate (a single upfront `cachedEvents` snapshot filtered by kind/pubkey/d-tag/created_at,
  taken once before the per-coordinate loop) and the existing `ownEventArchive` candidate, taking
  the newer of the two — mirroring `EventRepositoryImpl.getLatestAddressableEvent`'s established
  two-source resolution. The author-equality check and the `created_at` upper bound apply
  identically to the new source, and the removal is shared rather than branched by source. Pinned
  by three new `EventIngestCacheTest` cases (regression, ownership guard, recency guard).

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-27
- **Where:** `data/repository/EventRepositoryImpl.kt:498-500` (`clearAllData`)
- **Fix:** `clearAllData()`'s `disconnectFromAll()` catch no longer discards its exception silently
  — it now logs the throwable at error level via `logger.e(e) { "disconnectFromAll failed during
  clearAllData; continuing wipe" }`, naming only the failed step (no relay list, no pubkey, no row
  count). The wipe sequence still runs its remaining steps afterward exactly as before.

Found during review of the repository extraction. Confirmed pre-existing
(present before the extractions began). `try { disconnectFromAll() } catch (_: Exception) { }` — any
failure in `disconnectFromAll()` during a full data wipe (logout/account switch/factory reset) is
swallowed with zero logging. This is on a security/privacy-relevant path: if disconnect genuinely
fails, sockets could remain open and still deliver events while the rest of the wipe proceeds, and
nobody would know from the logs why. Fix: log the exception via `logger.e(e) { "disconnectFromAll
failed during clearAllData; continuing wipe" }` instead of swallowing it silently.

### LOG-21 — `snapshotEmitJob` is a plain, unsynchronized field mutated from concurrent coroutines
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-27
- **Where:** `data/repository/EventIngestCache.kt:203,353-373,384-389` (`scheduleSnapshotEmit`/`cancelPendingSnapshotEmit`)
- **Fix:** `snapshotEmitJob` is now a `val AtomicReference<Job?>`, matching `insertDebounceJob`'s
  existing precedent. `scheduleSnapshotEmit()` builds its job with `CoroutineStart.LAZY` and
  installs it via a single `compareAndSet` — a caller that loses the CAS cancels its unstarted
  job instead of leaking it, and the existing skip-relaunch-if-active coalescing semantics are
  unchanged. `cancelPendingSnapshotEmit()` now uses `getAndSet(null)?.cancel()`. Pinned by two new
  `EventIngestCacheTest` cases (an eight-way concurrent schedule burst producing exactly one
  snapshot and one bundle emission with no event lost, and a cancel-then-idempotent-recancel case).

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-28
- **Where:** `ui/profile/ProfileViewModel.kt` (`deleteEvent`, delegated through
  `InteractionActionsCoordinator.deleteEvent`'s `onOptimisticApply` callback)
- **Fix:** `InteractionActionsCoordinator.deleteEvent` no longer applies anything ahead of
  confirmation — instead of firing the caller's state-removal callback and the cache/archive
  removal synchronously before the async sign round trip resolves, both now run only from
  `requestSignAndPublish`'s `onSigned` callback, matching the commit-after-sign pattern
  `toggleMute`/`togglePin`/`toggleFollow` already use. No pending-action/rollback machinery was
  added; a rejected or failed sign leaves visible state, the in-memory cache, and the encrypted
  archive untouched, since nothing was applied before confirmation in the first place. Pinned by
  new `InteractionActionsCoordinatorTest` cases covering both the confirmed and rejected paths.

`deleteEvent` removed the target note from `state.notes` unconditionally, synchronously, before
the Amber sign round trip even resolved — not gated on the signature actually being confirmed,
and with no rollback path if signing was rejected or failed. This was inconsistent with the same
ViewModel's own `toggleMute`/`togglePin`/`toggleFollow`, all three of which commit only after
Amber confirms the signature. Confirmed pre-existing — present before this note's extraction
into `InteractionActionsCoordinator`, which preserved the behavior deliberately rather than
fixing it as a side effect.

### LOG-23 — FeedViewModel.muteUser's local-filter mute mirror is dead code
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-28
- **Where:** `ui/feed/FeedViewModel.kt` (`muteUser`, delegated through
  `InteractionActionsCoordinator.mirrorMuteIntoActiveFilter`'s caller-supplied
  `resolveActiveFilter` callback)
- **Fix:** `muteUser`'s resolver now takes the first entry of the live active-filters list
  (`feedRepository.getActiveFilters().first().firstOrNull()`), the same expression
  `ProfileViewModel.toggleMute` already uses, instead of the by-id lookup against
  `mergeActiveFeedFilters`'s fixed synthetic id. `mirrorMuteIntoActiveFilter`'s
  caller-supplied resolver parameter is unchanged. A new `FeedFilterTest` case pins the
  invariant that made the old lookup permanently dead: the merged filter's id is always the
  fixed synthetic id, never any input filter's id.

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-29
- **Where:** `ui/feed/FeedViewModel.kt` (`muteUser`, `togglePin`, calling
  `InteractionActionsCoordinator.applyMuteChange`/`applyPinChange`)
- **Fix:** Both local writes' `Result<Unit>` are now inspected via two new top-level
  `muteWriteResultMessage`/`pinWriteResultMessage` functions, which map success to the
  existing success messages unchanged and failure to the existing
  `error_mute_author`/`error_pin_note`/`error_unpin_note` strings carrying the failure's
  message — the same vocabulary and expression `ProfileViewModel.toggleMute`/`togglePin`
  already use. The mapping is covered by unit tests in `FeedViewModelStateTest`.

`muteUser`'s and `togglePin`'s `onSigned` callbacks call `applyMuteChange`/`applyPinChange` without
inspecting the returned `Result<Unit>`. If the local mute-list/pin-list write fails after Amber has
already confirmed and published the NIP-51 update, the UI still shows the optimistic success
message even though local state is now out of sync with what was published. Confirmed pre-existing
— the original inline `muteListRepository.mute()`/`pinListRepository.pin()`/`unpin()` calls were
likewise fire-and-forget before this extraction. `ProfileViewModel`'s own `toggleMute`/`togglePin`
already check `result.isSuccess` and surface an error message on failure, so the fix is to make
`FeedViewModel`'s equivalents do the same instead of unconditionally showing success.

### LOG-26 — SettingsScreen's logout flow has the same swallowed-exception bug FeedScreen's just had fixed
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-02
- **Where:** `ui/settings/SettingsScreen.kt` (the logout `MenuItemRow`'s `onClick` try/catch around `loginViewModel.logout()`)
- **Fix:** `SettingsScreen.kt` gained a file-scope tagged logger (`settingsScreenLogger`); its logout
  `catch` block now logs the caught exception at error level with the static message "Logout failed"
  before navigating to the login screen, matching the fix already shipped for `FeedScreen.kt`'s
  independent logout entry point (LOG-25). Navigation to the login screen still happens
  unconditionally regardless of whether logout succeeded, exactly as before.

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-02
- **Where:** `domain/usecase/LogoutUseCase.kt` (every step inside `invoke()`'s `try` block — session
  stop, data wipe, per-repository cache clears, backfill-anchor clear) / `domain/usecase/TrimMemoryCachesUseCase.kt` (every step inside `invoke()`)
- **Fix:** commits `5df6085` (TrimMemoryCachesUseCase, 5 sites) and `5dd50ce` (LogoutUseCase, 7
  sites) — each per-step catch now calls `logger.e(throwable) { "<step> failed during ..." }`
  with a static, data-free description; the outer method-wide catch and the unwrapped
  `userPreferences.clearAll()` call in `LogoutUseCase` are intentionally untouched (separate,
  smaller residual gap, filed as TODO LOG-32).

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-02
- **Where:** `ui/auth/LoginViewModel.kt` (`loginAnonymously()`'s and `savePublicKey()`'s inner
  `try { eventRepository.activateUserSession(...) } catch (_: Exception) { }`)
- **Fix:** both inner `try/catch` blocks around `eventRepository.activateUserSession(...)` (in
  `loginAnonymously()` and `savePublicKey()`) now log the caught exception at error level with the
  static message "Session activation failed" instead of discarding it silently. The inner catch
  itself is unchanged — a hydration failure still doesn't block login, and `nostrSessionController.start()`
  plus the authenticated-state update still run right afterward, exactly as before.

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-02
- **Fix:** Added a per-relay-id `Mutex` around `updateRelayRole`'s whole read-map-persist
  sequence and switched the base-relay read to a fresh `RelayRepository.getRelayById(relayId)`
  point read (falling back to `state.value.relays` only when the repository has no entry yet) —
  the lock alone wasn't sufficient since `state.relays` only resyncs on a 300ms-throttled
  collector, so a second serialized toggle would still have mapped from a pre-write snapshot.
  Covered by two genuinely-concurrent `RelayCrudCoordinatorTest` cases (same relay serializes and
  keeps both role flags, different relays don't false-serialize).
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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-02
- **Where:** `data/nostr/NostrSessionManager.kt` (`scheduleRetry()`'s `retryJob` check-then-act;
  also `bootstrapJob`/`userBackfillJob`/`autoDisableRelayJob`/`torCircuitRecoveryJob`/
  `ownProfileBootstrapWatcherJob`, all plain `var Job?` fields)
- **Fix:** Audited all six fields against their live call sites; `retryJob`, `userBackfillJob`,
  and `ownProfileBootstrapWatcherJob` became `AtomicReference<Job?>` holders, scheduled through
  two new non-blocking extension functions (`AtomicReference<Job?>.launchIfIdle`/`.launchReplacing`
  in the new `data/nostr/AtomicJobScheduling.kt`) instead of a plain check-then-act pair. Scheduling
  now goes through a single compare-and-set (or unconditional atomic swap for the cancel-and-replace
  fields) that cancels the losing/displaced candidate before it can execute a single statement, so a
  burst of concurrent reconciles can no longer schedule two jobs where one was intended or leave one
  silently orphaned. `bootstrapJob`, `autoDisableRelayJob`, and `torCircuitRecoveryJob` stay plain
  nullable fields — they're written only by the `start()`/`stop()` pair, which the volatile `started`
  flag already serializes, so an atomic holder would add no guarantee; that invariant is now recorded
  as an inline comment above the three fields rather than left unaudited. The no-lost-schedule and
  no-orphan guarantees are covered by a unit test that races eight genuinely parallel coroutines on a
  real `Dispatchers.Default` thread pool per scenario, not a single-threaded approximation.

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

### LOG-34 — Logger.e() leaks the raw, unscrubbed Throwable via Android's own stack-trace formatting, bypassing LogScrubber entirely
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-03
- **Where:** `util/logging/Logger.kt:23-27` (`e()`)
- **Fix:** `LogScrubber.scrubThrowableForLogs()` (new) returns a replacement `Throwable` — original
  stack frames, scrubbed message, no cause — and `Logger.e()` now passes that to `Log.e()` instead
  of the original throwable. Also moved `LogScrubber.kt` into `util/logging/` alongside
  `Logger`/`UmbraLog`, since it exists purely to serve them.

Found by code review of Phase 1 (Error Visibility & Log Hygiene), which promoted roughly two
dozen catch sites from debug to error level specifically so their failures would be visible in
release builds. `Logger.e()` correctly scrubs the `message()` string via
`LogScrubber.scrubThrowableMessageForLogs()`, but it also passes the raw, un-sanitized
`throwable` object as `Log.e(tag, msg, throwable)`'s third argument. Android's own `Log.e`
implementation appends `Log.getStackTraceString(tr)` to the printed line, and that helper's
first line is `tr.toString()` — the exception's own unscrubbed class name and message — repeated
for every exception in the `cause` chain. None of that text passes through `LogScrubber`; the
scrubbed string computed one line earlier is functionally redundant, since the very next thing
`Log.e` prints is the same content, unscrubbed, sourced directly from the `Throwable` itself.

This is not hypothetical: every one of Phase 1's promoted call sites is either a live-network
failure (SOCKS/TLS/timeout/connect exceptions from OkHttp, whose `.message` routinely embeds a
hostname, `.onion` address, or resolved `IP:port`) or a JSON/event-parsing failure that can echo
malformed input. Before Phase 1 these all logged at debug level, filtered out of release builds
by `Log.isLoggable(tag, Log.DEBUG)` — the raw throwable text never reached a release logcat.
Promoting them to error level (`Log.isLoggable(tag, Log.ERROR)` is true by default, and this
project's `proguard-rules.pro` has no `Log.e` stripping rule) removes that filter, so Phase 1
inadvertently widened the exposure window for this pre-existing gap from "never" to "every
occurrence." This directly contradicts `AUDIT.md`'s and the `find-non-lambda-logs` skill's claim
that `logger.e(throwable) { }` auto-scrubs the throwable — both describe the intended behavior,
not the actual one. Fix: build a sanitized throwable that keeps the real stack frames (harmless —
just class/method/file/line of this app's own code and library internals) but replaces the
exception's own message/cause chain with the already-scrubbed text, e.g.:
```kotlin
val scrubbed = LogScrubber.scrubThrowableMessageForLogs(throwable)
val safeForTrace = RuntimeException("${throwable.javaClass.simpleName}: $scrubbed").apply {
    stackTrace = throwable.stackTrace
}
Log.e(tag, "${message()}: $scrubbed", safeForTrace)
```

### LOG-35 — LoginViewModel.requestAmberLogin's catch block fully discards its throwable — never logged, only surfaced as raw UI text
- **Status:** open
- **Found:** 2026-09-03
- **Where:** `ui/auth/LoginViewModel.kt:182-192` (`requestAmberLogin`)

Found by code review of Phase 1. Unlike every other catch block Phase 1 touched in this same
file, `requestAmberLogin()`'s catch neither logs nor rethrows — it only sets
`errorMessage = UiMessage.Res(R.string.login_amber_response_error, listOf(e.message ?: ""))`.
Predates Phase 1 (not part of its changed hunks) but sits in a file that phase substantially
edited for exactly this class of bug, and is the same swallowed-throwable pattern LOG-25/27/28
were written to close elsewhere in this file. A secondary, more direct exposure: `e.message` is
never scrubbed before being interpolated into `UiMessage.Res` and rendered on-screen — visible to
anyone looking at the device or a screenshot, no `adb logcat` access needed, for the same class of
relay/network exception LOG-34 covers on the logging side. Fix: add
`logger.e(e) { "Amber login response failed" }` before updating `_authState`, and route `e.message`
through `LogScrubber.scrubThrowableMessageForLogs(e)` (or drop the raw detail from the user-facing
string) before it reaches `UiMessage.Res`. The same raw-`e.message`-in-UI pattern also exists in
`savePublicKey()` (`LoginViewModel.kt:150-160`) and should get the same scrubbing fix.

### LOG-31 — RelayCrudCoordinator.setDmEnabled marks the DM relay list dirty even when the enable is rejected
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-02
- **Fix:** Moved the `dmRelayListDirty = true` update into the mapper lambda, after the
  transport-rejection branch and immediately before the `relay.copy(...)` that actually changes
  the relay, so the flag only flips on the branch that produces a genuinely changed relay.
  Covered by `RelayCrudCoordinatorTest` (rejected `ws://` non-onion enable, accepted `wss://`
  enable, unknown relayId).
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

### LOG-37 — RelayCrudCoordinator.removeRelayRole bypasses the per-relay Mutex LOG-29 added for its sibling setters
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayCrudCoordinator.kt:174-220` (`removeRelayRole`)
- **Fix:** `removeRelayRole` no longer runs its own independent read-map-persist sequence — it now
  calls `updateRelayRole` (the same chokepoint the five `set*Enabled` setters route through) with a
  mapper that clears the target role's enable/active flags, so it picks up the per-relay-id `Mutex`
  and the fresh `relayRepository.getRelayById` read for free. `RelayCrudCoordinatorTest`'s existing
  two overlapping-role-toggle cases continue to pass unmodified.

Found during Phase 2's code review of the LOG-29 fix. `removeRelayRole` is functionally a sixth
role-mutating setter — it clears one role's enable/active flags, exactly like the five setters
LOG-29 fixed — but was never folded into `updateRelayRole`, which is the single chokepoint that
acquires `relayRoleMutexes[relayId]` and re-reads the relay fresh from
`relayRepository.getRelayById(relayId)` instead of the 300ms-throttled `state.value.relays`
mirror. `removeRelayRole` still reads from that stale mirror and acquires no lock at all. A
`removeRelayRole` call racing against any of the five `updateRelayRole`-routed setters — or
against a second `removeRelayRole` call — for the same relay id can silently lose one write,
reintroducing the identical lost-update race LOG-29 fixed for the other five methods.
`RelayDetailsScreen` exposes both role-remove actions and role-toggle switches for the same relay
in the same view, so this is realistically reachable, not theoretical. Fix: route
`removeRelayRole` through `updateRelayRole` the same way the five setters do, and add a
`RelayCrudCoordinatorTest` case racing `removeRelayRole` against a setter on the same relay id.

### LOG-38 — NostrSessionManager's plain instance fields are still unsynchronized across the two coroutines LOG-30's own fix comment says race each other
- **Status:** open
- **Found:** 2026-09-04
- **Where:** `data/nostr/NostrSessionManager.kt:150-171` (field declarations), `:311-398`
  (`reconcile`), `:449-460` (`startUserHistoryBackfill`), `:596-603` (`scheduleRetry`), `:271-287`
  (`stop`)

Found during Phase 2's code review of the LOG-30 fix. LOG-30 converted `retryJob`,
`userBackfillJob`, and `ownProfileBootstrapWatcherJob` to `AtomicReference<Job?>`, correctly
preventing double-scheduling the same job slot — but `reconcile()`'s body itself reads and writes
several plain, non-`@Volatile`, non-atomic fields reachable from the same two concurrent entry
points LOG-30's own retained-fields comment names (the `combine()`-driven collect loop and
`retryJob`'s own delayed relaunch): `relaysConnected`, `backfillPubkey`, `firstRelayConnectedLogged`,
`lastSnapshot`, and `ownProfileBootstrapPubkey`. None are protected by a `Mutex`, `AtomicReference`,
or `@Volatile`. Two `reconcile()` invocations racing on different threads can interleave reads and
writes of these fields — e.g. a spurious duplicate or missed backfill restart from
`startUserHistoryBackfill`'s `backfillPubkey`-keyed guard, or `relaysConnected` flipping back after
a newer state already set it. `stop()` compounds this: it writes several of the same fields from
whatever thread calls it, and only requests cancellation (`bootstrapJob?.cancel()`) rather than
joining, leaving a window where an in-flight `reconcile()` can still be executing concurrently
with `stop()`'s own field writes. This is the same class of bug LOG-30 set out to close, left
half-done for the plain state the same functions mutate. Fix: either confine all
`reconcile()`-reachable mutable state behind a single `Mutex` held for each field-touching
section, or migrate the listed fields to `AtomicReference`/`@Volatile`; at minimum `lastSnapshot`
needs `@Volatile` for cross-thread visibility since it's written on one thread and read on two
others.

### LOG-39 — RelayConfigViewModel.enforceAnonymousRelayPolicyIfNeeded silently discards failures while enforcing the anonymous-session privacy restriction
- **Status:** open
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayConfigViewModel.kt:346-367`

Found during Phase 2's code review. `enforceAnonymousRelayPolicyIfNeeded` turns off read/DM relay
roles when a session is anonymous — a genuine privacy control meant to keep read/DM relay usage
from being tied to an identity. The write is wrapped in an unchecked `runCatching { ... }` with no
`.onFailure { }` and no logging: if `updateRelayUseCase` throws for any reason, the anonymous-session
restriction silently fails to apply for that relay, with zero diagnostic trail and no way for the
caller to know enforcement didn't take effect. Same class of silent-catch-on-a-privacy-relevant-path
bug already fixed at LOG-20/LOG-27/LOG-28 elsewhere in this codebase, but this specific site was
never covered by any of those fixes. Fix: add `.onFailure { e -> logger.e(e) { "Failed to enforce
anonymous-session relay restriction" } }` after the `runCatching` block, matching LOG-20's fix
shape.

### LOG-40 — EventIngestCache.scheduleInsert's cancel-and-replace ordering lets the old and new debounce jobs run concurrently
- **Status:** open
- **Found:** 2026-09-04
- **Where:** `data/repository/EventIngestCache.kt:513-531` (`scheduleInsert`)

Found during Phase 2's code review, comparing `scheduleInsert` against the same file's
`scheduleSnapshotEmit` and `AtomicJobScheduling.launchReplacing`, both of which guarantee the old
job is cancelled strictly before the new one starts. `scheduleInsert` implements the same
cancel-and-replace shape by hand but gets the ordering backwards: it starts the new debounce job
eagerly (`repoScope.launch(Dispatchers.IO) { ... }`, not `CoroutineStart.LAZY`) and only cancels
the previous job afterward (`insertDebounceJob.getAndSet(newJob)?.cancel()`). Because the new job's
delay begins before the old one is cancelled, there is a narrow but real window where both the
superseded and superseding debounce coroutines are simultaneously alive.
`ConcurrentLinkedQueue.poll()` prevents this from losing data (each drains whatever's left), but it
can produce two separate `ownEventArchive.writeBatch()` transactions instead of the intended one
coalesced batch under a tight burst, defeating the debounce's purpose. Fix: route `scheduleInsert`
through the existing `AtomicJobScheduling.launchReplacing` helper (adjusted for the
`Dispatchers.IO` context), or manually cancel-then-lazily-start to match.

### LOG-41 — EventIngestCache.cacheRepostTarget skips the replaceable-event supersede bookkeeping ingest() enforces for the same slot
- **Status:** open
- **Found:** 2026-09-04
- **Where:** `data/repository/EventIngestCache.kt:278-283`, `:492-498` (`cacheRepostTarget`,
  `cacheVerifiedRepostTarget`)

Found during Phase 2's code review. `ingest()` maintains `latestReplaceableEventId` and runs
`winsReplaceableRace()` so only one revision per `ReplaceableEventKey` slot is ever retrievable
(the LOG-1 fix). `cacheRepostTarget` — used by NIP-18's `cacheVerifiedRepostTarget` to cache an
already-verified repost's embedded original event — bypasses all of that: it does an id-keyed
`cachedEvents.put(target)` with no `replaceableKey()`/`latestReplaceableEventId` update. If
`target` is itself a replaceable or parameterized-replaceable event (a NIP-18 repost of a
long-form article, a list, or a live-status event — all addressable kinds), caching it this way
never updates `latestReplaceableEventId`, so a subsequently-ingested direct revision of the same
slot won't know about this cached id when computing `supersededId`, and an older revision arriving
via a repost after a newer one was already ingested directly gets cached under its own id with no
race check at all — the exact bug LOG-1 was written to close for the direct-ingest path, left open
for the repost-embedded path. Fix: route `cacheRepostTarget` through the same replaceable-key-aware
logic `ingest()` uses (or a shared private helper both can call).

### LOG-42 — RelayCrudCoordinator.saveRelay/deleteRelay mutate a relay's persisted record without the per-relay Mutex updateRelayRole uses
- **Status:** open
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayCrudCoordinator.kt:51-143` (`saveRelay`), `:145-172` (`deleteRelay`)

Found during Phase 2's code review, as a narrower variant of LOG-37. `updateRelayRole` serializes
writes to a given relay id via `relayRoleMutexes.computeIfAbsent(relayId) { Mutex() }`. `saveRelay`
(the add/edit dialog's Save action) and `deleteRelay` write/remove the same underlying `Relay`
record via `updateRelayUseCase`/`removeRelayUseCase` without acquiring that same mutex. A role
toggle in flight for a relay simultaneously being edited or deleted can race: whichever call lands
last wins, silently discarding the other. Lower likelihood in practice than LOG-37 since the
add/edit dialog typically has focus while open, but it's still an unguarded write path to relay
records the rest of this class treats as needing per-relay serialization. Fix: either document why
the UI genuinely can't produce this race (dialog exclusivity), or route `saveRelay`/`deleteRelay`'s
persistence calls through the same `relayRoleMutexes[relayId]` guard.
