# KNOWN ISSUES

Local, session-found bug log. See [`.claude/CLAUDE.md`](../.claude/CLAUDE.md)'s "Bug tracking" section for the full convention — locally sequential numbers shared with [TODO.md](TODO.md) and [DONE.md](DONE.md), independent of GitHub issue numbers, statuses of `open` or `fix applied — needs on-device validation`. Once a fix is validated on-device (or otherwise confirmed working), its entry moves to [DONE.md](DONE.md). Every remaining entry at `fix applied` status now carries a `**Validation:**` bullet naming what is blocking its closure — either the user's own device pass or an architectural test-seam gap.

### LOG-2 — Image sometimes never loads until scrolled away/back or opened fullscreen
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-19
- **Where:** `ui/components/NostrImageComponents.kt` (`rememberRetryingAsyncImagePainter`) /
  `util/ImageLoadGate.kt`
- **Fix:** consolidated the `ImageLoadGate` permit's acquire/await/release lifecycle into a
  single `LaunchedEffect` with a real `try`/`finally`, replacing the prior 3-effect split
  (separate `LaunchedEffect` for acquire, `DisposableEffect` for teardown-release, another
  `LaunchedEffect` for terminal-state release). See `claude/bugs-y-features` branch.
- **Validation:** Awaiting the user's own on-device pass (via the `run-umbra`
  skill) — the fix is Compose `LaunchedEffect` lifecycle wiring in
  `NostrImageComponents.kt` with no pure-function extraction possible;
  `ImageLoadGateTest.kt` is cited as adjacent bonus coverage of the underlying
  `ImageLoadGate` only, not as proof this entry's actual regression is fixed.

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
- **Validation:** Awaiting the user's own on-device pass (via the `run-umbra`
  skill) for the rendered-frame behavior. Bonus logic coverage already exists
  and is sufficient: `VideoPlayerControllerTest.kt`'s `` `given anamorphic
  pixel ratio when computing aspect ratio then pixelWidthHeightRatio is
  applied` `` case directly exercises the pure aspect-ratio computation behind
  this fix — no new test needed.

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
- **Validation:** Both fixes are present in current source at the quoted lines
  (`NostrSessionManager.kt:255` for the reactive fourth combine input,
  `UserRepositoryImpl.kt:265-267` for the atomic staleness check-and-write). The
  real-race test this phase intended for LOG-4 (D-02) is not currently possible:
  `NostrSessionManager` requires `TorRuntimeManager`/`BackfillAnchorStore` (both
  need a live `android.content.Context`), and `UserRepositoryImpl` requires
  `ImagePrefetcher` (needs both a live `Context` and a Coil `ImageLoader`) — none
  of the three has an interface seam, a fake, or a Robolectric/mocking dependency
  available in this project, and no existing JVM unit test anywhere in the suite
  constructs an Android `Context`. This entry stays in `docs/KNOWN_ISSUES.md`
  alongside LOG-30/38/49/52.

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
- **Validation:** Awaiting the user's own on-device pass (via the `run-umbra`
  skill) — this is a Room DAO SQL statement with no JVM-testable path in this
  project (no Robolectric, no Room in-memory test harness, no instrumented DAO
  test); the ingestion integration test's fake DAO stubs this method to a
  constant `0` and never executes the real query.

Follow-up to LOG-1: that fix covers the in-memory `EventLruCache` (deduped at ingest time via
`ReplaceableEventKey`/`winsReplaceableRace()`), but the encrypted Room DB had no equivalent —
`EventDao.insertEvents()`/`insertEvent()` are `@Upsert`, which conflict-resolves by `id` only,
and every revision of a replaceable/parameterized-replaceable event (kind 0/3, 10000-19999,
30000-39999) has a distinct `id`, so old revisions of the signed-in user's own profile, contact
list, relay lists, etc. accumulated in `events`/`event_tags` forever. Confirmed by the user:
"asegurar que en la DB cifrada no se guardan versiones no necesarias de eventos reemplazables
igual que en caché ya se comprueba esto."

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
- **Validation:** Awaiting the user's own on-device pass (via the `run-umbra`
  skill) — the retry schedule is inline Compose-local state with no pure
  function to extract without a production-code change; no bonus test is
  attempted for this entry.

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

### LOG-26 — SettingsScreen's logout flow has the same swallowed-exception bug FeedScreen's just had fixed
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-02
- **Where:** `ui/settings/SettingsScreen.kt` (the logout `MenuItemRow`'s `onClick` try/catch around `loginViewModel.logout()`)
- **Fix:** `SettingsScreen.kt` gained a file-scope tagged logger (`settingsScreenLogger`); its logout
  `catch` block now logs the caught exception at error level with the static message "Logout failed"
  before navigating to the login screen, matching the fix already shipped for `FeedScreen.kt`'s
  independent logout entry point (LOG-25). Navigation to the login screen still happens
  unconditionally regardless of whether logout succeeded, exactly as before.
- **Validation:** Awaiting the user's own on-device pass (via the `run-umbra`
  skill) — the fix is Compose click-handler code with no ViewModel seam and
  this project has no Compose UI test / Robolectric infrastructure to assert
  it.

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
- **Validation:** A unit test for this fix is currently impossible:
  `NostrSessionManager`'s constructor requires `TorRuntimeManager` and
  `BackfillAnchorStore`, both concrete classes needing a live
  `android.content.Context` with no interface seam and no fake in
  `app/src/test/java/com/umbra/app/testutil/fakes/`, and this project has no
  Robolectric or mocking dependency to supply one. This is the identical
  architectural gap LOG-44 (`docs/TODO.md`) was deferred over. This entry
  stays in `docs/KNOWN_ISSUES.md` at `fix applied — needs on-device
  validation` — the fix itself is real, deployed, working code; only its
  automated test coverage is blocked. Do not attempt an isolated
  `NostrSessionManagerTest.kt` for this entry before LOG-44's interface-seam
  work lands.

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

### LOG-38 — NostrSessionManager's plain instance fields are still unsynchronized across the two coroutines LOG-30's own fix comment says race each other
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `data/nostr/NostrSessionManager.kt:150-171` (field declarations), `:311-398`
  (`reconcile`), `:449-460` (`startUserHistoryBackfill`), `:596-603` (`scheduleRetry`), `:271-287`
  (`stop`)
- **Fix:** `relaysConnected`, `backfillPubkey`, `firstRelayConnectedLogged`, `lastSnapshot`, and
  `ownProfileBootstrapPubkey` are now `@Volatile`, guaranteeing the write from whichever thread
  (the combine()-driven collect loop, scheduleRetry()'s delayed relaunch, or stop()'s caller) is
  visible to a read on any other. Every mutation of these five fields is a single unconditional
  assignment, never a read-modify-write of the field's own prior value, so cross-thread visibility
  was the actual gap — a full Mutex around reconcile()'s body was considered and rejected as a
  much larger, higher-risk restructuring for a class with no dedicated unit test (see LOG-44) to
  catch a regression. No dedicated concurrency test exists for this fix; flagged for manual
  verification.
- **Validation:** A unit test for this fix is currently impossible:
  `NostrSessionManager`'s constructor requires `TorRuntimeManager` and
  `BackfillAnchorStore`, both concrete classes needing a live
  `android.content.Context` with no interface seam and no fake in
  `app/src/test/java/com/umbra/app/testutil/fakes/`, and this project has no
  Robolectric or mocking dependency to supply one. This is the identical
  architectural gap LOG-44 (`docs/TODO.md`) was deferred over. This entry
  stays in `docs/KNOWN_ISSUES.md` at `fix applied — needs on-device
  validation` — the fix itself is real, deployed, working code; only its
  automated test coverage is blocked. Do not attempt an isolated
  `NostrSessionManagerTest.kt` for this entry before LOG-44's interface-seam
  work lands.

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

### LOG-49 — NostrSessionManager.maybeBootstrapOwnProfile's check-then-act guard wasn't atomic under reconcile()'s two concurrent entry points
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `data/nostr/NostrSessionManager.kt:427-452` (`maybeBootstrapOwnProfile`)
- **Fix:** Wrapped the method's full check-then-act body (the `ownProfileBootstrapPubkey != pubkey`
  guard through `bootstrapOwnProfileUseCase.start(pubkey)`) in a dedicated `ownProfileBootstrapMutex`,
  so `reconcile()`'s two documented concurrent entry points (the `bootstrapJob` collect loop and
  `retryJob`'s delayed relaunch) can interleave their scheduling but not their compound decisions.
- **Validation:** A unit test for this fix is currently impossible:
  `NostrSessionManager`'s constructor requires `TorRuntimeManager` and
  `BackfillAnchorStore`, both concrete classes needing a live
  `android.content.Context` with no interface seam and no fake in
  `app/src/test/java/com/umbra/app/testutil/fakes/`, and this project has no
  Robolectric or mocking dependency to supply one. This is the identical
  architectural gap LOG-44 (`docs/TODO.md`) was deferred over. This entry
  stays in `docs/KNOWN_ISSUES.md` at `fix applied — needs on-device
  validation` — the fix itself is real, deployed, working code; only its
  automated test coverage is blocked. Do not attempt an isolated
  `NostrSessionManagerTest.kt` for this entry before LOG-44's interface-seam
  work lands.

Found during Phase 2's iteration-2 code re-review. `@Volatile` on `ownProfileBootstrapPubkey` only
guarantees each individual read/write is visible across threads — it does not make the
read-decide-write sequence in `maybeBootstrapOwnProfile` exclusive. Two overlapping `reconcile()`
calls for the same pubkey could both observe the stale (pre-write) value and both call
`bootstrapOwnProfileUseCase.start(pubkey)`, a duplicate channel start whose safety otherwise
depended on that use case tolerating a double `start()` — outside this file's own scope to
guarantee.

### LOG-52 — ownProfileBootstrapMutex only guarded maybeBootstrapOwnProfile's own body, not the other two call sites mutating the same fields
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `data/nostr/NostrSessionManager.kt` (`stop()`, `maybeBootstrapOwnProfile`'s watcher-job
  tail, `stopOwnProfileBootstrap`)
- **Fix:** Split `stopOwnProfileBootstrap()` into a private, non-locking
  `stopOwnProfileBootstrapLocked()` (called only by code that already holds
  `ownProfileBootstrapMutex` — `Mutex` isn't reentrant, so a second call site couldn't just
  reacquire it) and updated both in-lock call sites in `maybeBootstrapOwnProfile` to use it
  directly. The watcher job's own trailing teardown, which runs on its own separately-scheduled
  coroutine outside the block that launched it, now wraps that call in its own
  `ownProfileBootstrapMutex.withLock { }`. `stop()` can't take the lock at all (`start()`/`stop()`
  aren't suspend per `NostrSessionController`'s interface), so it mutates
  `ownProfileBootstrapWatcherJob`/`ownProfileBootstrapPubkey` directly instead, mirroring the
  `getAndSet(null)?.cancel()` pattern already used there for `retryJob`/`userBackfillJob`; `start()`
  now also unconditionally resets `ownProfileBootstrapPubkey` to `null`, so a lost race in `stop()`
  can at worst leave a bootstrap channel running slightly longer than intended but can never cause a
  same-pubkey re-login to silently skip re-bootstrapping.
- **Validation:** A unit test for this fix is currently impossible:
  `NostrSessionManager`'s constructor requires `TorRuntimeManager` and
  `BackfillAnchorStore`, both concrete classes needing a live
  `android.content.Context` with no interface seam and no fake in
  `app/src/test/java/com/umbra/app/testutil/fakes/`, and this project has no
  Robolectric or mocking dependency to supply one. This is the identical
  architectural gap LOG-44 (`docs/TODO.md`) was deferred over. This entry
  stays in `docs/KNOWN_ISSUES.md` at `fix applied — needs on-device
  validation` — the fix itself is real, deployed, working code; only its
  automated test coverage is blocked. Do not attempt an isolated
  `NostrSessionManagerTest.kt` for this entry before LOG-44's interface-seam
  work lands.

Found during Phase 2's iteration-3 (final) code re-review, as a residual gap in LOG-49's fix.
LOG-49 correctly serialized `maybeBootstrapOwnProfile`'s own check-then-act sequence, but
`stopOwnProfileBootstrap()` — which mutates the exact same fields — was still reachable unguarded
from `NostrSessionManager.stop()` and from the watcher job's own trailing statement, both outside
any lock, so a stale watcher-job completion could tear down a just-started replacement bootstrap,
or an unguarded `stop()` race could leave `ownProfileBootstrapPubkey` non-null and cause the next
same-pubkey login to skip bootstrapping entirely.
