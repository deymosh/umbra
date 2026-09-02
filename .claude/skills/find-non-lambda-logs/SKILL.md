---
name: find-non-lambda-logs
description: Use when auditing or reviewing logger.d/w/e calls in Umbra — checks two things, not three: (1) unscrubbed relay URL/pubkey/hex/nsec-shaped content reaching log calls (AUDIT.md violation, the most important check here), (2) catch blocks that lose the throwable by calling logger.d/w with a scrubbed message string instead of logger.e(throwable) { }. Umbra uses a lambda-taking wrapper (UmbraLog.tag(TAG) → Logger, implementing the domain-layer UmbraLogger interface) with Log.isLoggable gating built into the wrapper itself — there is no plain android.util.Log call site left anywhere outside the wrapper's own implementation file, and no manual isLoggable guard for callers to add.
---

# Auditing log calls in Umbra

Umbra's logging goes exclusively through a lambda-taking wrapper — there is no plain `Log.d(TAG, "...")` anywhere in the codebase outside the wrapper's own implementation. Don't flag missing `Log.isLoggable` guards at call sites; the wrapper already gates every call internally, before the lambda is even evaluated.

## The wrapper

`app/src/main/java/com/umbra/app/util/logging/UmbraLog.kt` — factory: `UmbraLog.tag(TAG)` returns a `Logger`.

`app/src/main/java/com/umbra/app/util/logging/Logger.kt` — the Android-backed implementation:

```kotlin
class Logger internal constructor(private val tag: String) : UmbraLogger {
    override fun d(message: () -> String) {
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, message())
    }
    override fun w(message: () -> String) {
        if (Log.isLoggable(tag, Log.WARN)) Log.w(tag, message())
    }
    override fun e(throwable: Throwable, message: () -> String) {
        if (Log.isLoggable(tag, Log.ERROR)) {
            Log.e(tag, "${message()}: ${LogScrubber.scrubThrowableMessageForLogs(throwable)}", throwable)
        }
    }
}
```

`domain/logging/UmbraLogger.kt` is the pure-Kotlin interface (`d`, `w`, `e`) that `domain/` code depends on instead of the Android-backed `Logger` directly, plus a `NoOpUmbraLogger` for tests.

**Three methods only** — `d(() -> String)`, `w(() -> String)`, `e(Throwable, () -> String)`. No `i`/`v`. `e()` *requires* a throwable — there's no throwable-less error overload, and it doesn't need one: `e()` already scrubs `throwable.message` via `LogScrubber.scrubThrowableMessageForLogs` and appends it to the caller's message, while still passing the raw `throwable` object through to `Log.e(tag, msg, throwable)` for the stack trace. A call site should never re-scrub the throwable manually inside the lambda passed to `e()` — that's already handled.

Every class obtains its logger the same way: `private val logger = UmbraLog.tag(TAG)` with `private const val TAG = "UmbraXxx"` alongside it.

## Check 1 (most important): unscrubbed sensitive content

AUDIT.md: "Logs must be scrubbed of relay URLs, pubkeys, and profile/event content in release builds (`LogScrubber` helpers), gated behind `Log.isLoggable`." This is a privacy requirement specific to Umbra — a raw pubkey, relay URL, or npub/nsec-shaped string in a release-build log is exactly the kind of leak this whole client exists to prevent. The isLoggable half of this rule is already satisfied by the wrapper for every call; scrubbing is not — that's still the caller's job.

`app/src/main/java/com/umbra/app/util/LogScrubber.kt` (plain `object`) exposes:

```kotlin
fun scrubUrlForLogs(url: String?): String                    // "[url]" or "scheme://[redacted]"
fun scrubEndpointForLogs(host: String?, port: Int?): String   // always "[endpoint]"
fun scrubPubkeyForLogs(pubkey: String?): String                // "[pubkey]" or first 8 chars + "..."
fun scrubThrowableMessageForLogs(throwable: Throwable): String
fun scrubMessageForLogs(message: String?): String              // regex-scrubs urls/nostrsigner:/host:port/npub/nsec/hex64
```

**What to flag:** a `logger.d/w/e { ... }` call whose lambda body interpolates a relay URL, pubkey/npub/nsec-shaped hex, or raw exception message **not** passed through the matching `scrub*ForLogs` function first. Concretely:

```kotlin
// ❌ FLAG — raw url, no scrubbing
logger.d { "Connecting to $relayUrl" }

// ✅ correct
logger.d { "Connecting to ${scrubUrlForLogs(relayUrl)}" }

// ❌ FLAG — raw exception message, could contain a URL/pubkey embedded in it
logger.w { "Failed: ${e.message}" }

// ✅ correct
logger.w { "Failed: ${scrubThrowableMessageForLogs(e)}" }
```

Search: grep `logger\.(d|w)\s*\{` (and `client\.logger\.(d|w)\s*\{` — some `data/nostr` classes hang the logger off a `client` reference) for interpolated `$relayUrl`/`$url`/`$pubkey`/`$npub`/`.message` patterns that don't also contain `scrub` on the same line. Treat a new unscrubbed hit as a real, not hypothetical, regression to flag, not noise.

## Check 2: throwable dropped by using `d`/`w` instead of `e`

The wrapper makes this check mechanical in a way a plain `Log.*` codebase can't: `e()` is the *only* method that accepts a `Throwable` at all. So the bug shape isn't "missing third argument" — it's "a catch block reaching for `logger.d`/`logger.w` with a manually-scrubbed message string, when `logger.e(throwable) { }` was available and drops the actual stack trace on the floor either way." This is exactly what `LOG-17` in `docs/CONCERNS.md`/`docs/TODO.md` catalogs — sites that lost throwable attachment during the migration to this wrapper.

```kotlin
// ❌ FLAG — throwable is available (it's `e` in the catch clause) but never reaches the logger;
// stack trace is gone, and this manual scrub is redundant with what e() already does
catch (e: Exception) {
    logger.d { "Anonymous login failed: ${scrubThrowableMessageForLogs(e)}" }
}

// ✅ correct — e() auto-scrubs and appends the message, and preserves the stack trace
catch (e: Exception) {
    logger.e(e) { "Anonymous login failed" }
}
```

**What to flag:** any `catch (e: ...)` (or `.onFailure { e -> ... }`/`.getOrElse { e -> ... }`) block whose only logging is `logger.d { ... }` or `logger.w { ... }` referencing the caught exception's message — that's a throwable being discarded when `logger.e(caughtException) { }` was one call away. This is a judgment call on log *level*, not just presence: a genuinely expected/transient failure (e.g. an ordinary relay disconnect that feeds the existing backoff/reconnect ladder) may legitimately stay at `w` rather than `e` — but if it stays at `w`/`d`, the throwable is still lost, since neither method takes one. Flag the throwable loss regardless of what level the fix lands at; level is a separate call for whoever fixes it (see AUDIT.md and the phase's own CONTEXT.md if one exists).

## Do NOT flag

- `logger.e(throwable) { "message" }` calls — that's the correct, complete shape (scrubbing and throwable preservation both handled by the wrapper).
- Static strings with no interpolation.
- Missing `Log.isLoggable` guards at call sites — the wrapper already gates every method internally; there is nothing for a caller to add.
- Anything already routed through `scrubMessageForLogs`/`scrubUrlForLogs`/`scrubPubkeyForLogs`/`scrubThrowableMessageForLogs`.
- Manual re-scrubbing of the throwable *inside* an `e()` lambda (e.g. `logger.e(e) { "Failed: ${scrubThrowableMessageForLogs(e)}" }`) isn't a privacy bug, but it is redundant — `e()` already scrubs and appends `e`'s message on its own.

## Search commands

```
# Check 1 — unscrubbed url/pubkey candidates in logger lambdas (manually verify each hit)
(logger|client\.logger)\.(d|w)\s*\{.*\$\{?(relayUrl|url|pubkey|npub)\b

# Check 2 — catch/onFailure blocks logging via d/w instead of e, losing the throwable
catch\s*\(\s*(e|t|throwable|cause)\s*:.*\{[^}]*(logger|client\.logger)\.(d|w)\s*\{[^}]*\$\{?\1\.message
```

## Related

- AUDIT.md — the authoritative logging rules this skill enforces; re-read it if a finding seems ambiguous rather than guessing.
- `docs/CONCERNS.md` (LOG-17, LOG-18) and `docs/TODO.md` — the specific known instances of Check 1/Check 2 failures already catalogued in this codebase, useful as ground truth for what a real hit looks like.
