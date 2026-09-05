# KNOWN ISSUES

Local, session-found bug log. See [`.claude/CLAUDE.md`](../.claude/CLAUDE.md)'s "Bug tracking" section for the full convention — locally sequential numbers shared with [TODO.md](TODO.md) and [DONE.md](DONE.md), independent of GitHub issue numbers, statuses of `open` or `fix applied — needs on-device validation`. Once a fix is validated on-device (or otherwise confirmed working), its entry moves to [DONE.md](DONE.md).

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayConfigViewModel.kt:346-367`
- **Fix:** `enforceAnonymousRelayPolicyIfNeeded`'s `runCatching { updateRelayUseCase(...) }` now
  chains `.onFailure { e -> logger.e(e) { "Failed to enforce anonymous-session relay restriction" } }`,
  matching LOG-20's fix shape. `RelayConfigViewModel` gained its own `TAG`/`logger` (it had neither
  before).

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

### LOG-49 — NostrSessionManager.maybeBootstrapOwnProfile's check-then-act guard wasn't atomic under reconcile()'s two concurrent entry points
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `data/nostr/NostrSessionManager.kt:427-452` (`maybeBootstrapOwnProfile`)
- **Fix:** Wrapped the method's full check-then-act body (the `ownProfileBootstrapPubkey != pubkey`
  guard through `bootstrapOwnProfileUseCase.start(pubkey)`) in a dedicated `ownProfileBootstrapMutex`,
  so `reconcile()`'s two documented concurrent entry points (the `bootstrapJob` collect loop and
  `retryJob`'s delayed relaunch) can interleave their scheduling but not their compound decisions.

Found during Phase 2's iteration-2 code re-review. `@Volatile` on `ownProfileBootstrapPubkey` only
guarantees each individual read/write is visible across threads — it does not make the
read-decide-write sequence in `maybeBootstrapOwnProfile` exclusive. Two overlapping `reconcile()`
calls for the same pubkey could both observe the stale (pre-write) value and both call
`bootstrapOwnProfileUseCase.start(pubkey)`, a duplicate channel start whose safety otherwise
depended on that use case tolerating a double `start()` — outside this file's own scope to
guarantee.

### LOG-51 — NostrSessionManager's onFailure handlers logged a scrubbed message instead of the throwable
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `data/nostr/NostrSessionManager.kt` (`disableDeadRelay`'s and `reconcile`'s
  `.onFailure` handlers)
- **Fix:** Both sites now call `logger.e(e) { ... }` / `logger.e(error) { ... }` instead of
  `logger.d { ... }`, keeping the same scrubbed message text as the log line's content — matching
  `RelayConfigViewModel`'s existing correct pattern (see LOG-39's fix).

Found during Phase 2's iteration-2 code re-review. Both handlers routed their caught throwable
through `.d { "... ${scrubThrowableMessageForLogs(e)}" }`, discarding the stack trace that would
otherwise be available for on-device debugging, instead of `.e(e) { ... }`.

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

Found during Phase 2's iteration-3 (final) code re-review, as a residual gap in LOG-49's fix.
LOG-49 correctly serialized `maybeBootstrapOwnProfile`'s own check-then-act sequence, but
`stopOwnProfileBootstrap()` — which mutates the exact same fields — was still reachable unguarded
from `NostrSessionManager.stop()` and from the watcher job's own trailing statement, both outside
any lock, so a stale watcher-job completion could tear down a just-started replacement bootstrap,
or an unguarded `stop()` race could leave `ownProfileBootstrapPubkey` non-null and cause the next
same-pubkey login to skip bootstrapping entirely.

### LOG-54 — InteractionActionsCoordinator still discarded the throwable in two logger.d catch/onFailure sites
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/common/InteractionActionsCoordinator.kt` (`requestSignAndPublish`'s `catch`,
  `publishSignedEvent`'s `onFailure`)
- **Fix:** Both sites now call `logger.e(e) { ... }` instead of `logger.d { ... }`, keeping the
  same scrubbed message text as the log line's content — matching LOG-51's fix in
  `NostrSessionManager` and `RelayConfigViewModel`'s existing correct pattern.

Found during Phase 2's iteration-3 (final) code re-review. This is the same throwable-discarding
pattern LOG-51 fixed in `NostrSessionManager`, present unaddressed in the file this phase's own
`runCatchingCancellable` migration (LOG-43/LOG-46) was actively editing — pre-existing from the
initial commit rather than a regression, but the exact same bug class caught and fixed elsewhere
in a file already under active review.
