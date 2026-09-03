---
phase: 01-error-visibility-log-hygiene
reviewed: 2026-09-03T11:50:38Z
depth: standard
files_reviewed: 18
files_reviewed_list:
  - app/src/main/java/com/umbra/app/data/nostr/RelayMessageHandling.kt
  - app/src/main/java/com/umbra/app/data/nostr/RelayWebSocketListener.kt
  - app/src/main/java/com/umbra/app/data/nostr/UmbraNostrClient.kt
  - app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt
  - app/src/main/java/com/umbra/app/data/repository/NegentropySyncOrchestrator.kt
  - app/src/main/java/com/umbra/app/di/UseCaseModule.kt
  - app/src/main/java/com/umbra/app/domain/usecase/LogoutUseCase.kt
  - app/src/main/java/com/umbra/app/domain/usecase/PublishEventUseCases.kt
  - app/src/main/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCase.kt
  - app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt
  - app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt
  - app/src/test/java/com/umbra/app/domain/usecase/BackfillDeleteLogoutUseCaseTest.kt
  - app/src/test/java/com/umbra/app/domain/usecase/PublishEventUseCasesTest.kt
  - app/src/test/java/com/umbra/app/domain/usecase/TrimMemoryCachesUseCaseTest.kt
  - app/src/test/java/com/umbra/app/testutil/fakes/FakeUmbraLogger.kt
  - docs/DONE.md
  - docs/KNOWN_ISSUES.md
  - docs/TODO.md
findings:
  critical: 1
  warning: 4
  info: 3
  total: 8
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-09-03T11:50:38Z
**Depth:** standard
**Files Reviewed:** 18
**Status:** issues_found

## Summary

The diff (`27130af^..HEAD`) promotes roughly two dozen catch/failure sites from silent or
debug-level logging to `logger.e(throwable) { }`, adds a `FakeUmbraLogger` test double with
matching unit tests, and does the corresponding `docs/KNOWN_ISSUES.md`/`docs/TODO.md`/`docs/DONE.md`
bookkeeping. Message-level scrubbing at each new call site is consistent — none of the
`message()` lambda strings interpolate a raw relay URL, pubkey, or event content — and the two
scrub-only fixes (`EventRepositoryImpl.kt`'s two relay-URL interpolations,
`NegentropySyncOrchestrator.kt`'s `e.message`) are exactly what the tracking docs describe.
`compileDebugKotlin` and the three affected test classes (`BackfillDeleteLogoutUseCaseTest`,
`PublishEventUseCasesTest`, `TrimMemoryCachesUseCaseTest`) all pass.

However, the phase's central premise — "these messages are now scrubbed and safe to always emit
in release" — does not hold for the `Throwable` object itself. `Logger.e()` passes the raw,
un-sanitized `throwable` through to `android.util.Log.e(tag, msg, throwable)` as its third
argument, purely so Android prints a stack trace. Android's own stack-trace formatting embeds the
exception's own (unscrubbed) `toString()` — class name plus raw `message`, for every exception in
the cause chain — ahead of the frame list. Every one of the newly-promoted call sites in this
diff hands a real, live-network `Throwable` (SOCKS/TLS/IO exceptions whose messages routinely
contain a hostname, `.onion` address, or resolved IP) to this path, and because these are now
error-level, they print unconditionally in release builds where the same throwable previously
never printed at all (debug builds are filtered by `Log.isLoggable`). This is the single most
important finding below (CR-01) — it inverts the review's core question: the *messages* are
scrubbed, but the *throwables* attached to them are not, and the throwable is exactly the part of
each of these call sites that changed behavior.

A handful of smaller gaps sit alongside it: one already-tracked, still-live swallowed-throwable
site inside `LogoutUseCase` (`docs/TODO.md` LOG-32 — confirmed still present), one *not* tracked
(`LoginViewModel.requestAmberLogin()`'s catch block never reaches a logger at all), and one
dead-code catch block added by this phase (`SettingsScreen.kt`) that can never execute because
the method it wraps already swallows everything internally.

## Critical Issues

### CR-01: `logger.e(throwable)` reprints the throwable's raw (unscrubbed) message via Android's stack-trace formatting, at every newly-promoted call site

**File:** `app/src/main/java/com/umbra/app/data/nostr/UmbraNostrClient.kt:371` (representative; same
root cause applies to every other `logger.e(...)` call this diff adds or touches — see full list
below)
**Issue:**

`Logger.e()` (`app/src/main/java/com/umbra/app/util/logging/Logger.kt:23-27`, not itself part of
this diff but the sole implementation every call in this diff routes through) does:

```kotlin
override fun e(throwable: Throwable, message: () -> String) {
    if (Log.isLoggable(tag, Log.ERROR)) {
        Log.e(tag, "${message()}: ${LogScrubber.scrubThrowableMessageForLogs(throwable)}", throwable)
    }
}
```

The *string* argument is correctly scrubbed. But the third argument — the raw `throwable` object
— is also passed straight to `android.util.Log.e(String, String, Throwable)`. Android's
implementation of that overload appends `Log.getStackTraceString(tr)` to the printed line, and
that helper calls `tr.printStackTrace(...)`, whose first line is `tr.toString()` —
`"<ExceptionClassName>: <raw message>"` — repeated for every exception in the `cause` chain
(`"Caused by: ..."`). None of that text passes through `LogScrubber`. So the scrubbed string
computed on the line above is functionally redundant: the very next thing `Log.e` prints is the
same message, unscrubbed, sourced directly from the `Throwable`.

This is not hypothetical for this codebase: every call site this diff promotes to `logger.e(...)`
is either a live-network failure path (SOCKS/TLS/timeout/connect exceptions from OkHttp, whose
`.message` routinely embeds a hostname, `.onion` address, or resolved `IP:port` — e.g.
`ConnectException: Failed to connect to /198.51.100.7:443`) or a JSON/event-parsing failure whose
message can echo back malformed input. Before this phase, these all logged at **debug** level, so
`Log.isLoggable(tag, Log.DEBUG)` filtered them out of release builds entirely — the raw throwable
text never reached a release logcat. Promoting them to **error** level (this phase's whole point)
removes that filter: `Log.isLoggable(tag, Log.ERROR)` is true by default with no ProGuard
stripping of `Log.e` in this project's `proguard-rules.pro`, so every one of these now prints
unconditionally in release builds, complete with the unscrubbed throwable text the scrubbing pass
was specifically meant to prevent.

This directly contradicts `AUDIT.md`'s own claim about this exact code
(`AUDIT.md:87`: *"`Logger.e(throwable) { }` scrubs the throwable's own message automatically via
`LogScrubber`"*) and the `find-non-lambda-logs` skill's identical claim
(`.claude/skills/find-non-lambda-logs/SKILL.md:34`). Both describe the intended behavior, not the
actual one — the scrubbing only reaches the `message()` string, not the object Android itself
re-stringifies a few lines later.

Affected call sites added or touched by this diff (all route through the same `Logger.e`):
- `data/nostr/UmbraNostrClient.kt:371` (`logWebSocketFailure`)
- `data/nostr/RelayMessageHandling.kt:139` (`onWebSocketMessage` catch)
- `data/nostr/RelayWebSocketListener.kt:51` (incoming-drain `onFailure`)
- `data/repository/EventRepositoryImpl.kt:502` (`clearAllData`'s `disconnectFromAll()` catch)
- `domain/usecase/LogoutUseCase.kt:38,43,55,67,72,77,84` (all seven per-step catches)
- `domain/usecase/TrimMemoryCachesUseCase.kt:32,37,42,47,52` (all five per-step catches)
- `domain/usecase/PublishEventUseCases.kt:41,68`
- `ui/auth/LoginViewModel.kt:85,100,137,151,230`
- `ui/settings/SettingsScreen.kt:212`

**Fix:** Stop handing the live `Throwable` to `Log.e`'s trace-printing path unscrubbed. Build a
sanitized throwable that keeps the stack frames (harmless — just class/method/file/line of this
app's own code and library internals) but replaces the exception's own message/cause chain with
the already-scrubbed text:

```kotlin
override fun e(throwable: Throwable, message: () -> String) {
    if (Log.isLoggable(tag, Log.ERROR)) {
        val scrubbed = LogScrubber.scrubThrowableMessageForLogs(throwable)
        val safeForTrace = RuntimeException("${throwable.javaClass.simpleName}: $scrubbed").apply {
            stackTrace = throwable.stackTrace
        }
        Log.e(tag, "${message()}: $scrubbed", safeForTrace)
    }
}
```

`Logger.kt` isn't in this phase's file list, so this fix belongs to a follow-up, but it should be
filed (`docs/KNOWN_ISSUES.md`, new `LOG-<n>`) before this phase is considered to have actually
closed the release-log-leak gap it set out to close — right now it has widened the exposure
window for every one of the call sites above from "never" (filtered at debug) to "every
occurrence" (always printed at error), without actually fixing the underlying leak vector.

## Warnings

### WR-01: `LogoutUseCase`'s outer catch still silently swallows a failure from the one unwrapped step (`userPreferences.clearAll()`)

**File:** `app/src/main/java/com/umbra/app/domain/usecase/LogoutUseCase.kt:88-91`
**Issue:** All seven inner cleanup steps now have their own `try { ... } catch (e: Exception) { logger.e(e) { ... } }`, but `userPreferences.clearAll()` on line 88 sits directly inside the outer `try`, and the outer handler is `catch (_: Exception) { /* best-effort logout; callers handle any further errors */ }`. If `userPreferences.clearAll()` throws — the step that actually removes the stored pubkey/auth state — that exception is discarded with zero logging, the exact anti-pattern the other seven sites in this same file were just fixed for. This is already tracked (`docs/TODO.md` LOG-32, added in this same change), so it isn't a missed finding by the implementer — but it is a confirmed, still-live gap in the file under review, and it's the last step of the logout path, arguably the most safety-critical one to lose visibility into.
**Fix:** Wrap `userPreferences.clearAll()` in its own per-step `try/catch` matching the other seven (as LOG-32 itself proposes), or at minimum log inside the outer catch instead of discarding it silently.

### WR-02: `LoginViewModel.requestAmberLogin()`'s catch block never reaches a logger — the throwable is fully discarded

**File:** `app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt:182-192`
**Issue:** Unlike every other catch block touched by this phase, `requestAmberLogin()`'s catch neither logs nor rethrows — it only sets `errorMessage = UiMessage.Res(R.string.login_amber_response_error, listOf(e.message ?: ""))`. This predates the diff (not part of the changed hunks), but it's in a file this phase substantially edited for exactly this class of bug, and it's the same swallowed-throwable pattern `docs/KNOWN_ISSUES.md`'s LOG-25/26/27/28 entries were written to close elsewhere in this same file. It isn't currently tracked under any `LOG-<n>` entry.
**Fix:** Add `logger.e(e) { "Amber login response failed" }` (or similar) before updating `_authState`, matching the pattern applied to every sibling catch block in this file during this phase.

### WR-03: `SettingsScreen.kt`'s newly-added logout catch block is dead code — it can never execute

**File:** `app/src/main/java/com/umbra/app/ui/settings/SettingsScreen.kt:203-213`
**Issue:** The fix for LOG-26 wraps `loginViewModel.logout()` in a new `try { ... } catch (e: Exception) { settingsScreenLogger.e(e) { "Logout failed" } }`. But `LoginViewModel.logout()` (`ui/auth/LoginViewModel.kt:225-234`) already wraps its own body in `try { logoutUseCase() } catch (e: Exception) { logger.e(e) { "Logout failed" } } finally { _authState.update { AuthState() } }` — it catches `Exception`, never rethrows, and the `finally` always runs. `logout()` is a normal (non-throwing-by-contract) suspend function; the only other statement inside `SettingsScreen`'s new `try` is `isLoggingOut = true`, a plain state assignment that cannot throw. So the new `catch` block added by this diff is unreachable: it looks like it improved logout-failure visibility for this screen, but the actual visibility improvement already happened one layer down in `LoginViewModel.logout()` itself (LOG-25's fix), and this one is a no-op duplicate.
**Fix:** Either remove the now-redundant `try/catch` in `SettingsScreen.kt` (since `logout()` never throws), or — if defense-in-depth against a future change to `logout()`'s contract is the intent — say so in a comment so a future reader doesn't mistake it for a load-bearing fix the way `docs/KNOWN_ISSUES.md`'s LOG-26 entry currently implies it is.

### WR-04: `LoginViewModel` surfaces raw, unscrubbed `e.message` directly into on-screen UI text

**File:** `app/src/main/java/com/umbra/app/ui/auth/LoginViewModel.kt:150-160` (`savePublicKey`) and `:182-192` (`requestAmberLogin`)
**Issue:** Both catch blocks build `UiMessage.Res(R.string.login_error_with_detail / login_amber_response_error, listOf(e.message ?: ""))` — the raw exception message, never passed through `LogScrubber`, is interpolated into a string resource and rendered on-screen. This predates the diff, but it's a more direct exposure surface than the logcat-only leak in CR-01 (it's visible to anyone looking at the device, screenshot-able, no `adb logcat` access needed), for the same class of exception (relay/network calls inside the enclosing `try`, e.g. `relayRepository.bootstrapDefaultsOnFirstLogin()`) that this phase's CR-01 finding is about. Not part of this phase's diff, so not a regression it introduced, but directly on-theme for "error visibility & log hygiene" and worth folding into the same follow-up.
**Fix:** Route `e.message` through `LogScrubber.scrubThrowableMessageForLogs(e)` (or a UI-appropriate equivalent) before it reaches `UiMessage.Res`, or drop the raw detail from the user-facing string entirely and rely on the (now error-level, once CR-01 is fixed) log line for diagnosis.

## Info

### IN-01: `docs/KNOWN_ISSUES.md`'s LOG-27 entry says a gap is "not yet filed as its own entry" — but `docs/TODO.md`'s LOG-32, added in the same change, is exactly that entry

**File:** `docs/KNOWN_ISSUES.md` (LOG-27 fix note) / `docs/TODO.md` (LOG-32)
**Issue:** The LOG-27 fix note added by this diff reads: *"the outer method-wide catch and the unwrapped `userPreferences.clearAll()` call in `LogoutUseCase` are intentionally untouched (separate, smaller residual gap, not yet filed as its own entry)."* But this same diff also adds `docs/TODO.md`'s LOG-32, titled *"LogoutUseCase's outer catch and unwrapped final cleanup call are still silent"* — describing precisely that gap. The two files were edited in the same change, so the "not yet filed" wording in LOG-27 is stale the moment it was committed.
**Fix:** Update the LOG-27 fix note to reference LOG-32 by number instead of describing it as unfiled.

### IN-02: Inconsistent indentation inside `clearAllData()`'s newly-edited catch block

**File:** `app/src/main/java/com/umbra/app/data/repository/EventRepositoryImpl.kt:493-503`
**Issue:** The outer `try` (line 497, `try {`) and the comment above the inner `try`/`disconnectFromAll()` (lines 498-499) sit at the same indentation level as `isWiping.set(true)` (line 496) rather than one level deeper, so the block reads as flush-left relative to its own `try`. Pre-existing structure, but the new `catch (e: Exception) { logger.e(e) { ... } }` on lines 501-503 was added into this already-misindented block rather than the misindentation being corrected in passing.
**Fix:** Re-indent lines 497-541 one level deeper under `withContext(Dispatchers.IO) {`'s `try {`, consistent with the rest of the file's formatting (a formatter/`ktlint` pass would catch this).

### IN-03: `NegentropySyncOrchestrator`'s per-relay sync failure stays at debug level while eleven-plus sibling sites in the same phase were promoted to error

**File:** `app/src/main/java/com/umbra/app/data/repository/NegentropySyncOrchestrator.kt:118-119`
**Issue:** This is the one site in the diff that only received a scrubbing fix (`e.message` to `LogScrubber.scrubThrowableMessageForLogs(e)`), not a level promotion — it's already correctly scoped that way by `docs/KNOWN_ISSUES.md` LOG-18 (a scrub-only fix) rather than the LOG-17 promotion sweep, and per-relay NIP-77 sync failures are plausibly a legitimately routine/high-frequency event where debug is the right level. Flagging only so the level choice here — the one place in this phase's diff where a caught exception is deliberately *not* promoted — is a visible, reviewable decision rather than an accidental omission.
**Fix:** None required if the debug level is intentional (it reads that way); no action needed beyond confirming that reading is correct.

---

_Reviewed: 2026-09-03T11:50:38Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
