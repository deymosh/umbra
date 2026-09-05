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
