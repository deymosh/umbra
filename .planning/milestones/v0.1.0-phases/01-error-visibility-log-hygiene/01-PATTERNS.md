# Phase 1: Error Visibility & Log Hygiene - Pattern Map

**Mapped:** 2026-09-02
**Files analyzed:** 10 production files (15 call sites) + 1 new test-double file + 2 test files
**Analogs found:** 10 / 10 (this phase is call-site hygiene on existing classes — every "file to modify" is itself the best analog for its own fix; the cross-file value here is the *already-shipped sibling fix* and the *DI wiring precedent*)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|-----------------|---------------|
| `domain/usecase/PublishEventUseCases.kt` (LOG-17, 2 sites) | usecase | event-driven (relay publish result) | itself — logger already constructor-injected | exact (fix-in-place) |
| `ui/auth/LoginViewModel.kt` (LOG-17 x3, LOG-28 x2) | viewmodel | request-response (login flow) | `ui/feed/FeedScreen.kt`'s already-shipped LOG-25 logout-catch fix | exact (sibling pattern) |
| `data/nostr/UmbraNostrClient.kt` (LOG-17, `logWebSocketFailure`) | service (websocket client) | event-driven (relay connection lifecycle) | itself — self-constructed logger, existing `.d` branch to promote | exact (fix-in-place) |
| `data/nostr/RelayMessageHandling.kt` (LOG-17) | service (message dispatch) | event-driven | `UmbraNostrClient.kt` (shares `client.logger` field) | exact (shared logger owner) |
| `data/nostr/RelayWebSocketListener.kt` (LOG-17) | service (websocket listener) | streaming | `UmbraNostrClient.kt` (shares `client.logger` field) | exact (shared logger owner) |
| `data/repository/EventRepositoryImpl.kt` (LOG-18 x2, LOG-20) | repository | CRUD / event-driven | itself — logger + `LogScrubber` import already partially present | exact (fix-in-place) |
| `data/repository/NegentropySyncOrchestrator.kt` (LOG-18) | service (sync orchestrator) | event-driven | `EventRepositoryImpl.kt`'s LOG-18 sites (same scrub-only fix shape) | role-match |
| `ui/settings/SettingsScreen.kt` (LOG-26) | component (Compose screen) | request-response (logout entry point) | `ui/feed/FeedScreen.kt:383-401` (already-shipped LOG-25 fix, identical bug) | exact (literal sibling fix) |
| `domain/usecase/LogoutUseCase.kt` (LOG-27, 7 sites) | usecase | batch (multi-step cleanup) | `domain/usecase/PublishEventUseCases.kt` (constructor-injected `UmbraLogger` + `UseCaseModule.kt` `@Provides` wiring precedent) | role-match (DI shape only; fix shape is self-derived) |
| `domain/usecase/TrimMemoryCachesUseCase.kt` (LOG-27, 5 sites) | usecase | batch (multi-step cleanup) | same as `LogoutUseCase.kt` above; the two are siblings of each other | exact (sibling of `LogoutUseCase.kt`) |
| **New:** `testutil/fakes/FakeUmbraLogger.kt` (D-04 spy) | test double | N/A | `testutil/fakes/FakeNostrSessionController.kt` | exact (hand-rolled `Fake[InterfaceName]` convention, no mocking framework) |
| `di/UseCaseModule.kt` (wiring edit for `LogoutUseCase`/`TrimMemoryCachesUseCase`'s new logger param) | config (Hilt module) | N/A | `di/UseCaseModule.kt:141-145` (`providePublishSignedEventUseCase`, same file) | exact (existing precedent in same file) |

## Pattern Assignments

### `domain/usecase/PublishEventUseCases.kt:42,69` (usecase, event-driven)

**Analog:** itself (`logger: UmbraLogger` already a constructor parameter — no DI change needed)

**Before** (current, both sites, `PublishEventUseCases.kt:41-43` / `:68-70`):
```kotlin
}.onFailure { e ->
    logger.d { "Failed to publish signed event: ${scrubThrowableMessageForLogs(e)}" }
}
```

**After (target shape for every LOG-17 site, per D-01):**
```kotlin
}.onFailure { e ->
    logger.e(e) { "Failed to publish signed event" }
}
```

Apply the identical transform to line 69's `"Failed to publish signed AUTH event"` message. After both edits, check whether `scrubThrowableMessageForLogs` is still used elsewhere in the file — per RESEARCH.md Pitfall 3, `PublishEventUseCases.kt` uses it in exactly these 2 sites, so the import becomes unused and must be removed (lint treats unused imports as an error in CI).

**Spy-test pattern (D-04 — cheap here, logger already injectable):** construct the use case with `FakeUmbraLogger()` in place of a real `UmbraLogger`/`NoOpUmbraLogger`, trigger the failure path, assert `fakeLogger.errorCalls` has one entry with a non-null throwable.

---

### `ui/auth/LoginViewModel.kt:97,143,222` (LOG-17) and `:81-83,128-130` (LOG-28) (viewmodel, request-response)

**Analog:** `ui/feed/FeedScreen.kt:383-401` (already-shipped LOG-25 fix — the literal reference shape for this whole file's changes)

`FeedScreen.kt`'s shipped fix:
```kotlin
// Source: app/src/main/java/com/umbra/app/ui/feed/FeedScreen.kt:390-401
scope.launch {
    try {
        isLoggingOut = true
        loginViewModel.logout()
    } catch (e: Exception) {
        feedScreenLogger.e(e) { "Logout failed" }
    }
    isLoggingOut = false
    navController.navigate(Screen.Login.route) {
        popUpTo(0) { inclusive = true }
    }
}
```

**LOG-17 outer-catch fix (3 sites, `LoginViewModel.kt:97,143,222`):** promote `logger.d { "<message>: ${scrubThrowableMessageForLogs(e)}" }` to `logger.e(e) { "<message>" }`, dropping the manual `scrubThrowableMessageForLogs(e)` interpolation exactly as in the `PublishEventUseCases.kt` example above. `logger` here is self-constructed (`UmbraLog.tag(TAG)` at `LoginViewModel.kt:44`) — not constructor-injectable without a broader refactor, so this class is **not** in D-04's cheap spy-test group (verify via compile + lint + manual review instead, per RESEARCH.md Pitfall 2).

**LOG-28 inner-catch fix (D-03, 2 sites, `LoginViewModel.kt:81-83` and `:128-130`):**
```kotlin
// Before
try {
    eventRepository.activateUserSession(anonymousPubkey, DefaultFeedFilters.DEFAULT)
} catch (_: Exception) { }

// After — keep the inner catch (do not remove/propagate), only add logging
try {
    eventRepository.activateUserSession(anonymousPubkey, DefaultFeedFilters.DEFAULT)
} catch (e: Exception) {
    logger.e(e) { "Session activation failed" }
}
```
Same transform for `savePublicKey()`'s equivalent block (`normalized` instead of `anonymousPubkey`).

---

### `data/nostr/UmbraNostrClient.kt:371` (`logWebSocketFailure`, non-SOCKS branch) (service, event-driven)

**Analog:** itself — fix in place, same file also owns the field `RelayMessageHandling.kt` and `RelayWebSocketListener.kt` log through as `client.logger`.

```kotlin
// Before (line 371 only — do NOT touch line 367's SOCKS-transient branch)
logger.d { "WebSocket error for $relay$responseInfo: $errorMessage" }

// After
logger.e(<throwable>) { "WebSocket error for $relay$responseInfo: $errorMessage" }
```
Confirm the exact throwable variable name in scope at line 371 when implementing (RESEARCH.md verified the message text but the planner/implementer must read the surrounding 5-10 lines to bind the right exception reference). **Do not** change line 367 — that branch is explicitly out of the 8-site count (RESEARCH.md Pitfall 1).

---

### `data/nostr/RelayMessageHandling.kt:140` and `data/nostr/RelayWebSocketListener.kt:52` (service, event-driven/streaming)

**Analog:** `UmbraNostrClient.kt` (same `logger`/`client.logger` field family — treat as one logical fix group with `UmbraNostrClient.kt:371`)

```kotlin
// RelayMessageHandling.kt:140 — before
logger.d { "Error processing message from ${scrubUrlForLogs(relayUrl)}: ${scrubThrowableMessageForLogs(e)}" }

// after
logger.e(e) { "Error processing message from ${scrubUrlForLogs(relayUrl)}" }
```
```kotlin
// RelayWebSocketListener.kt:52 — before
client.logger.d { "Error processing message from ${scrubUrlForLogs(relayUrl)}: ${scrubThrowableMessageForLogs(e)}" }

// after
client.logger.e(e) { "Error processing message from ${scrubUrlForLogs(relayUrl)}" }
```
Both sites already scrub the URL — only the level/throwable-attachment changes; keep `scrubUrlForLogs(relayUrl)` in the message (URL scrubbing is a separate concern from the throwable, which `logger.e()` scrubs itself).

---

### `data/repository/EventRepositoryImpl.kt:428,486` (LOG-18) and `:498-500` (LOG-20) (repository, CRUD/event-driven)

**Analog:** itself — class already has `private val logger = UmbraLog.tag(TAG)` (line 216) and a partial `LogScrubber` import.

**LOG-18 (scrub only, level unchanged):**
```kotlin
// :428 before
logger.d { "FEED_NOTES EOSE from relay $relayUrl reported MORE — not advancing since watermark" }
// after
logger.d { "FEED_NOTES EOSE from relay ${scrubUrlForLogs(relayUrl)} reported MORE — not advancing since watermark" }

// :486 before
logger.d { "Re-applied ${channelFilters.size} channels to relay $relayUrl" }
// after
logger.d { "Re-applied ${channelFilters.size} channels to relay ${scrubUrlForLogs(relayUrl)}" }
```
Add `scrubUrlForLogs` to the file's existing `LogScrubber` import (currently only `scrubThrowableMessageForLogs` is imported at line 54).

**LOG-20 (`clearAllData()`'s silent catch, :498-500):**
```kotlin
// before
try {
    disconnectFromAll()
} catch (_: Exception) { }

// after
try {
    disconnectFromAll()
} catch (e: Exception) {
    logger.e(e) { "disconnectFromAll failed during clearAllData; continuing wipe" }
}
```

**Spy-test pattern (D-04):** NOT in the cheap group — `EventRepositoryImpl` self-constructs its logger, not constructor-injectable without a broader Hilt refactor. Verify via compile + lint + manual review (`find-non-lambda-logs` skill) instead.

---

### `data/repository/NegentropySyncOrchestrator.kt:118` (LOG-18) (service, event-driven)

**Analog:** `EventRepositoryImpl.kt`'s LOG-18 sites above (identical scrub-only fix shape)

```kotlin
// before
logger.d { "NIP-77 sync with relay failed: ${e.message}" }

// after
logger.d { "NIP-77 sync with relay failed: ${LogScrubber.scrubThrowableMessageForLogs(e)}" }
```
File currently imports no `LogScrubber` member at all (only `UmbraLog`) — add the import.

---

### `ui/settings/SettingsScreen.kt:201-204` (LOG-26) (component, request-response)

**Analog:** `ui/feed/FeedScreen.kt:383-401` (already-shipped LOG-25 fix — literal reference; this is the same bug in a sibling screen)

```kotlin
// Before, SettingsScreen.kt:201-204
try {
    isLoggingOut = true
    loginViewModel.logout()
} catch (_: Exception) { }

// After — mirror FeedScreen.kt:390-401 exactly
try {
    isLoggingOut = true
    loginViewModel.logout()
} catch (e: Exception) {
    settingsScreenLogger.e(e) { "Logout failed" }
}
```
`SettingsScreen.kt` currently has zero logger import — add `import com.umbra.app.util.logging.UmbraLog` and a new file-scope declaration mirroring `FeedScreen.kt:101`:
```kotlin
private val settingsScreenLogger = UmbraLog.tag("SettingsScreen")
```

---

### `domain/usecase/LogoutUseCase.kt` (LOG-27, 7 sites) and `domain/usecase/TrimMemoryCachesUseCase.kt` (LOG-27, 5 sites) (usecase, batch)

**Analog:** `domain/usecase/PublishEventUseCases.kt` for the constructor-injection + `di/UseCaseModule.kt` `@Provides` wiring shape (the only existing precedent for an injected `UmbraLogger` in this package); the two files are otherwise siblings of each other for the per-step catch shape.

**DI wiring precedent** (`di/UseCaseModule.kt:141-145`, existing code — copy this shape):
```kotlin
fun providePublishSignedEventUseCase(
    repo: EventRepository,
    broadcastRepository: BroadcastRepository
): PublishSignedEventUseCase =
    PublishSignedEventUseCase(repo, broadcastRepository, UmbraLog.tag("UmbraPublishUC"))
```

Apply identically to `provideLogoutUseCase` (`UseCaseModule.kt:274-292`) and `provideTrimMemoryCachesUseCase` (`:294-308`) — add `UmbraLog.tag("UmbraLogout")` / `UmbraLog.tag("UmbraTrimMemory")` as a new trailing constructor arg. `UseCaseModule.kt` already imports `UmbraLog` (line 30) — no new import needed there. No Hilt binding for `UmbraLogger` is required since the `@Provides` function constructs it inline.

**Per-step catch fix shape** (7 sites in `LogoutUseCase.kt`, 5 in `TrimMemoryCachesUseCase.kt`, all currently `catch (_: Exception) { }`):
```kotlin
// Before (LogoutUseCase.kt:33-35, and 6 more identically-shaped sites)
try {
    nostrSessionController.stop()
} catch (_: Exception) { }

// After — constructor gains `private val logger: UmbraLogger` (new trailing param)
try {
    nostrSessionController.stop()
} catch (e: Exception) {
    logger.e(e) { "nostrSessionController.stop() failed during logout" }
}
```
Each of the 7 `LogoutUseCase.kt` sites (lines 35, 38, 48, 58, 61, 64, 69) and 5 `TrimMemoryCachesUseCase.kt` sites (lines 29, 32, 35, 38, 41) needs its own step-specific message describing which call failed. **Do not** touch `LogoutUseCase.kt`'s outer catch at line 73 or wrap the unwrapped `userPreferences.clearAll()` call at line 72 — both are explicitly out of D-02's 7-site scope (RESEARCH.md Pitfall 4); flag as a new backlog item instead of fixing here.

**Spy-test pattern (D-04 — cheap here, new dependency added deliberately to enable this):** existing test file `app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt` constructs `LogoutUseCase` with 7 positional fake args (all `Fake[InterfaceName]` doubles from `testutil/fakes/`, e.g. `FakeNostrSessionController`, `FakeUserRepository`); adding the 8th `logger` constructor param means every existing call site there needs a trailing `FakeUmbraLogger()` (or the specific spy) argument added. `TrimMemoryCachesUseCase.kt` has no existing test file — a new one can be written from scratch using the same fakes + a `FakeUmbraLogger`.

---

### New: `testutil/fakes/FakeUmbraLogger.kt` (test double, N/A)

**Analog:** `testutil/fakes/FakeNostrSessionController.kt` (exact hand-rolled `Fake[InterfaceName]` convention already used across this package — no mocking framework in this codebase's `build.gradle.kts`)

`FakeNostrSessionController.kt` (full file, existing pattern to copy):
```kotlin
package com.umbra.app.testutil.fakes

import com.umbra.app.domain.nostr.NostrSessionController

class FakeNostrSessionController : NostrSessionController {
    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    override fun start() {
        startCalls++
    }

    override fun stop() {
        stopCalls++
    }
}
```

**Target shape for `FakeUmbraLogger`** (implements `domain/logging/UmbraLogger`, records invocations per D-04 — which method was called and whether a throwable was passed):
```kotlin
package com.umbra.app.testutil.fakes

import com.umbra.app.domain.logging.UmbraLogger

class FakeUmbraLogger : UmbraLogger {
    data class Call(val level: String, val throwable: Throwable?, val message: String)

    val calls = mutableListOf<Call>()
    val errorCalls get() = calls.filter { it.level == "e" }

    override fun d(message: () -> String) {
        calls += Call("d", null, message())
    }

    override fun w(message: () -> String) {
        calls += Call("w", null, message())
    }

    override fun e(throwable: Throwable, message: () -> String) {
        calls += Call("e", throwable, message())
    }
}
```
Place at `app/src/test/java/com/umbra/app/testutil/fakes/FakeUmbraLogger.kt`, matching the existing sibling fakes' package and location exactly. Confirm the exact `UmbraLogger` interface method signatures against `domain/logging/UmbraLogger.kt:12-27` before finalizing (RESEARCH.md already verified: `d(() -> String)`, `w(() -> String)`, `e(throwable: Throwable, message: () -> String)`).

## Shared Patterns

### Tagged logger + lambda-gated call sites (applies to every touched production file)
**Source:** `app/src/main/java/com/umbra/app/util/logging/Logger.kt:14-28`, `UmbraLog.kt:8-10`, `domain/logging/UmbraLogger.kt:12-27`
**Apply to:** All 10 modified production files.
```kotlin
// Logger.kt — e() is the only method taking a throwable, and it scrubs internally
fun e(throwable: Throwable, message: () -> String) {
    if (Log.isLoggable(tag, Log.ERROR)) {
        Log.e(tag, "${message()}: ${LogScrubber.scrubThrowableMessageForLogs(throwable)}", throwable)
    }
}
```
Never manually call `scrubThrowableMessageForLogs(e)` inside an `logger.e(e) { ... }` lambda body — the wrapper already does it once (RESEARCH.md Pitfall 3). Every class obtains its logger via `private val logger = UmbraLog.tag(TAG)` (with `private const val TAG = "UmbraXxx"`), except `PublishSignedEventUseCase`/`PublishAuthEventUseCase`, which take `UmbraLogger` as a constructor param — this phase adds `LogoutUseCase`/`TrimMemoryCachesUseCase` to that injectable group.

### Log message scrubbing
**Source:** `app/src/main/java/com/umbra/app/util/LogScrubber.kt:1-42`
**Apply to:** `EventRepositoryImpl.kt` (LOG-18), `NegentropySyncOrchestrator.kt` (LOG-18), and any manual message text elsewhere in this phase's fixes.
```kotlin
LogScrubber.scrubUrlForLogs(relayUrl)          // "wss://relay.example" -> "wss://[redacted]"
LogScrubber.scrubThrowableMessageForLogs(e)
```

### Already-shipped sibling fix as the literal reference for a new fix
**Source:** `app/src/main/java/com/umbra/app/ui/feed/FeedScreen.kt:383-401` (LOG-25, already shipped)
**Apply to:** `SettingsScreen.kt` (LOG-26) — same bug, same fix shape, different screen; and secondarily as the general "silent catch -> `logger.e(e) { "<description>" }`" template for every other silent-catch fix in this phase (`EventRepositoryImpl.kt` LOG-20, `LoginViewModel.kt` LOG-28, `LogoutUseCase.kt`/`TrimMemoryCachesUseCase.kt` LOG-27).

### Constructor-injected `UmbraLogger` wired via `@Provides`
**Source:** `app/src/main/java/com/umbra/app/di/UseCaseModule.kt:141-145` (`providePublishSignedEventUseCase`)
**Apply to:** `LogoutUseCase.kt`/`TrimMemoryCachesUseCase.kt`'s new logger dependency (`UseCaseModule.kt:274-309`) — this is the only precedent in the codebase for this shape and is the mechanism that makes those two classes D-04-spy-testable without a broader Hilt refactor.

### Hand-rolled `Fake[InterfaceName]` test doubles (no mocking framework)
**Source:** `app/src/test/java/com/umbra/app/testutil/fakes/FakeNostrSessionController.kt` (and siblings: `FakeEventRepository.kt`, `FakeUserRepository.kt`, `FakeContactListRepository.kt`, `FakeMuteListRepository.kt`, `FakePinListRepository.kt`, `FakeUserPreferences.kt`)
**Apply to:** New `FakeUmbraLogger.kt` (D-04) — same package (`com.umbra.app.testutil.fakes`), same "simple recording class, no framework" shape. Confirmed: `app/build.gradle.kts` has no MockK/Mockito dependency; `.planning/codebase/TESTING.md` documents this as the established convention.

## No Analog Found

None — every file in this phase's scope is either fixed in place (its own current code is the "analog," since this is call-site hygiene, not new-file creation) or has a directly-applicable sibling (`FeedScreen.kt` for `SettingsScreen.kt`; `PublishEventUseCases.kt`/`UseCaseModule.kt` for the two use cases' new DI shape; `FakeNostrSessionController.kt` for the one genuinely new file, `FakeUmbraLogger.kt`).

## Metadata

**Analog search scope:** `app/src/main/java/com/umbra/app/{domain,ui,data,di}/`, `app/src/test/java/com/umbra/app/testutil/fakes/`
**Files scanned:** 10 production files (already fully read during RESEARCH.md's session — no re-reads performed here beyond confirming the `FakeNostrSessionController.kt` convention and `BackfillDeleteLogoutUseCaseTest.kt`'s existing `LogoutUseCase` construction call site), plus 2 test files read fresh this session.
**Pattern extraction date:** 2026-09-02
