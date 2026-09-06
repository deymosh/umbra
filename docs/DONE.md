# DONE

Append-only completed-work log — validated bug fixes originally logged in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md), and completed backlog items originally logged in
[TODO.md](TODO.md). See [`.claude/CLAUDE.md`](../.claude/CLAUDE.md)'s "Bug tracking" section for
the full convention. Entries move here verbatim from their source file:
- A bug fix moves once validated, with a `**Validated:**` date appended.
- A backlog item moves once shipped, with a `**Completed:**` date appended.

Never edit a past entry beyond adding that one line.

### LOG-5 — "Create new filter" reopens the last-edited filter in stale edit mode
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-20
- **Where:** `ui/feedconfig/FeedConfigViewModel.kt` (`openAddDialog()`/`closeAddDialog()`) /
  `ui/feedconfig/FeedFilterEditScreen.kt`
- **Fix:** `openAddDialog()` now explicitly clears `editingFilter`, and `FeedFilterEditScreen`
  adds a local `BackHandler` calling `closeAddDialog()` so system back/gesture resets state at
  the source, not just the explicit close (X) button. See
  `claude/relay-client-and-subscriptions-ui` branch.
- **Validated:** 2026-08-20

Reported by the user: tap "Edit" on an existing filter, don't save, go back, then tap "create
new filter" — it reopens in edit mode of the previously-tapped filter instead of a blank create
form. Root cause: `FeedConfigViewModel`'s `editingFilter`/`showAddDialog` state is scoped to the
shared nested-nav-graph ViewModel (`FeedConfig`/`FeedFilterEdit` share one instance), and while
the in-screen close icon correctly calls `closeAddDialog()` (which clears `editingFilter`), the
system back button/gesture bypasses it entirely — `NavHost.kt`'s global `BackHandler` just pops
the nav stack with no knowledge of this ViewModel's state, leaving `editingFilter` set. The next
`openAddDialog()` call (from the "+" button) never cleared it either, so `FeedFilterEditScreen`
read the stale `editingFilter` and rendered pre-filled in edit mode.

### LOG-8 — Add a three-dot overflow menu to the repost banner
- **Status:** backlog
- **Added:** 2026-08-21
- **Why:** requested by the user — a normal note's EventCard has a three-dot menu (pin/unpin,
  mute, copy id/content/nevent/json, delete), but the "X reposted" banner above a NIP-18
  kind-6/16 repost had no menu at all, only tap-to-profile on the reposter.

Threaded the repost event through end-to-end (`ResolvedFeedEvent`, `NoteView`,
`FeedViewModel`/`ProfileViewModel` state, `NotesFeedSection`) alongside the already-threaded
`repostedByPubkey`/`repostedAt`, and added a right-aligned overflow button to `RepostBanner`
matching `NoteHeader`'s kebab in size/style. Per the user's explicit instruction ("este menú
tiene que tener las mismas opciones que cualquier evento normal, ni más ni menos"), the repost's
menu offers exactly the same actions a normal note's menu does — `EventCard`'s action-list
building was extracted into a shared `eventActionItems()` helper and invoked once for the note
and once for the repost event when present, instead of a smaller bespoke set.
**Completed:** 2026-08-21
**From:** TODO LOG-8

### LOG-9 — Add a developer option to inspect the encrypted database
- **Status:** backlog
- **Added:** 2026-08-21
- **Why:** requested by the user — wanted to be able to browse tables and search events in the
  SQLCipher-encrypted DB directly, for debugging during development.

Added a "DB Inspector" screen, reachable from Settings' Developer section as a sibling row to
Developer Options / App Resource Usage (same navigational pattern as `AppResourceUsageScreen`,
not nested inside the toggle-only `DeveloperOptionsScreen`): per-table row counts across all 6
tables, plus a kind/pubkey/content-substring search over the `events` table with a raw detail
view (id/pubkey/kind/created_at/content/tagsJson/sig). Deliberately a fixed set of read-only,
parameterized queries rather than arbitrary SQL passthrough, given the DB's security-sensitive,
encrypted nature. New `domain/repository/DbInspectorRepository.kt` interface (+
`DbInspectorRepositoryImpl`) keeps `ui/devoptions/dbinspector/` from importing `data/db/dao/*`
directly, consistent with the existing `ui -> domain -> data` layering.
**Completed:** 2026-08-21
**From:** TODO LOG-9

### LOG-10 — New feed events should push down existing content, not auto-scroll
- **Status:** backlog
- **Added:** 2026-08-21
- **Why:** requested by the user — a prior auto-scroll attempt (`FeedScreen.kt`'s
  `LaunchedEffect(Unit)` animate-scroll-to-top on new-head arrival) didn't feel right; wanted
  the Amethyst-style behavior where a new note simply pushes existing content down with no
  visible scroll.

`NotesFeedSection`'s `LazyColumn` already keys items by stable event id, so LazyColumn's own
scroll-position preservation already makes a newly-prepended note "push down" existing content
without any explicit scroll call — the animate-scroll `LaunchedEffect` was fighting behavior
that already worked correctly on its own. Removed the `LaunchedEffect(Unit)` block and its
now-unused `scrollToTopAnimated()` helper; `scrollToTopImmediate()` (used by the explicit
"go to top" affordances) is untouched.
**Completed:** 2026-08-21
**From:** TODO LOG-10

### LOG-15 — Add tap-to-explain info icons for relay type sections
- **Status:** backlog
- **Added:** 2026-08-22
- **Why:** the app had zero tap-to-explain affordance anywhere, and the meaning of
  Outbox/Inbox/DM/Search/Index/Discovered relay sections isn't obvious to a new user.

New `ui/components/InfoIcon.kt` (modeled on `ConfirmDialog.kt`'s plain `AlertDialog` wrapper
shape): a small `IconButton` showing `Icons.Default.Info` that opens a dismiss-only `AlertDialog`
with a title/message on tap. `SectionHeader.kt` and `RelaySections.kt`'s `relayRoleSection()` gained
an optional `infoContent` composable slot, wired into all 8 relay-section headers in
`RelayConfigScreen.kt` (Outbox/Inbox/DM/Search/Index/three Discovered variants) plus the five real
per-relay-type toggle rows in `RelayEditDialog.kt` (Read/Write/DM/Search/Index — the sixth "AUTH
NIP-42" row is a disabled switch mirroring DM for display, not an independent flag, so it shares
the DM explanation rather than getting its own). New `relay_help_*_body` strings in `strings.xml`
(a `relay_info_*` prefix was already taken by unrelated NIP-11 fetch-status strings).
**Completed:** 2026-08-22
**From:** TODO LOG-15

### LOG-16 — Default NIP-77 sync direction to download-only; drop redundant checkmark
- **Status:** backlog
- **Added:** 2026-08-22
- **Why:** a fresh/untouched sync-direction setting shouldn't silently start publishing (uploading)
  a user's local relay set to a relay without them opting in first; the segmented-button's
  selection checkmark was redundant since color already indicates the selected option.

Changed the default from `SyncDirection.BOTH` to `SyncDirection.DOWNLOAD_ONLY` in the three places
that actually assign it: `SyncPreferencesImpl.loadDirection()`'s fallback (the authoritative one —
what a fresh install/untouched setting resolves to), `RelayConfigState.negentropySyncDirection`'s
initial value, and `NegentropySyncOrchestrator.sync()`'s `direction` parameter default. Updated
three `NegentropySyncOrchestratorTest` cases that relied on the old default to publish (upload) —
they now pass `direction = SyncDirection.BOTH` explicitly, matching their actual intent of testing
push/publish behavior rather than the default. Also added `icon = {}` to `NegentropySyncCard`'s
`SegmentedButton` to suppress Material3's default active-state checkmark, leaving color-based
selection indication untouched.
**Completed:** 2026-08-22
**From:** TODO LOG-16

### LOG-25 — FeedScreen's logout flow swallowed exceptions with an empty catch block
- **Status:** fixed (validated by shipping — this entry retroactively closes the gap where
  an earlier cleanup fixed it ad hoc without filing a LOG-N entry first)
- **Found:** 2026-08-29
- **Where:** `ui/feed/FeedScreen.kt` (the logout flow's try/catch around `loginViewModel.logout()`)

The logout flow previously discarded any exception from `loginViewModel.logout()` with an
empty catch block, then navigated to the login screen unconditionally — a failed logout (e.g.
a database wipe leaving stale key material behind) was indistinguishable from a successful
one, with no record anywhere that it happened. The fix still navigates to the login screen
either way (there's no in-app state left to usefully retry from), but now logs the throwable
via the project's scrubbed logging utility so a failure is at least visible in a debug build's
logcat.
**Completed:** 2026-08-29
**Fix:** applied in the logout flow

### LOG-17 — Publish failure logs drop the throwable and emit at debug level
- **Status:** in progress
- **Added:** 2026-08-24
- **Why:** Found across three separate logging-migration plans and deliberately not fixed in any of them, to keep each migration a behaviour-preserving 1:1 translation with zero regressions. Folded into this single entry at the migration closeout instead of filed as three duplicates, since all eight sites below share the same root cause and the same fix shape.

- Eight sites share this shape:

- ~~`domain/usecase/PublishEventUseCases.kt` — `PublishSignedEventUseCase`/`PublishAuthEventUseCase`'s two `.onFailure` handlers~~ — fixed, commit `aebd2db`
- `ui/auth/LoginViewModel.kt:97,143,222` — anonymous login failure, save-public-key failure, logout failure
- `data/nostr/UmbraNostrClient.kt`'s `logWebSocketFailure` non-SOCKS branch, `data/nostr/RelayMessageHandling.kt`'s `onWebSocketMessage` catch block, `data/nostr/RelayWebSocketListener.kt`'s incoming-drain `onFailure` handler

All eight are debug-level, so release builds already filter them — the stack-trace loss is invisible in release regardless. The fix for each is a deliberate, individually-reviewable promotion to `UmbraLogger`'s three-argument exception overload (`logger.e(throwable) { ... }`), which attaches the throwable and auto-scrubs its message — not something to fold into a migration diff, since a level promotion (DEBUG to ERROR) is itself a real behaviour change whose release-log-visibility impact should be weighed per site.
**Completed:** 2026-09-02
**From:** TODO LOG-17

### LOG-43 — Broad catch (e: Exception)/unchecked runCatching around suspend calls swallow CancellationException across Phase 2's write paths
- **Status:** in progress
- **Added:** 2026-09-04
- **Why:** Found during Phase 2's code review as a systemic pattern across most of the phase's
  reviewed write paths, not a single-file bug — tracked here as the umbrella item while individual
  sites are fixed as part of Phase 2's code-review remediation pass.

`CancellationException` is a subtype of `Exception` in Kotlin, so `catch (e: Exception) { ... }`
(and unchecked `runCatching { ... }`) catches it without rethrowing, defeating structured
cancellation. Representative sites (not exhaustive): `InteractionActionsCoordinator.kt:90-95`
(`requestSignAndPublish`), `ProfileViewModel.kt:611-616` (`requestSignEvent`),
`RelayCrudCoordinator.kt:134-141, 163-170, 214-218, 337-352` (`saveRelay`, `deleteRelay`,
`removeRelayRole`, `updateRelayRole`), `NostrSessionManager.kt:302-308` (`disableDeadRelay`),
`RelayConfigViewModel.kt:331-333, 352-365, 520-521` (`applyRelaysSnapshot`,
`enforceAnonymousRelayPolicyIfNeeded`, `loadRelayInfo`). This project's own
`kotlin-coroutines-structured-concurrency` skill documents this exact anti-pattern. Fix: add
`catch (e: CancellationException) { throw e }` before the generic catch at each site (or check
`result.exceptionOrNull() is CancellationException` and rethrow for `runCatching` variants);
consider a small shared helper given how many sites share this shape.

Every representative site listed above now rethrows `CancellationException` instead of swallowing
it: the four plain `try`/`catch (e: Exception)` sites (`InteractionActionsCoordinator`,
`ProfileViewModel`, and `RelayCrudCoordinator`'s three) gained an explicit
`catch (e: CancellationException) { throw e }` before their generic catch, and the four
`runCatching` sites (`NostrSessionManager.disableDeadRelay`, `RelayConfigViewModel`'s
`applyRelaysSnapshot`/`enforceAnonymousRelayPolicyIfNeeded`/`loadRelayInfo`) now use a new shared
`util/coroutines/CancellableRunCatching.kt` (`runCatchingCancellable`) instead of the stdlib
`runCatching`, matching the shared-helper suggestion above rather than hand-rolling the check at
each of the four call sites.
**Completed:** 2026-09-04
**From:** TODO LOG-43

### LOG-45 — RelayCrudCoordinator.relayRoleMutexes is never pruned
- **Status:** backlog
- **Added:** 2026-09-04
- **Why:** Found during Phase 2's code review — explicitly flagged by the reviewer as low priority
  and not urgent; logged for the record rather than fixed immediately.

`ConcurrentHashMap<String, Mutex>()` gains one entry per distinct relay id ever toggled through
`updateRelayRole`, for the coordinator's lifetime (i.e. the `RelayConfigViewModel`'s lifecycle).
Practically bounded by the number of relays a user ever interacts with in one screen session, so
unlikely to matter in practice — an unbounded-growth structure with no removal path. Fix (not
urgent): an LRU-bounded map, or remove an entry once a relay is deleted (`deleteRelay`).

Turned out straightforward: `deleteRelay` now calls `relayRoleMutexes.remove(relayId)` once its
own `removeRelayUseCase` call (and the per-relay lock guarding it, see WR-03) completes. A caller
racing in for the now-deleted id right after gets a fresh, unlocked `Mutex` from
`computeIfAbsent` and no-ops harmlessly once `updateRelayRole`'s own `getRelayById` lookup finds
nothing — same as any other unknown `relayId`.
**Completed:** 2026-09-04
**From:** TODO LOG-45

### LOG-48 — AtomicJobScheduling.launchReplacing's docstring overstated its cancel-before-start ordering guarantee under concurrent unsynchronized callers
- **Status:** backlog
- **Added:** 2026-09-04
- **Why:** Found during Phase 2's iteration-2 code re-review — the guarantee only holds within a
  single caller's own three-step sequence, not across genuinely concurrent unsynchronized callers
  on the same `AtomicReference`, but practical impact for both current call sites is low (the
  displaced work is itself idempotent/re-derivable), so a docstring correction was preferred over
  a structural fix in this second fixer pass to avoid compounding risk.

`launchReplacing`'s docstring claimed the displaced job is always fully cancelled before the
replacement starts. Only the `getAndSet` on the `AtomicReference` is atomic — the cancel and the
subsequent `start()` are two separate, unsynchronized statements. Under two genuinely concurrent
callers on the same reference with no external synchronization, the first candidate can still be
running when the second's `start()` executes (cooperative cancellation only takes effect at the
next suspension point), so both bodies can execute concurrently for a window — exactly what the
docstring claimed couldn't happen.

Docstring rewritten to state the guarantee accurately: cancel-before-start ordering holds
per-call, not per-reference; a caller that cannot tolerate the overlap must serialize its own
calls to this function. Both current call sites (`EventIngestCache.insertDebounceJob`,
`NostrSessionManager.userBackfillJob`/`ownProfileBootstrapWatcherJob`) already tolerate the
overlap for the reasons noted above, so no structural change was needed.
**Completed:** 2026-09-04
**From:** TODO LOG-48

### LOG-50 — No dedicated unit test for runCatchingCancellable
- **Status:** backlog
- **Added:** 2026-09-04
- **Why:** Found during Phase 2's iteration-2 code re-review — `runCatchingCancellable` is now
  shared, load-bearing infrastructure (`NostrSessionManager`, `RelayConfigViewModel`, and per
  LOG-46 now `InteractionActionsCoordinator`), but had no direct regression coverage of its own.

`CancellableRunCatching.kt` had no `CancellableRunCatchingTest.kt` asserting its two behaviors
directly: a thrown `CancellationException` propagates instead of being captured, and any other
`Throwable` is captured into `Result.failure` exactly like the stdlib version.

Added `app/src/test/java/com/umbra/app/util/coroutines/CancellableRunCatchingTest.kt` with three
cases: `CancellationException` propagates, a plain exception becomes `Result.failure`, and a
normal return value is wrapped into `Result.success`.
**Completed:** 2026-09-04
**From:** TODO LOG-50

### LOG-55 — No regression test exercised RelayCrudCoordinator.saveRelay's merge-branch fresh-read fix
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `test/.../ui/relay/RelayCrudCoordinatorTest.kt`
- **Fix:** Added a test that seeds a relay directly into `RecordingRelayRepository` while leaving
  it out of the `state.value.relays` passed to `subject()` (simulating the throttled UI mirror not
  having caught up yet), then calls `saveRelay` with a blank id and the same URL, and asserts the
  repository still holds exactly one row for that URL with both the pre-existing and newly-merged
  flags set — proving the merge path resolves `existingRelay` via a fresh repository read rather
  than the stale mirror (LOG-53's fix).
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` —
  `given a relay already in the repository but not yet reflected in the throttled state mirror when saveRelay is called with a blank id then it merges into the existing row instead of creating a duplicate`

Found during Phase 2's iteration-3 (final) code re-review. `RelayCrudCoordinatorTest` had strong
concurrency coverage for `updateRelayRole`'s per-relay-id lock, but nothing called `saveRelay` at
all, so the LOG-47/LOG-53 merge-branch fixes had zero test coverage in either direction.


### LOG-1 — Stale kind-0/replaceable-event revisions can linger in EventLruCache
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-19
- **Where:** `data/repository/EventRepositoryImpl.kt` (ingestion pipeline) / `data/repository/cache/EventLruCache.kt`
- **Fix:** `domain/nip01/ReplaceableEventKey.kt` (new) + `EventRepositoryImpl`'s ingestion
  pipeline now proactively evicts a superseded replaceable-event revision (NIP-01/33 rules,
  same race-tiebreak idiom as `OwnerTagSetCache.ingest()`) the moment a newer one for the
  same pubkey+kind[+d-tag] is ingested, instead of leaving both id-keyed entries to coexist
  until the LRU reclaims the old one. See `claude/optimize-replaceable-event-cache` branch.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/data/repository/EventRepositoryIngestionIntegrationTest.kt` — `given two revisions of a replaceable slot delivered in ascending timestamp order then only the newer revision is retrievable`; `given two revisions of a replaceable slot delivered in descending timestamp order then only the newer revision is retrievable`

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/domain/model/EventModelBehaviorTest.kt` — `given_noExplicitTolerance_when_checkingFuture_then_theDefaultBehavesAsZeroTolerance`; `app/src/test/java/com/umbra/app/ui/feed/FeedStateMergeCoordinatorTest.kt` — `given a future dated note in notesFlow when computedFeedFlow computes the visible set then it is excluded while a past dated note is kept`. The two remaining `futureEventRecheckTicker()` call sites were verified by reading current source rather than by a test: `app/src/main/java/com/umbra/app/ui/profile/ProfileViewModel.kt:295` — `.combine(futureEventRecheckTicker()) { result, _ -> result }`, feeding into the filter at `ProfileViewModel.kt:298-299`: `n.event.isFromFuture() || (n.repostedAt != null && isTimestampFromFuture(n.repostedAt))`; and `app/src/main/java/com/umbra/app/ui/feed/ThreadViewModel.kt:287` — `futureEventRecheckTicker()` inside the same four-source `combine(...)` that feeds `processThreadGraph`, whose own future check is at `ThreadViewModel.kt:443`: `val descendants = collectDescendants(anchor.id, allEvents).filterNot { it.isFromFuture() }`.

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/common/FutureEventRecheckTickerTest.kt` — `given ticker when collecting first tick then it emits immediately with no delay`

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/data/nostr/UmbraNostrClientTest.kt` — `given a superseded socket when its open callback arrives late then it is closed and the relay is not marked active`; `given the current socket when its open callback arrives then the relay is marked active and its failure backoff is cleared`; `given a dial already in flight for a relay when a second concurrent connect for the same relay runs then it no-ops instead of starting its own dial`

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

### LOG-14 — Promoting a discovered relay to an owned role does nothing
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-22
- **Where:** `ui/relay/RelayConfigViewModel.kt` (`saveRelay()`)
- **Fix:** `sanitizedRelay` now forces `isDiscovered = false` right after it's computed (covers
  the direct-edit and brand-new-relay branches), and the `existingRelay.copy(...)` merge branch
  (hit when a typed/edited URL matches an already-known relay) now sets it explicitly too, since
  that branch copies from `existingRelay` rather than `sanitizedRelay`. See
  `claude/relay-screen-usability-fixes` branch.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` — `given a discovered relay when saveRelay assigns it an owned role then the persisted relay is no longer discovered`; `given a discovered relay already in the repository when saveRelay is called with a blank id and the same url then the merged row is no longer discovered`

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt` — `given a non-owned addressable event resident only in the in-memory cache when an a-tag deletion targets it then it is removed from the cache`; `given an a-tag deletion signed by a different pubkey than the coordinate's author when applied then the in-memory event is not removed`; `given an in-memory addressable event newer than the deletion's own created_at when applied then it is not removed`

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt` — `given eight overlapping coroutines calling scheduleSnapshotEmit concurrently when time advances then exactly one snapshot and one bundle emission occur with no event lost`; `given a scheduled snapshot emit when cancelPendingSnapshotEmit runs then no emission occurs and a repeated cancel is a harmless no-op`

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/common/InteractionActionsCoordinatorTest.kt` — `given amber signs the delete when deleteEvent runs then onDeleteConfirmed and the cache removal fire only after the sign resolves`; `given amber rejects the delete when deleteEvent runs then onDeleteConfirmed never fires and the cache and archive are untouched`; `given deleteEvent's owner check fails then neither the sign round trip nor onDeleteConfirmed fire`

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/domain/feed/FeedFilterTest.kt` — `given a filter with a persisted-looking id when merging then the merged id is the fixed synthetic id, never the input's`

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/feed/FeedViewModelStateTest.kt` — `given a successful mute write when mapping the result then returns the mute success message`; `given a failed mute write when mapping the result then returns the mute error message with the failure text`; `given a failed mute write with a null exception message when mapping the result then the formatted argument is empty`; `given a successful pin write when the note was previously unpinned then returns the pinned success message`; `given a successful pin write when the note was previously pinned then returns the unpinned success message`; `given a failed pin write when the note was previously unpinned then returns the pin error message with the failure text`; `given a failed pin write when the note was previously pinned then returns the unpin error message with the failure text`

`muteUser`'s and `togglePin`'s `onSigned` callbacks call `applyMuteChange`/`applyPinChange` without
inspecting the returned `Result<Unit>`. If the local mute-list/pin-list write fails after Amber has
already confirmed and published the NIP-51 update, the UI still shows the optimistic success
message even though local state is now out of sync with what was published. Confirmed pre-existing
— the original inline `muteListRepository.mute()`/`pinListRepository.pin()`/`unpin()` calls were
likewise fire-and-forget before this extraction. `ProfileViewModel`'s own `toggleMute`/`togglePin`
already check `result.isSuccess` and surface an error message on failure, so the fix is to make
`FeedViewModel`'s equivalents do the same instead of unconditionally showing success.

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt` — `given_nostrSessionControllerStopThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`; `given_userRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`; `given_contactListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`; `given_muteListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`; `given_pinListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`; `given_clearBackfillAnchorsThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`; and `app/src/test/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCaseTest.kt` — `given_userRepositoryPruneStaleDataThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`; `given_contactListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`; `given_muteListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`; `given_pinListRepositoryTrimMemoryThrows_when_invoked_then_loggerRecordsErrorWithSameThrowable`

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
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` — `given two overlapping role toggles on the same relay when both resolve then neither update is lost`; `given overlapping role toggles on different relays when advanced then the un-gated relay is not serialized behind the gated one`

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

### LOG-31 — RelayCrudCoordinator.setDmEnabled marks the DM relay list dirty even when the enable is rejected
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-02
- **Fix:** Moved the `dmRelayListDirty = true` update into the mapper lambda, after the
  transport-rejection branch and immediately before the `relay.copy(...)` that actually changes
  the relay, so the flag only flips on the branch that produces a genuinely changed relay.
  Covered by `RelayCrudCoordinatorTest` (rejected `ws://` non-onion enable, accepted `wss://`
  enable, unknown relayId).
- **Where:** `ui/relay/RelayCrudCoordinator.kt` (`setDmEnabled`)
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` — `given a plaintext non-onion relay when setDmEnabled(true) runs then dmRelayListDirty stays false and the transport error is surfaced`; `given a wss relay when setDmEnabled(true) runs then dmRelayListDirty is set and the persisted relay is DM-active`; `given an unknown relayId when setDmEnabled(true) runs then dmRelayListDirty stays false and nothing is persisted`

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

### LOG-34 — Logger.e() leaks the raw, unscrubbed Throwable via Android's own stack-trace formatting, bypassing LogScrubber entirely
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-03
- **Where:** `util/logging/Logger.kt:23-27` (`e()`)
- **Fix:** `LogScrubber.scrubThrowableForLogs()` (new) returns a replacement `Throwable` — original
  stack frames, scrubbed message, no cause — and `Logger.e()` now passes that to `Log.e()` instead
  of the original throwable. Also moved `LogScrubber.kt` into `util/logging/` alongside
  `Logger`/`UmbraLog`, since it exists purely to serve them.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/util/logging/LogScrubberTest.kt` — `given_throwableWithSensitiveMessage_when_scrubbingForLogs_then_returnedThrowableMessageIsRedacted`; `given_throwableWithCause_when_scrubbingForLogs_then_causeChainIsDropped`; `given_throwable_when_scrubbingForLogs_then_originalStackFramesArePreserved`

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

### LOG-37 — RelayCrudCoordinator.removeRelayRole bypasses the per-relay Mutex LOG-29 added for its sibling setters
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayCrudCoordinator.kt:174-220` (`removeRelayRole`)
- **Fix:** `removeRelayRole` no longer runs its own independent read-map-persist sequence — it now
  calls `updateRelayRole` (the same chokepoint the five `set*Enabled` setters route through) with a
  mapper that clears the target role's enable/active flags, so it picks up the per-relay-id `Mutex`
  and the fresh `relayRepository.getRelayById` read for free. `RelayCrudCoordinatorTest`'s existing
  two overlapping-role-toggle cases continue to pass unmodified.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` — `given removeRelayRole overlapping a role setter on the same relay when both resolve then neither the removal nor the flag flip is lost`

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

### LOG-40 — EventIngestCache.scheduleInsert's cancel-and-replace ordering lets the old and new debounce jobs run concurrently
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `data/repository/EventIngestCache.kt:513-531` (`scheduleInsert`)
- **Fix:** `scheduleInsert` now routes through `AtomicJobScheduling.launchReplacing` (already used
  elsewhere in this file/package), which builds the replacement job lazily and only `start()`s it
  after the previous job is cancelled — restoring the cancel-strictly-before-start ordering this
  method's hand-rolled version got backwards. `Dispatchers.IO` moved inside the block via
  `withContext` since `launchReplacing` launches on `repoScope` directly.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/data/nostr/AtomicJobSchedulingTest.kt` — `given an active slot when replacing scheduled then the new block runs and the old job is cancelled`; `given eight genuinely parallel replacing schedulers on real threads when racing then exactly one survives`. The fix was a migration to this already-tested generic helper, confirmed by source read at `EventIngestCache.kt:545` — `insertDebounceJob.launchReplacing(repoScope) { ... }`.

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `data/repository/EventIngestCache.kt:278-283`, `:492-498` (`cacheRepostTarget`,
  `cacheVerifiedRepostTarget`)
- **Fix:** extracted `storeEventLocked` (the replaceable-key-aware `latestReplaceableEventId`/
  `winsReplaceableRace` logic `ingest()` already ran) as a shared private helper both `ingest()`
  and `cacheRepostTarget` now call while holding `cachedEventsMutex`, so a repost-embedded
  replaceable/parameterized-replaceable event participates in the same one-revision-per-slot
  invariant as a directly-ingested one. Two new `EventIngestCacheTest` cases cover both directions
  (a repost-cached older revision superseded by a later direct ingest, and a direct-ingested newer
  revision correctly rejecting an older one arriving via `cacheRepostTarget`).
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt` — `given a replaceable event cached via cacheRepostTarget when a newer direct-ingest revision arrives then the repost-cached older one is superseded`; `given a newer replaceable revision already ingested when an older revision arrives via cacheRepostTarget then it is dropped instead of coexisting`

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
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayCrudCoordinator.kt:51-143` (`saveRelay`), `:145-172` (`deleteRelay`)
- **Fix:** `saveRelay`'s update-existing-relay and merge-onto-existing-relay branches, and
  `deleteRelay`'s removal, now acquire the same `relayRoleMutexes.computeIfAbsent(relayId) { Mutex() }`
  lock `updateRelayRole` uses, so a role toggle racing an edit/delete for the same relay id can no
  longer silently lose one write. `saveRelay`'s brand-new-relay branch is left unlocked
  deliberately — a freshly generated id can't race anything, since nothing else has seen it yet.
  `RelayCrudCoordinatorTest`'s existing cases continue to pass unmodified.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` — `given saveRelay overlapping a role setter on the same relay id when both resolve then the second write waits for the first`; `given deleteRelay overlapping a role setter on the same relay id when both resolve then the removal waits for the role write`

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

### LOG-46 — InteractionActionsCoordinator.mirrorMuteIntoActiveFilter used stdlib runCatching, swallowing CancellationException
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/common/InteractionActionsCoordinator.kt:135-149` (`mirrorMuteIntoActiveFilter`)
- **Fix:** Migrated to `runCatchingCancellable` (the same helper LOG-43's fix introduced), so a
  `CancellationException` thrown while suspended in `resolveActiveFilter()` or
  `feedRepository.updateMutedAuthors(...)` now propagates instead of being silently captured into
  an ordinary `Result.failure`.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/util/coroutines/CancellableRunCatchingTest.kt` — `given a block that throws CancellationException when run then it propagates instead of being captured`. The fix was a migration to this already-tested generic helper, confirmed by source read at `InteractionActionsCoordinator.kt:141` — `runCatchingCancellable { ... }` inside `mirrorMuteIntoActiveFilter`.

Found during Phase 2's iteration-2 code re-review, as the one leftover call site LOG-43's
systemic `runCatchingCancellable` migration missed. Both callers (`FeedViewModel.muteUser`,
`ProfileViewModel.toggleMute`) invoke `mirrorMuteIntoActiveFilter` from inside
`requestSignAndPublish`'s `scope.launch { ... }` — a genuinely cancellable coroutine (e.g.
cancelled when the owning ViewModel is cleared mid-mute) — so this was the exact bug class LOG-43
was meant to close, left open in this one file.

### LOG-47 — RelayCrudCoordinator.saveRelay's merge branch based its OR-merged write on a stale pre-lock snapshot
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayCrudCoordinator.kt:82-124` (`saveRelay`, existing-relay merge branch)
- **Fix:** The merge branch now re-reads the base relay from
  `relayRepository.getRelayById(existingRelay.id)` (falling back to `existingRelay` only if that
  lookup returns null) *inside* the per-relay-id `withLock` block, the same pattern
  `updateRelayRole` already uses, before computing the OR-merged result.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` — `given a relay already in the repository but not yet reflected in the throttled state mirror when saveRelay is called with a blank id then it merges into the existing row instead of creating a duplicate`. This is the same test cited by LOG-53 and LOG-55 — one regression test covering the cumulative hardening of `saveRelay`'s merge branch, not three independent proofs.

Found during Phase 2's iteration-2 code re-review, as a residual gap in LOG-42's fix. LOG-42
correctly added the per-relay-id mutex to this merge branch, but the merge itself still computed
its OR-merged write (`existingRelay.X || sanitizedRelay.X` — every flag only ever turns a role on)
against `existingRelay`, captured from the throttled `state.relays` UI mirror *before* the lock
was acquired. A concurrent role-disable landing in that window was silently reverted by the
stale-based OR merge even though the mutex prevented the two writes from corrupting each other —
the lock serialized the writes but did nothing to prevent one of them from being computed from
already-stale data.

### LOG-53 — RelayCrudCoordinator.saveRelay's new-relay/URL-collision decision still read the throttled state.value.relays mirror
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayCrudCoordinator.kt:82-99` (`saveRelay`, existing-relay-vs-new branch)
- **Fix:** `existingRelay` is now resolved from a fresh `relayRepository.getAllRelays().first()`
  read first, falling back to the throttled `state.value.relays` mirror only if that repository
  read itself doesn't find a match — consistent with this file's established "fresh read, not
  throttled mirror" principle (see LOG-47) used everywhere else in `updateRelayRole`.
- **Validated:** 2026-09-05
- **Evidence:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` — `given a relay already in the repository but not yet reflected in the throttled state mirror when saveRelay is called with a blank id then it merges into the existing row instead of creating a duplicate`. This is the same test cited by LOG-47 and LOG-55 — one regression test covering the cumulative hardening of `saveRelay`'s merge branch, not three independent proofs.

Found during Phase 2's iteration-3 (final) code re-review, as a second, independent gap in the
same method LOG-47 already fixed. LOG-47 closed the stale-merge-base bug once `existingRelay !=
null` was already known, but the `existingRelay != null` decision itself — whether a URL is
treated as new-vs-existing at all — was still made from `state.value.relays`, populated by a
300ms-throttled collector. A relay added moments earlier (e.g. a double-tap on the add-relay
dialog's save button, or a concurrent NIP-65 sync path adding the same URL) that hadn't yet been
reflected in that mirror routed into the unguarded "add new" branch, and since neither
`AddRelayUseCase` nor `RelayRepository.addRelay` enforces URL uniqueness, this could produce two
`Relay` rows with the same normalized URL and different ids.


### LOG-18 — Three unscrubbed log messages survive the logging migration (EventRepositoryImpl.kt x2, NegentropySyncOrchestrator.kt x1)
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-24
- **Where:** `data/repository/EventRepositoryImpl.kt:428` (`"FEED_NOTES EOSE from relay $relayUrl reported MORE — not advancing since watermark"`), `:486` (`"Re-applied ${channelFilters.size} channels to relay $relayUrl"`), and `data/repository/NegentropySyncOrchestrator.kt:118` (`"NIP-77 sync with relay failed: ${e.message}"`) — line numbers for the first two updated 2026-08-27 after `EventRepositoryImpl.kt` extractions shifted them from :493/:551; same unfixed sites, reconfirmed during code review
- **Fix:** `EventRepositoryImpl.kt`'s two relay-URL interpolations (the feed-EOSE and channel-reapply
  debug logs) now wrap `relayUrl` in `LogScrubber.scrubUrlForLogs()` before logging; `NegentropySyncOrchestrator.kt`'s
  NIP-77 per-relay sync-failure log now wraps the caught exception's message in
  `LogScrubber.scrubThrowableMessageForLogs()` instead of interpolating `e.message` raw. Log level
  is unchanged at all three sites — this was a scrubbing-only fix.
- **Validated:** 2026-09-05 — by direct source read, not by a test
- **Evidence:** "Verified by direct source read — `logger` is not constructor-injected in `EventRepositoryImpl`/`NegentropySyncOrchestrator`, so no unit test can assert the logging call; confirmed by inspection of `EventRepositoryImpl.kt:429`, `EventRepositoryImpl.kt:487`, and `NegentropySyncOrchestrator.kt:119` — all three relay-URL/throwable interpolations are routed through `LogScrubber` at their current call sites."

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
- **Validated:** 2026-09-05 — by direct source read, not by a test
- **Evidence:** "Verified by direct source read — `logger` is not constructor-injected in `EventRepositoryImpl`, so no unit test can assert the logging call; confirmed by inspection of `EventRepositoryImpl.kt:502` — the previously-empty `catch` now logs the throwable at error level before the wipe continues."

Found during review of the repository extraction. Confirmed pre-existing
(present before the extractions began). `try { disconnectFromAll() } catch (_: Exception) { }` — any
failure in `disconnectFromAll()` during a full data wipe (logout/account switch/factory reset) is
swallowed with zero logging. This is on a security/privacy-relevant path: if disconnect genuinely
fails, sockets could remain open and still deliver events while the rest of the wipe proceeds, and
nobody would know from the logs why. Fix: log the exception via `logger.e(e) { "disconnectFromAll
failed during clearAllData; continuing wipe" }` instead of swallowing it silently.

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
- **Validated:** 2026-09-05 — by direct source read, not by a test
- **Evidence:** "Verified by direct source read — `logger` is not constructor-injected in `LoginViewModel`, so no unit test can assert the logging call; confirmed by inspection of `LoginViewModel.kt:85` and `LoginViewModel.kt:137` — both inner `activateUserSession(...)` catches now log the caught exception instead of discarding it."

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

### LOG-39 — RelayConfigViewModel.enforceAnonymousRelayPolicyIfNeeded silently discards failures while enforcing the anonymous-session privacy restriction
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/relay/RelayConfigViewModel.kt:346-367`
- **Fix:** `enforceAnonymousRelayPolicyIfNeeded`'s `runCatching { updateRelayUseCase(...) }` now
  chains `.onFailure { e -> logger.e(e) { "Failed to enforce anonymous-session relay restriction" } }`,
  matching LOG-20's fix shape. `RelayConfigViewModel` gained its own `TAG`/`logger` (it had neither
  before).
- **Validated:** 2026-09-05 — by direct source read, not by a test
- **Evidence:** "Verified by direct source read — `logger` is not constructor-injected in `RelayConfigViewModel`, so no unit test can assert the logging call; confirmed by inspection of `RelayConfigViewModel.kt:369-370` — the previously-unchecked `runCatching` now chains `.onFailure` and logs the throwable."

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

### LOG-51 — NostrSessionManager's onFailure handlers logged a scrubbed message instead of the throwable
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `data/nostr/NostrSessionManager.kt` (`disableDeadRelay`'s and `reconcile`'s
  `.onFailure` handlers)
- **Fix:** Both sites now call `logger.e(e) { ... }` / `logger.e(error) { ... }` instead of
  `logger.d { ... }`, keeping the same scrubbed message text as the log line's content — matching
  `RelayConfigViewModel`'s existing correct pattern (see LOG-39's fix).
- **Validated:** 2026-09-05 — by direct source read, not by a test
- **Evidence:** "Verified by direct source read — `logger` is not constructor-injected in `NostrSessionManager`, so no unit test can assert the logging call; confirmed by inspection of `NostrSessionManager.kt:348-349` and `NostrSessionManager.kt:435,437` — both `onFailure` handlers now call `logger.e(...)` instead of `logger.d(...)`, preserving the stack trace."

Found during Phase 2's iteration-2 code re-review. Both handlers routed their caught throwable
through `.d { "... ${scrubThrowableMessageForLogs(e)}" }`, discarding the stack trace that would
otherwise be available for on-device debugging, instead of `.e(e) { ... }`.

### LOG-54 — InteractionActionsCoordinator still discarded the throwable in two logger.d catch/onFailure sites
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-09-04
- **Where:** `ui/common/InteractionActionsCoordinator.kt` (`requestSignAndPublish`'s `catch`,
  `publishSignedEvent`'s `onFailure`)
- **Fix:** Both sites now call `logger.e(e) { ... }` instead of `logger.d { ... }`, keeping the
  same scrubbed message text as the log line's content — matching LOG-51's fix in
  `NostrSessionManager` and `RelayConfigViewModel`'s existing correct pattern.
- **Validated:** 2026-09-05 — by direct source read, not by a test
- **Evidence:** "Verified by direct source read — `logger` is not constructor-injected in `InteractionActionsCoordinator`, so no unit test can assert the logging call; confirmed by inspection of `InteractionActionsCoordinator.kt:97` and `InteractionActionsCoordinator.kt:116` — both sites now call `logger.e(...)` instead of `logger.d(...)`."

Found during Phase 2's iteration-3 (final) code re-review. This is the same throwable-discarding
pattern LOG-51 fixed in `NostrSessionManager`, present unaddressed in the file this phase's own
`runCatchingCancellable` migration (LOG-43/LOG-46) was actively editing — pre-existing from the
initial commit rather than a regression, but the exact same bug class caught and fixed elsewhere
in a file already under active review.
