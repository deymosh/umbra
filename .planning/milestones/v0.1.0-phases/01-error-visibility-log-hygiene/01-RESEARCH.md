# Phase 1: Error Visibility & Log Hygiene - Research

**Researched:** 2026-09-02
**Domain:** Internal logging wrapper call-site hygiene (Kotlin/Android, no new dependencies)
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Log level (LOG-17's 8 sites) — D-01:** All 8 LOG-17 sites are promoted to `logger.e(throwable) { "..." }` (blanket ERROR), not split by whether the underlying failure is expected/transient (e.g. relay disconnects) vs. a genuine bug (e.g. local prefs/session-state setup failures). Every site was silently losing its throwable; error-level visibility with the stack trace preserved takes priority over noise reduction on the transient-failure sites. Do not manually re-scrub the throwable's message inside the `e()` lambda — `logger.e()` already calls `LogScrubber.scrubThrowableMessageForLogs()` internally and appends it to the caller's message; only pass a short static description string as the lambda body.
Sites: `PublishEventUseCases.kt:42,69`, `LoginViewModel.kt:97,143,222`, `UmbraNostrClient.kt:371` (`logWebSocketFailure`), `RelayMessageHandling.kt:140`, `RelayWebSocketListener.kt:52`.

**Wipe-path failure exposure (LOG-20/26/27) — D-02:** LOG-20, LOG-26, and LOG-27 stay **log-only** — `logger.e(throwable) { "..." }` at each currently-silent catch site (`clearAllData()`'s `disconnectFromAll()` catch, `SettingsScreen.kt`'s logout `catch`, each of `LogoutUseCase`'s 7 and `TrimMemoryCachesUseCase`'s 5 per-step cleanup catches). No user-facing UI change (no aggregated "logout completed with warnings" message or similar). This matches REQUIREMENTS.md's BUG-04/09/10 wording, which already describes these as "log the exception," not "surface to the user." Reversible — adding a user-facing warning later is additive.
Surfacing an aggregated user-facing warning on wipe-path failure was raised as an alternative but explicitly deferred — new UI-facing scope not in Phase 1's 6 requirements.

**LoginViewModel inner-catch fix shape (LOG-28) — D-03:** Keep the inner `try/catch` around `eventRepository.activateUserSession(...)` in both `loginAnonymously()` and `savePublicKey()` — do **not** remove it or let the exception propagate to the outer catch. Add `logger.e(e) { "Session activation failed" }` inside the inner catch instead of `catch (_: Exception) { }`. One-way if reversed carelessly — removing the inner catch instead would change login's success/failure semantics (a user whose pubkey was already saved would now see "login failed" instead of logging in with a possibly-empty feed).
Root cause context: the inner catch exists so that `activateUserSession` (backfill/hydration trigger) failing does not block login itself. This is intentional, not an oversight; only the missing log call is the bug.
Sites: `LoginViewModel.kt:81-83` (`loginAnonymously`), `LoginViewModel.kt:128-130` (`savePublicKey`).

**Verification strategy for logging-only fixes — D-04:** Introduce a test-double `UmbraLogger` (spy/fake) that records invocations (which method — `d`/`w`/`e` — and whether a throwable was passed), and use it in place of `NoOpUmbraLogger` in tests covering the classes touched by this phase's fixes. Assert that each fixed catch site now calls `e()` (or `w()` for sites that land there) instead of the previous `d()`. This is a new test pattern — no existing test currently asserts *what* a logger call was invoked with. Reversible — additive test infrastructure.
Compile (`compileDebugKotlin`), lint (`lintDebug`), and full unit test suite (`testDebugUnitTest`) still gate every task.

### Claude's Discretion
- Exact spy/fake `UmbraLogger` implementation shape (e.g. a simple recording class vs. a MockK-based mock) is left to the planner/researcher — codebase currently has no MockK/Mockito usage pattern to confirm against.
- Whether the 3 "expected/transient" LOG-17 sites (`UmbraNostrClient.kt`, `RelayMessageHandling.kt`, `RelayWebSocketListener.kt`) get any accompanying rate-limiting/log-spam consideration is left to planner discretion — D-01 only decided the level, not whether repeated errors need throttling. Do not add throttling infrastructure speculatively; only address it if it turns out to be a real problem during implementation.

### Deferred Ideas (OUT OF SCOPE)
- **Aggregated user-facing warning on logout/wipe failure** — raised while discussing LOG-20/26/27 (D-02). Would show something like "Logout completed with warnings" if any per-step cleanup fails. Explicitly out of Phase 1's scope; worth considering as a future backlog item if on-device use ever surfaces a real case where silent-but-logged wipe failures cause user confusion.
- **Rate-limiting/throttling for the 3 "expected/transient" LOG-17 sites** — not decided either way; left to planner/implementer discretion, not a deferred phase-boundary item.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| BUG-01 | Promote LOG-17's 8 swallowed-exception debug-level log sites to scrubbed error-level logging with the throwable attached | All 8 sites re-verified against current source this session — see Architecture Patterns; exact line numbers confirmed unchanged from CONTEXT.md |
| BUG-02 | Scrub LOG-18's 3 unscrubbed log sites via `LogScrubber` | Exact sites/imports verified — `EventRepositoryImpl.kt:428,486` need `scrubUrlForLogs` import added; `NegentropySyncOrchestrator.kt:118` needs `LogScrubber` import added (currently has neither) |
| BUG-04 | Fix LOG-20 — log the exception currently swallowed by `clearAllData()`'s `disconnectFromAll()` catch block | Verified at `EventRepositoryImpl.kt:498-500`; `logger` field already exists on this class (self-constructed, `private val logger = UmbraLog.tag(TAG)` at line 216) |
| BUG-09 | Fix LOG-26 — apply LOG-25's already-shipped logout exception-logging fix to `SettingsScreen.kt`'s independent logout entry point | Exact reference pattern read from `FeedScreen.kt:383-401` (the already-fixed sibling); `SettingsScreen.kt` currently has zero logger import — needs both the import and a module-level tagged logger added |
| BUG-10 | Fix LOG-27 — log each per-step cleanup exception (scrubbed) in `LogoutUseCase` and `TrimMemoryCachesUseCase` instead of silently swallowing it | Both classes read in full: 7 and 5 per-step catches respectively confirmed; **neither class currently has a logger of any kind** — this is a new constructor dependency, not a call-site swap |
| BUG-11 | Fix LOG-28 — stop swallowing `activateUserSession`'s exception inside `LoginViewModel`'s inner catch | Exact sites verified at `LoginViewModel.kt:81-83` and `:128-130`; outer catch's existing (about-to-be-fixed-by-D-01) logging path confirmed at `:97`/`:143` |
</phase_requirements>

## Summary

This is a call-site hygiene phase with **zero new dependencies and zero new architecture** — every fix routes through the `UmbraLog.tag(TAG)` → `Logger` → `UmbraLogger` interface → `LogScrubber` stack that already exists and was fully read this session (`util/logging/Logger.kt`, `UmbraLog.kt`, `domain/logging/UmbraLogger.kt`, `util/LogScrubber.kt`). All 15 call sites named in CONTEXT.md (8 LOG-17 + 3 LOG-18 + 4 previously-silent catches) were independently re-read against current source this session; **every line number in CONTEXT.md matches current source exactly** — CONTEXT.md was written the same day as this research and there has been no drift.

The one finding CONTEXT.md does not resolve, and that materially affects how the planner should scope D-04's spy-logger tests, is **logger injectability**: of the classes touched by this phase, only `PublishSignedEventUseCase`/`PublishAuthEventUseCase` (in `PublishEventUseCases.kt`) already take `UmbraLogger` via constructor injection. Every other touched class — `LoginViewModel`, `UmbraNostrClient` (and by extension `RelayMessageHandling.kt`/`RelayWebSocketListener.kt`, which both log through `client.logger`), `EventRepositoryImpl`, `NegentropySyncOrchestrator`, `SettingsScreen.kt` — self-constructs its logger internally via `private val logger = UmbraLog.tag(TAG)` (or an equivalent module-level `val` for the Composable file), with no constructor parameter to substitute a spy into. `LogoutUseCase`/`TrimMemoryCachesUseCase` currently have **no logger at all**. See Common Pitfalls and Open Questions for the full analysis and a concrete, low-risk recommendation (inject via the two use cases' existing `UseCaseModule.kt` `@Provides` functions, matching `PublishEventUseCases.kt`'s own precedent exactly) rather than retrofitting Hilt-managed `@Inject constructor` classes with per-class `@Named` logger qualifiers.

**Primary recommendation:** Fix all 15 sites as literal, mechanical call-site edits per D-01/D-02/D-03 (every line number below is pre-verified against current source — no exploratory reading needed during planning). Apply D-04's spy-logger regression test only to the classes where it is achievable without a DI refactor — `PublishSignedEventUseCase`/`PublishAuthEventUseCase` (already injectable) and `LogoutUseCase`/`TrimMemoryCachesUseCase` (new dependency naturally added as constructor-injected `UmbraLogger`, wired through `UseCaseModule.kt`'s existing `@Provides` functions) — and verify the rest via compile + lint + manual `find-non-lambda-logs`-style review, flagging that scope split explicitly for user confirmation since CONTEXT.md's D-04 doesn't resolve it either way.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Throwable-attached error logging (LOG-17) | Data / Domain (usecase, repository, nostr client) | UI (LoginViewModel) | These are the layers where the try/catch blocks already live; no new logging infrastructure is introduced, only call-site changes within existing classes |
| Log message scrubbing (LOG-18) | Data (`EventRepositoryImpl`, `NegentropySyncOrchestrator`) | — | `LogScrubber` is a `util/` object with no layer restriction, but both unscrubbed call sites are data-layer |
| Wipe-path failure logging (LOG-20/27) | Data (`EventRepositoryImpl`) / Domain (`LogoutUseCase`, `TrimMemoryCachesUseCase`) | — | Both are the classes that already own the wipe sequence; no new class needed |
| Settings logout failure logging (LOG-26) | UI (Compose screen, `SettingsScreen.kt`) | — | Mirrors the already-shipped `FeedScreen.kt` fix, same tier |
| Session-activation failure logging (LOG-28) | UI (`LoginViewModel`) | — | Existing ViewModel-owned try/catch |
| Spy `UmbraLogger` test double (D-04) | Test infrastructure (`src/test/`) | Domain (`UmbraLogger` interface) | New test-only class implementing the existing domain-layer port; no production-code architecture change |

## Standard Stack

No new external libraries. Every fix in this phase routes through code that already exists and was read in full this session:

| File | Role | Verified |
|------|------|----------|
| `app/src/main/java/com/umbra/app/util/logging/Logger.kt` | Android-backed `UmbraLogger` impl — `d(() -> String)`, `w(() -> String)`, `e(Throwable, () -> String)`. `e()` internally calls `Log.e(tag, "${message()}: ${LogScrubber.scrubThrowableMessageForLogs(throwable)}", throwable)` — confirms D-01's "don't manually re-scrub" instruction is correct: the scrub happens exactly once, inside the wrapper. | [VERIFIED: app/src/main/java/com/umbra/app/util/logging/Logger.kt:14-28] |
| `app/src/main/java/com/umbra/app/util/logging/UmbraLog.kt` | `object UmbraLog { fun tag(tag: String): Logger = Logger(tag) }` — the only factory entry point. | [VERIFIED: app/src/main/java/com/umbra/app/util/logging/UmbraLog.kt:8-10] |
| `app/src/main/java/com/umbra/app/domain/logging/UmbraLogger.kt` | Pure-Kotlin domain port: `interface UmbraLogger { fun d(...); fun w(...); fun e(throwable: Throwable, message: () -> String) }`, plus `object NoOpUmbraLogger : UmbraLogger` (all three methods no-op). | [VERIFIED: app/src/main/java/com/umbra/app/domain/logging/UmbraLogger.kt:12-27] |
| `app/src/main/java/com/umbra/app/util/LogScrubber.kt` | `object LogScrubber` — `scrubUrlForLogs`, `scrubEndpointForLogs`, `scrubPubkeyForLogs`, `scrubThrowableMessageForLogs`, `scrubMessageForLogs`. `scrubUrlForLogs("wss://relay.example")` → `"wss://[redacted]"`. | [VERIFIED: app/src/main/java/com/umbra/app/util/LogScrubber.kt:1-42] |

## Package Legitimacy Audit

Not applicable — this phase installs no external packages. Every fix routes through the existing internal `UmbraLog`/`LogScrubber` stack (see Standard Stack above). No `npm view`/`pip index`/`cargo search` verification needed.

## Architecture Patterns

### Existing Pattern: Tagged logger + lambda-gated call sites

**What:** Every class obtains its logger via `private val logger = UmbraLog.tag(TAG)` (with `private const val TAG = "UmbraXxx"` alongside), except the two classes that already inject it (`PublishSignedEventUseCase`, `PublishAuthEventUseCase`, both taking `private val logger: UmbraLogger` as a constructor param). `logger.e(throwable) { "..." }` is the only method taking a throwable.

**When to use:** Every one of this phase's 15 sites — no exceptions, no new pattern to design.

**Example — the exact target shape for every D-01/D-02/D-03 fix (from `FeedScreen.kt`'s already-shipped LOG-25 fix, the literal reference for BUG-09):**
```kotlin
// Source: app/src/main/java/com/umbra/app/ui/feed/FeedScreen.kt:390-401 (already shipped, LOG-25)
scope.launch {
    try {
        isLoggingOut = true
        loginViewModel.logout()
    } catch (e: Exception) {
        // Logout failing (e.g. a database wipe leaving stale key
        // material behind) must not be silently indistinguishable
        // from success — still proceed to the login screen below
        // since there's no in-app state left to usefully retry from,
        // but at least record that it happened.
        feedScreenLogger.e(e) { "Logout failed" }
    }
    isLoggingOut = false
    navController.navigate(Screen.Login.route) {
        popUpTo(0) { inclusive = true }
    }
}
```
`feedScreenLogger` is declared once at file scope: `private val feedScreenLogger = UmbraLog.tag("FeedScreen")` (`FeedScreen.kt:101`). `SettingsScreen.kt` has no equivalent logger declared today — BUG-09's fix needs both the `import com.umbra.app.util.logging.UmbraLog` line and a new file-scope `private val settingsScreenLogger = UmbraLog.tag("SettingsScreen")` (or similar tag), plus the identical try/catch body change at `SettingsScreen.kt:201-204`. [VERIFIED: app/src/main/java/com/umbra/app/ui/feed/FeedScreen.kt:1-101,383-401 and app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt:1-46,191-212]

### Verified exact call sites (LOG-17 — D-01, promote to `logger.e(throwable) { "..." }`)

All 8 confirmed against current source this session — line numbers match CONTEXT.md exactly, zero drift:

| File:Line | Current code | Notes |
|-----------|--------------|-------|
| `domain/usecase/PublishEventUseCases.kt:42` | `logger.d { "Failed to publish signed event: ${scrubThrowableMessageForLogs(e)}" }` inside `PublishSignedEventUseCase`'s `.onFailure { e -> ... }` | `logger: UmbraLogger` already a constructor param — trivially spy-testable |
| `domain/usecase/PublishEventUseCases.kt:69` | `logger.d { "Failed to publish signed AUTH event: ${scrubThrowableMessageForLogs(e)}" }` inside `PublishAuthEventUseCase`'s `.onFailure { e -> ... }` | Same as above |
| `ui/auth/LoginViewModel.kt:97` | `logger.d { "Anonymous login failed: ${scrubThrowableMessageForLogs(e)}" }` in `loginAnonymously()`'s outer `catch (e: Exception)` | `logger` is self-constructed (`UmbraLog.tag(TAG)` at line 44) — not injectable without refactor |
| `ui/auth/LoginViewModel.kt:143` | `logger.d { "Failed to save public key: ${scrubThrowableMessageForLogs(e)}" }` in `savePublicKey()`'s outer `catch (e: Exception)` | Same logger field as above |
| `ui/auth/LoginViewModel.kt:222` | `logger.d { "Logout failed: ${scrubThrowableMessageForLogs(e)}" }` in `logout()`'s `catch (e: Exception)` | Same logger field as above |
| `data/nostr/UmbraNostrClient.kt:371` | `logger.d { "WebSocket error for $relay$responseInfo: $errorMessage" }` — the **non-SOCKS branch** of `internal fun logWebSocketFailure(...)` (lines 361-372) | **Do not touch line 367** (`logger.d { "Transient SOCKS failure for..." }`), the other branch in the same function — it `return`s early and is explicitly excluded from the 8-site count (see Common Pitfalls) |
| `data/nostr/RelayMessageHandling.kt:140` | `logger.d { "Error processing message from ${scrubUrlForLogs(relayUrl)}: ${scrubThrowableMessageForLogs(e)}" }` in `internal suspend fun UmbraNostrClient.onWebSocketMessage(relayUrl: String, text: String)`'s outer `catch (e: Exception)` | Already scrubbed — only the level/throwable-attachment needs to change |
| `data/nostr/RelayWebSocketListener.kt:52` | `client.logger.d { "Error processing message from ${scrubUrlForLogs(relayUrl)}: ${scrubThrowableMessageForLogs(e)}" }` inside the `init {}` block's per-connection drain coroutine's `.onFailure { e -> ... }` | Logs via `client.logger` (i.e. `UmbraNostrClient`'s field) — same injectability constraint as `UmbraNostrClient.kt`/`RelayMessageHandling.kt` |

[VERIFIED: app/src/main/java/com/umbra/app/domain/usecase/PublishEventUseCases.kt:23-72; app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt:64-227; app/src/main/java/com/umbra/app/data/nostr/UmbraNostrClient.kt:361-372; app/src/main/java/com/umbra/app/data/nostr/RelayMessageHandling.kt:106-141; app/src/main/java/com/umbra/app/data/nostr/RelayWebSocketListener.kt:44-56]

### Verified exact call sites (LOG-18 — D-02 scrubbing only, level unchanged)

| File:Line | Current code | Fix | Import needed |
|-----------|--------------|-----|----------------|
| `data/repository/EventRepositoryImpl.kt:428` | `logger.d { "FEED_NOTES EOSE from relay $relayUrl reported MORE — not advancing since watermark" }` | Wrap `$relayUrl` → `${scrubUrlForLogs(relayUrl)}` | File already imports `LogScrubber.scrubThrowableMessageForLogs` (line 54) but **not** `scrubUrlForLogs` — add that import |
| `data/repository/EventRepositoryImpl.kt:486` | `logger.d { "Re-applied ${channelFilters.size} channels to relay $relayUrl" }` | Wrap `$relayUrl` → `${scrubUrlForLogs(relayUrl)}` | Same import as above (one addition covers both sites) |
| `data/repository/NegentropySyncOrchestrator.kt:118` | `logger.d { "NIP-77 sync with relay failed: ${e.message}" }` inside `sync()`'s `eligible.forEach { relay -> repoScope.launch { try { ... } catch (e: Exception) { ... } } }` | Wrap `${e.message}` → `${LogScrubber.scrubThrowableMessageForLogs(e)}` | File currently imports **no** `LogScrubber` member at all (only `UmbraLog`) — new import required |

[VERIFIED: app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt:1-60,418-429,476-487; app/src/main/java/com/umbra/app/data/repository/NegentropySyncOrchestrator.kt:1-124]

### Verified exact call sites (4 previously-silent catches — D-02/D-03)

| File:Line(s) | Current code | Fix (D-02/D-03) |
|--------------|---------------|-------------------|
| `data/repository/EventRepositoryImpl.kt:498-500` | `try { disconnectFromAll() } catch (_: Exception) { }` inside `clearAllData()` | `catch (e: Exception) { logger.e(e) { "disconnectFromAll failed during clearAllData; continuing wipe" } }` — class already has `private val logger = UmbraLog.tag(TAG)` (line 216), no new dependency |
| `ui/settings/SettingsScreen.kt:201-204` | `try { isLoggingOut = true; loginViewModel.logout() } catch (_: Exception) { }` | Mirror `FeedScreen.kt:390-401` exactly (see Architecture Patterns example above) — needs new file-scope logger + import |
| `domain/usecase/LogoutUseCase.kt` — 7 sites: lines 35 (`nostrSessionController.stop()`), 38 (`eventRepository.clearAllData()`), 48 (`userRepository.clearAll()`), 58 (`contactListRepository.clearAll()`), 61 (`muteListRepository.clearAll()`), 64 (`pinListRepository.clearAll()`), 69 (`eventRepository.clearBackfillAnchors(pubkey)`) — every one currently `catch (_: Exception) { }` | Each becomes `catch (e: Exception) { logger.e(e) { "<step description> failed during logout" } }` | Class has **no logger today** — needs a new `private val logger: UmbraLogger` constructor param, wired through `UseCaseModule.kt`'s `provideLogoutUseCase` (currently 7 params, line 276-292) |
| `domain/usecase/TrimMemoryCachesUseCase.kt` — 5 sites: lines 29 (`eventRepository.trimMemory(aggressive)`), 32 (`userRepository.pruneStaleData()`), 35 (`contactListRepository.trimMemory()`), 38 (`muteListRepository.trimMemory()`), 41 (`pinListRepository.trimMemory()`) — every one `catch (_: Exception) { }` | Same shape as `LogoutUseCase` | No logger today — new constructor param, wired through `UseCaseModule.kt`'s `provideTrimMemoryCachesUseCase` (currently 5 params, line 296-308) |

[VERIFIED: app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt:488-542; app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt:1-46,191-212; app/src/main/java/com/umbra/app/domain/usecase/LogoutUseCase.kt:1-79; app/src/main/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCase.kt:1-45; app/src/main/java/com/umbra/app/di/UseCaseModule.kt:274-309]

### Verified exact call sites (LoginViewModel inner catch — D-03)

| File:Line(s) | Current code | Fix |
|--------------|---------------|-----|
| `ui/auth/LoginViewModel.kt:81-83` | `try { eventRepository.activateUserSession(anonymousPubkey, DefaultFeedFilters.DEFAULT) } catch (_: Exception) { }` in `loginAnonymously()` | `catch (e: Exception) { logger.e(e) { "Session activation failed" } }` |
| `ui/auth/LoginViewModel.kt:128-130` | `try { eventRepository.activateUserSession(normalized, DefaultFeedFilters.DEFAULT) } catch (_: Exception) { }` in `savePublicKey()` | Same fix |

[VERIFIED: app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt:64-155]

### Recommended DI wiring for LogoutUseCase / TrimMemoryCachesUseCase's new logger dependency

Both classes are plain (non-`@Inject constructor`) classes manually wired via `@Provides` functions in `UseCaseModule.kt` — the exact same shape `PublishEventUseCases.kt`'s use cases already use for their injected `UmbraLogger`:

```kotlin
// Source: app/src/main/java/com/umbra/app/di/UseCaseModule.kt:141-145 (existing precedent)
fun providePublishSignedEventUseCase(
    repo: EventRepository,
    broadcastRepository: BroadcastRepository
): PublishSignedEventUseCase =
    PublishSignedEventUseCase(repo, broadcastRepository, UmbraLog.tag("UmbraPublishUC"))
```
The identical pattern applies directly to `provideLogoutUseCase` (`UseCaseModule.kt:274-292`) and `provideTrimMemoryCachesUseCase` (`:294-308`) — add `LogoutUseCase(..., UmbraLog.tag("UmbraLogout"))` / `TrimMemoryCachesUseCase(..., UmbraLog.tag("UmbraTrimMemory"))` (tag names at the planner's discretion, following the `"UmbraXxx"` convention). This requires **no Hilt binding for `UmbraLogger`** since the `@Provides` function constructs it inline — `UseCaseModule.kt` already imports `UmbraLog` (line 30), so no new import there either.
[VERIFIED: app/src/main/java/com/umbra/app/di/UseCaseModule.kt:26-30,141-145,173-174,274-309]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Scrubbing a relay URL / throwable message before logging | A new regex or ad-hoc string truncation | `LogScrubber.scrubUrlForLogs` / `scrubThrowableMessageForLogs` (already imported in most touched files) | Already exists, already covers every case this phase touches; AUDIT.md §1.3 requires using these helpers consistently |
| Recording what a logger call was invoked with, for test assertions | A MockK/Mockito mock (not a codebase dependency) | A hand-written fake implementing `UmbraLogger`, following `TESTING.md`'s established "`Fake[InterfaceName]`" convention (see `FakeRelayRepository` example) | No mocking framework exists in this codebase's `build.gradle.kts`; adding one for a single test-double class would be disproportionate and inconsistent with every other test file |
| A logger for `LogoutUseCase`/`TrimMemoryCachesUseCase` | A new self-constructed `private val logger = UmbraLog.tag(TAG)` field | Constructor-injected `UmbraLogger`, wired via `UseCaseModule.kt`'s existing `@Provides` functions (see above) | Matches the one existing precedent in this exact package (`PublishEventUseCases.kt`) and is the only shape of the 15 touched classes that makes D-04's spy pattern possible without a wider DI refactor |

**Key insight:** Every fix in this phase is a mechanical call-site edit against code already read and quoted verbatim above — there is no design work left for the planner beyond (a) deciding the D-04 test-coverage scope split (see Open Questions) and (b) writing the per-step log message text for `LogoutUseCase`/`TrimMemoryCachesUseCase`'s 12 catches.

## Common Pitfalls

### Pitfall 1: Touching `UmbraNostrClient.kt:367` while fixing `:371`
**What goes wrong:** `logWebSocketFailure` (lines 361-372) has two branches: an early-return SOCKS-transient branch at line 366-369 (`logger.d { "Transient SOCKS failure for..." }`) and the real WebSocket-error branch at line 371 (`logger.d { "WebSocket error for..." }`). CONTEXT.md's 8-site count and `docs/TODO.md`'s LOG-17 entry both explicitly scope this to "`logWebSocketFailure`'s **non-SOCKS branch**" only.
**Why it happens:** Both lines are in the same short function and look symmetric; a mechanical find-and-replace across the function would touch both.
**How to avoid:** Fix only line 371. Line 367 is deliberately excluded — SOCKS-layer hiccups feed the existing short fixed-retry path and were judged (in the prior LOG-17 cataloguing, not this phase's D-01) as legitimately transient noise, separate from the "expected/transient" candidates D-01 already decided to promote anyway. Since D-01 explicitly did NOT resolve to promote this one, leave it as `logger.d`.
**Warning signs:** A diff touching `UmbraNostrClient.kt` with more than one changed `logger.` line inside `logWebSocketFailure`.

### Pitfall 2: D-04's spy-logger pattern silently can't observe most of this phase's fixes
**What goes wrong:** A test asserting "the fixed catch block now calls `logger.e()`" only works if the class under test's `logger` field can be replaced with the spy. `LoggerTest.kt`'s own doc comment confirms `app/build.gradle.kts` sets `testOptions.unitTests.isReturnDefaultValues = true`, so `android.util.Log.isLoggable` returns `false` in a plain JVM test with no Robolectric — meaning the **real** `Logger` class's `d`/`w`/`e` methods never invoke their message lambda at all in this test suite. A test that tries to assert something by injecting a real `UmbraLog.tag(...)`-backed `Logger` and checking side effects would trivially pass or fail for the wrong reason.
**Why it happens:** 13 of the 15 touched call sites live in 6 classes that self-construct their logger internally (`private val logger = UmbraLog.tag(TAG)`, or the module-level Compose-file equivalent) rather than taking `UmbraLogger` as a constructor parameter. Only `PublishSignedEventUseCase`/`PublishAuthEventUseCase` (2 of 8 LOG-17 sites) are constructor-injectable today. `LoginViewModel`, `UmbraNostrClient`/`RelayMessageHandling.kt`/`RelayWebSocketListener.kt` (which share `UmbraNostrClient`'s field), `EventRepositoryImpl`, `NegentropySyncOrchestrator`, and `SettingsScreen.kt` are **not** constructor-injectable without a refactor. `LogoutUseCase`/`TrimMemoryCachesUseCase` have no logger at all yet, but are a clean case (see below).
**How to avoid:** Do not silently assume D-04 applies uniformly. Recommended split (needs explicit user/planner confirmation, since CONTEXT.md's D-04 doesn't resolve it):
- **Spy-testable today, no refactor:** `PublishSignedEventUseCase`, `PublishAuthEventUseCase` — write the spy test directly.
- **Spy-testable with a *cheap* refactor:** `LogoutUseCase`, `TrimMemoryCachesUseCase` — adding `UmbraLogger` as a brand-new constructor param is low-risk (both are plain classes wired via `@Provides` in `UseCaseModule.kt`, not Hilt `@Inject constructor`; `LogoutUseCase` has exactly 5 existing call sites to update in `BackfillDeleteLogoutUseCaseTest.kt`, all positional-arg; `TrimMemoryCachesUseCase` has **no existing test file** to update at all).
- **NOT spy-testable without a broader DI refactor:** `LoginViewModel`, `UmbraNostrClient`/`RelayMessageHandling.kt`/`RelayWebSocketListener.kt`, `EventRepositoryImpl`, `NegentropySyncOrchestrator`. These are `@Inject constructor`/`@HiltViewModel` classes; making `UmbraLogger` injectable per-class would need per-class Hilt `@Named` qualifiers (there's no single global `UmbraLogger` binding possible, since every class needs its own tag) — real added DI ceremony across production code, not scoped by CONTEXT.md's "logging visibility work only, no behavior change" boundary. `SettingsScreen.kt` is a Composable function with no ViewModel/constructor to inject into at all — same conclusion by a different mechanism.
For the third group, recommend verifying via `compileDebugKotlin` + `lintDebug` + manual review using the `find-non-lambda-logs` skill's Check 2 grep pattern, not a spy-based test. This is not a downgrade from D-04's regression-protection intent for those sites — see `TESTING.md`: no test in this codebase today asserts logger call arguments for *any* of these 6 classes, so there is no regression baseline to preserve; the phase still meaningfully raises coverage where it's cheap (2 of 8 LOG-17 sites, and both wipe-path use cases) without taking on unscoped DI-refactor risk for the rest.
**Warning signs:** A plan task that says "add spy-logger test for `LoginViewModel`" without a preceding task that first makes `LoginViewModel`'s logger injectable — that dependency would be silently missing.

### Pitfall 3: Manually re-scrubbing inside `logger.e()`
**What goes wrong:** Writing `logger.e(e) { "Failed: ${scrubThrowableMessageForLogs(e)}" }` — this is redundant, not wrong, but it's explicitly called out as an anti-pattern in both CONTEXT.md's D-01 and the `find-non-lambda-logs` skill's "Do NOT flag" section (since `e()` already scrubs `e`'s message internally and appends it).
**Why it happens:** Every other `logger.d`/`logger.w` call site in these same files already does manual scrubbing (that's the established pre-`e()` pattern) — pattern-matching against neighboring code produces this by habit.
**How to avoid:** For every LOG-17 site, drop the `scrubThrowableMessageForLogs(e)` call and any `import` that becomes unused as a result — some of these files (e.g. `PublishEventUseCases.kt`) import `scrubThrowableMessageForLogs` specifically for the sites being fixed; check whether it's still used elsewhere in the file (`PublishEventUseCases.kt` uses it in exactly the 2 sites being fixed, so the import becomes unused and should be removed — verify with `compileDebugKotlin`'s unused-import warning, which lint treats as an error per CI).
**Warning signs:** `lintDebug` failing on an unused-import warning after the LOG-17 fixes land.

### Pitfall 4: `LogoutUseCase`'s outer catch (line 73) and the unwrapped `userPreferences.clearAll()` call (line 72) are outside D-02's stated scope
**What goes wrong:** `LogoutUseCase.invoke()` has an 8th catch — `try { ... 7 per-step catches ... userPreferences.clearAll() } catch (_: Exception) { /* best-effort logout; callers handle any further errors */ }` (line 73) — that wraps the whole method body, including a call to `userPreferences.clearAll()` (line 72) that is **not** individually wrapped in its own per-step try/catch the way the other 7 steps are. If `userPreferences.clearAll()` throws, only this outer, still-silent catch would see it.
**Why it happens:** CONTEXT.md's D-02 and `docs/CONCERNS.md`'s LOG-27 entry both count exactly 7 per-step catches for `LogoutUseCase`, matching what's actually in the file — the outer catch and the unwrapped final call are real, but they're a distinct, out-of-scope gap that a mechanical "fix every silent catch in this file" pass would incorrectly sweep in.
**How to avoid:** Leave line 73's outer catch as-is; do not add an 8th `logger.e()` call there, and do not wrap line 72 in a new try/catch — neither is one of the 7 sites D-02 locked in. Flag this residual gap for the user/backlog rather than silently expanding scope.
**Warning signs:** A diff for `LogoutUseCase.kt` touching more than 7 catch blocks, or adding a new try/catch around line 72.

### Pitfall 5: CONTEXT.md's `docs/CONCERNS.md` path doesn't exist
**What goes wrong:** CONTEXT.md's canonical_refs section cites `docs/CONCERNS.md` for LOG-17/LOG-18/LOG-20/LOG-26/LOG-27/LOG-28 root-cause analysis. That file does not exist at that path.
**Why it happens:** The file actually lives at `.planning/codebase/CONCERNS.md` (confirmed by `find`) — likely a stale path left over from before the codebase-mapping docs were relocated under `.planning/codebase/`.
**How to avoid:** When the plan or execute-phase agent needs the root-cause narrative CONTEXT.md points at, read `.planning/codebase/CONCERNS.md` instead. All the content referenced in CONTEXT.md (LOG-17 through LOG-28 entries) was independently re-read from that file this session and matches CONTEXT.md's summary.
**Warning signs:** A `Read` tool call failing on `docs/CONCERNS.md` during planning or execution.

## Code Examples

### The complete target shape for a LOG-17 site (D-01)
```kotlin
// Before (current, all 8 sites)
}.onFailure { e ->
    logger.d { "Failed to publish signed event: ${scrubThrowableMessageForLogs(e)}" }
}

// After
}.onFailure { e ->
    logger.e(e) { "Failed to publish signed event" }
}
```
[VERIFIED: app/src/main/java/com/umbra/app/domain/usecase/PublishEventUseCases.kt:41-43 — current "before" state]

### The complete target shape for a LOG-18 site (D-02)
```kotlin
// Before
logger.d { "FEED_NOTES EOSE from relay $relayUrl reported MORE — not advancing since watermark" }

// After
logger.d { "FEED_NOTES EOSE from relay ${scrubUrlForLogs(relayUrl)} reported MORE — not advancing since watermark" }
```
[VERIFIED: app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt:428 — current "before" state]

### The complete target shape for LogoutUseCase's per-step catches (D-02, new logger dependency)
```kotlin
// Before (7 identical-shape sites)
try {
    nostrSessionController.stop()
} catch (_: Exception) { }

// After — constructor gains `private val logger: UmbraLogger` (8th param); UseCaseModule.kt's
// provideLogoutUseCase wires it via UmbraLog.tag(...), matching PublishEventUseCases.kt's
// existing precedent (no Hilt binding for UmbraLogger required)
try {
    nostrSessionController.stop()
} catch (e: Exception) {
    logger.e(e) { "nostrSessionController.stop() failed during logout" }
}
```
[VERIFIED: app/src/main/java/com/umbra/app/domain/usecase/LogoutUseCase.kt:33-35 — current "before" state]

## State of the Art

Not applicable — this phase modifies call sites within an existing, already-current internal logging wrapper. No external framework/library versioning is involved.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | ASVS "Error Handling and Logging" category (numbered V7 in ASVS 4.0.3, renumbered in later drafts) is the relevant category for this phase's changes | Security Domain | Low — the actually-enforced standard for this codebase is AUDIT.md §1.3, which was read in full this session and is what all findings above are checked against; the ASVS category name is informational framing only, not a control being newly introduced |
| A2 | The recommended tag strings `"UmbraLogout"`/`"UmbraTrimMemory"` for the new `LogoutUseCase`/`TrimMemoryCachesUseCase` loggers are illustrative, not fixed | DI wiring section | None — CONTEXT.md leaves exact naming to planner discretion; any `"UmbraXxx"`-shaped tag satisfies the established convention (`private const val TAG = "UmbraXxx"` per `AUDIT.md`/existing classes) |

**If this table is empty:** N/A — see above; both entries are low-risk framing/naming choices, not unverified factual claims about the codebase itself. Every claim about actual source code, line numbers, and call signatures in this document was verified by reading the file directly this session (see `[VERIFIED: path:lines]` tags throughout).

## Open Questions

1. **Does D-04's spy-logger test coverage apply to all 6 non-injectable classes, or only the 2+2 where it's cheap?**
   - What we know: Only `PublishSignedEventUseCase`/`PublishAuthEventUseCase` are constructor-injectable today; `LogoutUseCase`/`TrimMemoryCachesUseCase` can become injectable cheaply (new dependency, `@Provides`-wired, small test-file blast radius: 5 call sites in one existing test file for `LogoutUseCase`, zero for `TrimMemoryCachesUseCase`). `LoginViewModel`, `UmbraNostrClient`/`RelayMessageHandling.kt`/`RelayWebSocketListener.kt`, `EventRepositoryImpl`, `NegentropySyncOrchestrator`, and `SettingsScreen.kt` would need a broader Hilt `@Named`-qualifier-per-class refactor or (for the Composable) have no injection point at all.
   - What's unclear: Whether the user, on seeing this trade-off, wants the broader refactor anyway (full D-04 coverage, more risk/diff size) or accepts the narrower split (recommended here).
   - Recommendation: Default to the narrow split (spy tests only where already cheap; compile+lint+manual review for the rest) unless the user explicitly asks for full coverage. Surface this explicitly in the plan's task list rather than silently picking one.

2. **Should `LogoutUseCase.kt`'s outer catch (line 73) or the unwrapped `userPreferences.clearAll()` call (line 72) get any attention in this phase?**
   - What we know: Both are outside the 7 sites D-02 explicitly counted; `docs/CONCERNS.md`'s LOG-27 entry and CONTEXT.md agree on exactly 7.
   - What's unclear: Whether this residual gap (an unwrapped call whose failure still falls into a silent outer catch) should be logged as a new backlog item during this phase's work, per CLAUDE.md's "log an item the moment it's found" bug-tracking rule.
   - Recommendation: Log a new `LOG-32` entry in `docs/TODO.md` (next available ID — global counter currently at LOG-31 per STATE.md) noting the gap, without fixing it in this phase (D-02's scope is locked to 7 sites).

## Environment Availability

Skipped — this phase has no external tool/service/runtime dependency beyond the existing Gradle/JDK 17/Android SDK toolchain already required for every change in this repository (per CLAUDE.md's Commands section). No new dependency is introduced.

## Validation Architecture

Skipped — `.planning/config.json`'s `workflow.nyquist_validation` is explicitly `false`.

## Security Domain

Required — `security_enforcement: true`, `security_asvs_level: 1` in `.planning/config.json`.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| Error Handling and Logging (ASVS V7 in 4.0.3) [ASSUMED — category name/number not re-verified against the live spec this session] | Yes | AUDIT.md §1.3's `LogScrubber`/`Logger.e(throwable)` stack — the actually-enforced, verified control for this codebase; see Standard Stack above |
| V5 Input Validation | No | Not touched — no new input parsing in this phase |
| V6 Cryptography | No | Not touched |

### Known Threat Patterns for this phase's stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|-----------------------|
| Raw relay URL / pubkey / exception message reaching a release-build log (Information Disclosure) | Information Disclosure | `LogScrubber.scrubUrlForLogs`/`scrubPubkeyForLogs`/`scrubThrowableMessageForLogs`, already routed through for every fix in this phase per D-01's "don't manually re-scrub" instruction (the wrapper does it once, correctly) — see AUDIT.md §1.3, `[VERIFIED: app/src/main/java/com/umbra/app/util/logging/Logger.kt:23-27]` |
| A promoted error-level log becoming a new, previously-invisible high-volume noise source in release logcat (the 3 "expected/transient" LOG-17 sites) | Denial of Service (log-volume, not a security DoS in the traditional sense, but flagged in CONTEXT.md's Claude's Discretion) | Explicitly left to planner discretion by CONTEXT.md — not a required mitigation for this phase; do not add throttling infrastructure speculatively |

## Sources

### Primary (HIGH confidence — all read directly this session)
- `app/src/main/java/com/umbra/app/util/logging/Logger.kt`, `UmbraLog.kt` — wrapper implementation
- `app/src/main/java/com/umbra/app/domain/logging/UmbraLogger.kt` — domain port + `NoOpUmbraLogger`
- `app/src/main/java/com/umbra/app/util/LogScrubber.kt` — scrubbing helpers
- All 8 LOG-17 source files, all 3 LOG-18 source files, all 4 previously-silent-catch source files (see Architecture Patterns tables for exact line ranges read)
- `app/src/main/java/com/umbra/app/ui/feed/FeedScreen.kt` — already-shipped LOG-25 reference fix
- `app/src/main/java/com/umbra/app/di/UseCaseModule.kt` — DI wiring precedent for injectable loggers
- `app/src/test/java/com/umbra/app/util/logging/LoggerTest.kt` — confirms `isReturnDefaultValues = true` makes real `Logger` lambda-invocation unobservable in plain JVM tests
- `app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt`, `NegentropySyncOrchestratorTest.kt`, `EventRepositoryIngestionIntegrationTest.kt` — existing test construction patterns for touched classes
- `.planning/codebase/TESTING.md` — confirms no MockK/Mockito, `Fake[InterfaceName]` hand-rolled convention
- `.planning/codebase/CONCERNS.md` — LOG-17 through LOG-28 root-cause narrative (note: CONTEXT.md cites this at the stale path `docs/CONCERNS.md`, see Pitfall 5)
- `docs/KNOWN_ISSUES.md`, `docs/TODO.md`, `docs/DONE.md` — current bug-tracking entries and global LOG-N counter state
- `.claude/skills/find-non-lambda-logs/SKILL.md` — audit checklist for this phase's fix category
- `AUDIT.md` §1.3 — authoritative log-scrubbing rules

### Secondary (MEDIUM confidence)
None — every claim in this document is either read directly from source this session or a locked decision copied verbatim from CONTEXT.md.

### Tertiary (LOW confidence)
None.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; existing wrapper read in full
- Architecture: HIGH — every call site read directly, line numbers cross-checked and confirmed against CONTEXT.md with zero drift
- Pitfalls: HIGH — the D-04 injectability gap was discovered by reading every touched class's constructor and existing test-construction call sites, not inferred

**Research date:** 2026-09-02
**Valid until:** Effectively indefinite for the call-site facts (internal code, not a fast-moving external dependency) — but re-verify line numbers if any other phase or commit touches these same 10 files before Phase 1 executes, since CONTEXT.md itself notes prior line-number drift happened once already (`EventRepositoryImpl.kt`'s LOG-18 sites shifted from `:493/:551` to `:428/:486` after an unrelated extraction, per `docs/KNOWN_ISSUES.md:250`).
