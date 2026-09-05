# Phase 3 Dispositions Ledger — Non-Test-Closable Entries

This ledger records, with quoted source evidence, the disposition of the 16
`docs/KNOWN_ISSUES.md` entries that cannot be closed by a unit test: six
logging-visibility fixes sitting behind a non-injected logger (Group D), five
fixes blocked by `NostrSessionManager`'s untestable concrete dependencies plus
`UserRepositoryImpl`'s own dependency on the same class of blocker (Group C +
LOG-4), and five entries that genuinely need a human looking at a running app
(Group G). No production or test source file was modified while producing
this ledger, and no emulator/instrumented test was run.

Three sections follow: **Source-read verified (moves to DONE.md)**,
**Blocked by an untestable class (stays in KNOWN_ISSUES.md)**, and **Needs the
user's own device pass (stays in KNOWN_ISSUES.md)**.

---

## Section 1 — Source-read verified (moves to DONE.md)

Per D-09: these six entries are one-line logging-visibility fixes inside a
class that builds its own `UmbraLogger` internally
(`private val logger = UmbraLog.tag(TAG)`) rather than receiving one as a
constructor parameter. No unit test can intercept a log call made through a
non-injected logger in this codebase — see the shared explanation below the
table. "Verified by direct source read" is therefore recorded as the entry's
disposition instead of a test citation, per D-09's third-disposition rule.

| LOG-N | Requirement | Disposition | Evidence (file:line + quoted source) | Rationale text for the tracker |
|---|---|---|---|---|
| LOG-18 | VALID-11 | SOURCE-READ VERIFIED | Logger field: `app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt:217` — `private val logger = UmbraLog.tag(TAG)`. Site 1: `EventRepositoryImpl.kt:429` — `logger.d { "FEED_NOTES EOSE from relay ${scrubUrlForLogs(relayUrl)} reported MORE — not advancing since watermark" }`. Site 2: `EventRepositoryImpl.kt:487` — `logger.d { "Re-applied ${channelFilters.size} channels to relay ${scrubUrlForLogs(relayUrl)}" }`. Logger field (second file): `app/src/main/java/com/umbra/app/data/repository/NegentropySyncOrchestrator.kt:65` — `private val logger = UmbraLog.tag(TAG)`. Site 3: `NegentropySyncOrchestrator.kt:119` — `logger.d { "NIP-77 sync with relay failed: ${LogScrubber.scrubThrowableMessageForLogs(e)}" }`. | "Verified by direct source read — `logger` is not constructor-injected in `EventRepositoryImpl`/`NegentropySyncOrchestrator`, so no unit test can assert the logging call; confirmed by inspection of `EventRepositoryImpl.kt:429`, `EventRepositoryImpl.kt:487`, and `NegentropySyncOrchestrator.kt:119` — all three relay-URL/throwable interpolations are routed through `LogScrubber` at their current call sites." |
| LOG-20 | VALID-13 | SOURCE-READ VERIFIED | Logger field: `EventRepositoryImpl.kt:217` — `private val logger = UmbraLog.tag(TAG)` (same class as LOG-18). Fixed call site: `EventRepositoryImpl.kt:502` (inside `clearAllData()`'s `disconnectFromAll()` catch, `:500-503`) — `logger.e(e) { "disconnectFromAll failed during clearAllData; continuing wipe" }`. | "Verified by direct source read — `logger` is not constructor-injected in `EventRepositoryImpl`, so no unit test can assert the logging call; confirmed by inspection of `EventRepositoryImpl.kt:502` — the previously-empty `catch` now logs the throwable at error level before the wipe continues." |
| LOG-28 | VALID-20 | SOURCE-READ VERIFIED | Logger field: `app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt:43` — `private val logger = UmbraLog.tag(TAG)`. Site 1 (`loginAnonymously()`): `LoginViewModel.kt:85` — `logger.e(e) { "Session activation failed" }`. Site 2 (`savePublicKey()`): `LoginViewModel.kt:137` — `logger.e(e) { "Session activation failed" }`. | "Verified by direct source read — `logger` is not constructor-injected in `LoginViewModel`, so no unit test can assert the logging call; confirmed by inspection of `LoginViewModel.kt:85` and `LoginViewModel.kt:137` — both inner `activateUserSession(...)` catches now log the caught exception instead of discarding it." |
| LOG-39 | VALID-27 | SOURCE-READ VERIFIED | Logger field: `app/src/main/java/com/umbra/app/ui/relay/RelayConfigViewModel.kt:191` — `private val logger = UmbraLog.tag(TAG)`. Fixed call site: `RelayConfigViewModel.kt:369-370` (inside `enforceAnonymousRelayPolicyIfNeeded`) — `.onFailure { e -> logger.e(e) { "Failed to enforce anonymous-session relay restriction" } }`. | "Verified by direct source read — `logger` is not constructor-injected in `RelayConfigViewModel`, so no unit test can assert the logging call; confirmed by inspection of `RelayConfigViewModel.kt:369-370` — the previously-unchecked `runCatching` now chains `.onFailure` and logs the throwable." |
| LOG-51 | VALID-34 | SOURCE-READ VERIFIED | Logger field: `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt:151` — `private val logger = UmbraLog.tag(TAG)`. Site 1 (`disableDeadRelay`): `NostrSessionManager.kt:348-349` — `.onFailure { e -> logger.e(e) { "Failed to auto-disable relay: ${scrubThrowableMessageForLogs(e)}" } }`. Site 2 (`reconcile`): `NostrSessionManager.kt:435,437` — `.onFailure { error -> ... logger.e(error) { "Relay connect failed (${state.torStatus}) -> scheduling retry: ${scrubThrowableMessageForLogs(error)}" } }`. | "Verified by direct source read — `logger` is not constructor-injected in `NostrSessionManager`, so no unit test can assert the logging call; confirmed by inspection of `NostrSessionManager.kt:348-349` and `NostrSessionManager.kt:435,437` — both `onFailure` handlers now call `logger.e(...)` instead of `logger.d(...)`, preserving the stack trace." |
| LOG-54 | VALID-37 | SOURCE-READ VERIFIED | Logger field: `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt:49` — `private val logger = UmbraLog.tag("InteractionActionsCoordinator")`. Site 1 (`requestSignAndPublish`'s catch): `InteractionActionsCoordinator.kt:97` — `logger.e(e) { "Error requesting signed event: ${scrubThrowableMessageForLogs(e)}" }`. Site 2 (`publishSignedEvent`'s onFailure): `InteractionActionsCoordinator.kt:116` — `logger.e(e) { "Error publishing event: ${scrubThrowableMessageForLogs(e)}" }`. | "Verified by direct source read — `logger` is not constructor-injected in `InteractionActionsCoordinator`, so no unit test can assert the logging call; confirmed by inspection of `InteractionActionsCoordinator.kt:97` and `InteractionActionsCoordinator.kt:116` — both sites now call `logger.e(...)` instead of `logger.d(...)`." |

**Shared architectural explanation (why a test is impossible in general, not repeated per row):**
`app/src/main/java/com/umbra/app/util/logging/Logger.kt:14-27` gates every
level behind `android.util.Log.isLoggable(tag, level)` — `d()` at line 15,
`w()` at line 19, `e()` at line 23. `app/build.gradle.kts:66-75`'s
`testOptions.unitTests.isReturnDefaultValues = true` (line 73) makes any
unstubbed `android.util.Log` call return its default value under a plain JVM
unit test rather than throwing — for `Log.isLoggable`, that default is
`false`, so every `logger.d`/`.w`/`.e` call in this test config short-circuits
before reaching `Log.d`/`Log.w`/`Log.e` at all, with no observable side
effect a JUnit assertion can catch. The same `app/build.gradle.kts:70` comment
this flag lives beside states outright: "this project has no
Robolectric/Mockito-static dependency to stub it per-test" — confirmed by
`grep -in "mockito\|mockk\|robolectric\|powermock"` over
`gradle/libs.versions.toml` and `app/build.gradle.kts`, which returns only
that same comment line, no actual dependency declaration. A class whose
logger is a constructor parameter (`logger: UmbraLogger`) can substitute
`FakeUmbraLogger` and capture the call directly — that is what separates
these six entries from Plan 03-06's `LogoutUseCase`/`TrimMemoryCachesUseCase`
extensions. All six classes above instead build their logger internally via
`UmbraLog.tag(TAG)`, so no substitution point exists at all; the call's
existence and correctness (scrubbing, level) is confirmable only by reading
the current source, which is what this section does.

---

## Section 2 — Blocked by an untestable class (stays in KNOWN_ISSUES.md)

### Verifying the `NostrSessionManager` blocker (not assumed)

Full constructor, `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt:52-64`:

```kotlin
class NostrSessionManager @Inject constructor(
    private val eventRepository: EventRepository,
    private val relayRepository: RelayRepository,
    private val feedRepository: FeedRepository,
    private val userRepository: UserRepository,
    private val contactListRepository: ContactListRepository,
    private val userPreferences: UserPreferences,
    private val torRuntimeManager: TorRuntimeManager,
    private val relayInfoRepository: RelayInfoRepository,
    private val backfillAnchorStore: BackfillAnchorStore,
    private val bootstrapOwnProfileUseCase: BootstrapOwnProfileUseCase,
    private val relayListDecryptionCoordinator: RelayListDecryptionCoordinator
) : NostrSessionController {
```

Of the 11 constructor parameters, 7 are domain-layer interfaces
(`EventRepository`, `RelayRepository`, `FeedRepository`, `UserRepository`,
`ContactListRepository`, `UserPreferences`, `RelayInfoRepository` — each
confirmed by `grep -n "^interface"` on its own declaration file) and already
have fakes in `app/src/test/java/com/umbra/app/testutil/fakes/`. The
remaining 4 are concrete classes, each individually confirmed:

- `torRuntimeManager: TorRuntimeManager` —
  `app/src/main/java/com/umbra/app/data/tor/TorRuntimeManager.kt:39-42`:
  `class TorRuntimeManager @Inject constructor(@ApplicationContext private val context: Context, private val orBotCheck: OrBotConnectivityCheck) : TorRuntimeController` —
  requires a live `android.content.Context`.
- `backfillAnchorStore: BackfillAnchorStore` —
  `app/src/main/java/com/umbra/app/data/nostr/BackfillAnchorStore.kt:37-39`:
  `class BackfillAnchorStore @Inject constructor(@ApplicationContext context: Context) : BackfillAnchorClearer` —
  also requires a live `android.content.Context`.
- `bootstrapOwnProfileUseCase: BootstrapOwnProfileUseCase` —
  `app/src/main/java/com/umbra/app/domain/usecase/BootstrapOwnProfileUseCase.kt:35-38`:
  `class BootstrapOwnProfileUseCase(private val eventRepository: EventRepository, private val buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase)` —
  a concrete (non-`@Inject`) class; its own two dependencies are
  interface/use-case types, not Context-requiring, so it is not itself the
  irreducible blocker.
- `relayListDecryptionCoordinator: RelayListDecryptionCoordinator` —
  `app/src/main/java/com/umbra/app/data/repository/RelayListDecryptionCoordinator.kt:40-44`:
  `class RelayListDecryptionCoordinator @Inject constructor(private val userRepository: UserRepository, private val userPreferences: UserPreferences, private val nip44Gateway: Nip44Gateway)` —
  also a concrete class whose own dependencies are interfaces, not itself
  Context-requiring.

`ls app/src/test/java/com/umbra/app/testutil/fakes/` lists exactly 8 files
(`FakeContactListRepository.kt`, `FakeEventRepository.kt`,
`FakeMuteListRepository.kt`, `FakeNostrSessionController.kt`,
`FakePinListRepository.kt`, `FakeUmbraLogger.kt`, `FakeUserPreferences.kt`,
`FakeUserRepository.kt`) — none of them is a fake for `TorRuntimeManager`,
`BackfillAnchorStore`, `BootstrapOwnProfileUseCase`, or
`RelayListDecryptionCoordinator`.

**Finding:** `NostrSessionManager` cannot be constructed in a plain JVM unit
test. The irreducible reason is `torRuntimeManager` and `backfillAnchorStore`
alone — both require a real `@ApplicationContext Context`, neither has an
interface seam or a fake, and this project's `app/build.gradle.kts` has no
Robolectric or Android-instrumentation dependency (`grep -in
"robolectric" gradle/libs.versions.toml app/build.gradle.kts` returns only
the unrelated comment at `app/build.gradle.kts:70`) to supply a fake
`Context`. `bootstrapOwnProfileUseCase` and `relayListDecryptionCoordinator`
being concrete rather than interface-typed is a second, independent reason
the class as a whole has no fake-substitution seam today, but is not itself
what makes construction impossible — the Context-requiring pair is.

**Residual limitation, stated honestly:** this is a declaration-level check
(constructor signatures and fake-directory listing), not an attempted compile
of a `NostrSessionManagerTest.kt`. No such file was written or compiled to
confirm the failure mode directly.

### LOG-30, LOG-38, LOG-49, LOG-52 — shared disposition

| LOG-N | Requirement | Disposition | Where | Shared note applied |
|---|---|---|---|---|
| LOG-30 | VALID-22 | BLOCKED | `data/nostr/NostrSessionManager.kt` (`scheduleRetry()`'s `retryJob` check-then-act; sibling Job fields) | yes — see below |
| LOG-38 | VALID-26 | BLOCKED | `data/nostr/NostrSessionManager.kt:150-171,311-398,449-460,596-603,271-287` (`@Volatile` field fix) | yes — see below |
| LOG-49 | VALID-33 | BLOCKED | `data/nostr/NostrSessionManager.kt:427-452` (`maybeBootstrapOwnProfile`'s mutex) | yes — see below |
| LOG-52 | VALID-35 | BLOCKED | `data/nostr/NostrSessionManager.kt` (`stopOwnProfileBootstrapLocked()`, `stop()`) | yes — see below |

**Exact shared note — to be pasted identically into all four of LOG-30,
LOG-38, LOG-49, and LOG-52's `docs/KNOWN_ISSUES.md` entries:**

> A unit test for this fix is currently impossible: `NostrSessionManager`'s
> constructor requires `TorRuntimeManager` and `BackfillAnchorStore`, both
> concrete classes needing a live `android.content.Context` with no
> interface seam and no fake in `app/src/test/java/com/umbra/app/testutil/fakes/`,
> and this project has no Robolectric or mocking dependency to supply one.
> This is the identical architectural gap LOG-44 (`docs/TODO.md`) was
> deferred over. This entry stays in `docs/KNOWN_ISSUES.md` at `fix applied
> — needs on-device validation` — the fix itself is real, deployed,
> working code; only its automated test coverage is blocked. Do not attempt
> an isolated `NostrSessionManagerTest.kt` for this entry before LOG-44's
> interface-seam work lands.

None of these four is moved to `docs/TODO.md` — per D-10, they are working,
deployed fixes, not a pre-existing coverage gap like LOG-44.

### LOG-4 — verified separately (two distinct fixes, one shared blocker)

LOG-4 carries two fixes: (1) a reactive fourth input
(`userPreferences.getPublicKeyFlow()`, confirmed present at
`NostrSessionManager.kt:255`) added to the same `NostrSessionManager` class's
relay-reconciliation `combine(...)` — already covered by the blocker verified
above; and (2) the atomic staleness check-and-write in
`UserRepositoryImpl.saveRelayList()`, verified independently below.

`UserRepositoryImpl`'s full constructor,
`app/src/main/java/com/umbra/app/data/repository/UserRepositoryImpl.kt:61-67`:

```kotlin
class UserRepositoryImpl @Inject constructor(
    @Named("encrypted") private val userProfileDao: UserProfileDao,
    private val userPreferences: UserPreferences,
    private val relayRepository: RelayRepository,
    private val nip05Repository: Nip05Repository,
    private val imagePrefetcher: ImagePrefetcher
) : UserRepository {
```

`ImagePrefetcher`'s constructor,
`app/src/main/java/com/umbra/app/util/ImagePrefetcher.kt:27-30`:

```kotlin
class ImagePrefetcher @Inject constructor(
    private val imageLoader: ImageLoader,
    @ApplicationContext private val context: Context,
    private val mediaLoadPriorityGate: MediaLoadPriorityGate
) {
```

`imagePrefetcher: ImagePrefetcher` is a concrete class (not an interface)
requiring both a Coil `ImageLoader` and a live `@ApplicationContext Context`
— it has no fake in `testutil/fakes/` either. `grep -rl
"android.content.Context" app/src/test/` returns **zero files** — no JVM
unit test anywhere in this codebase constructs an Android `Context` today,
confirming `ImagePrefetcher` (and by extension `UserRepositoryImpl`, and
`TorRuntimeManager`/`BackfillAnchorStore` above) sit outside this project's
current unit-test reach. `grep -in "mockito\|mockk\|robolectric\|powermock"`
over `gradle/libs.versions.toml`/`app/build.gradle.kts` again returns only
the unrelated comment — no mocking or Robolectric dependency exists that
could substitute a fake `Context`.

The atomic-compute fix itself is present in current source,
`UserRepositoryImpl.kt:265-267`:

```kotlin
val accepted = relayLists.compute(relayList.pubkey) { _, existing ->
    if (existing != null && existing.lastUpdated >= relayList.lastUpdated) existing else relayList
} === relayList
```

**Disposition: BLOCKED.** No JVM-constructible path exists for either half
of this fix — `NostrSessionManager` for the first, `UserRepositoryImpl` (via
its `ImagePrefetcher` dependency) for the second — and no existing test
constructs an Android `Context` anywhere in this repository.

**Tracker rationale text:** "Both fixes are present in current source at the
quoted lines (`NostrSessionManager.kt:255` for the reactive fourth combine
input, `UserRepositoryImpl.kt:265-267` for the atomic staleness
check-and-write). The real-race test this phase intended for LOG-4 (D-02) is
not currently possible: `NostrSessionManager` requires
`TorRuntimeManager`/`BackfillAnchorStore` (both need a live
`android.content.Context`), and `UserRepositoryImpl` requires
`ImagePrefetcher` (needs both a live `Context` and a Coil `ImageLoader`) —
none of the three has an interface seam, a fake, or a Robolectric/mocking
dependency available in this project, and no existing JVM unit test anywhere
in the suite constructs an Android `Context`. This entry stays in
`docs/KNOWN_ISSUES.md` alongside LOG-30/38/49/52."
