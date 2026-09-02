---
name: find-non-lambda-logs
description: Use when auditing or reviewing Log.d/w/e calls in Umbra — checks three things, not one: (1) unscrubbed relay URL/pubkey/hex/nsec-shaped content reaching Log calls (AUDIT.md violation, the most important check here), (2) catch-block Log.w/e that interpolate ${e.message} but drop the throwable, (3) unguarded string-building on debug-level calls. Heavily adapted from a similar log-auditing skill built around a custom Log wrapper with a lambda overload; Umbra uses plain android.util.Log with no such overload, so the fix shapes are different (Log.isLoggable gating, not a lambda parameter).
---

# Auditing Log calls in Umbra

Umbra uses plain `android.util.Log`, not a custom lambda-taking wrapper — **there is no `Log.d("Tag") { "message" }` overload to migrate to here.** Don't apply a "convert to lambda" fix; that overload doesn't exist in this codebase. The three real checks, in priority order:

## Check 1 (most important): unscrubbed sensitive content

AUDIT.md: "Logs must be scrubbed of relay URLs, pubkeys, and profile/event content in release builds (`LogScrubber` helpers), gated behind `Log.isLoggable`." This is a privacy requirement specific to Umbra, not generic hygiene — a raw pubkey, relay URL, or npub/nsec-shaped string in a release-build log is exactly the kind of leak this whole client exists to prevent.

`app/src/main/java/com/umbra/app/util/LogScrubber.kt` (plain `object`) exposes:

```kotlin
fun scrubUrlForLogs(url: String?): String
fun scrubEndpointForLogs(host: String?, port: Int?): String
fun scrubPubkeyForLogs(pubkey: String?): String            // pubkey.take(8) + "..."
fun scrubThrowableMessageForLogs(throwable: Throwable): String
fun scrubMessageForLogs(message: String?): String          // regex-scrubs urls/nostrsigner:/host:port/npub/nsec/hex64
```

**What to flag:** a `Log.d/w/e/i` call whose interpolated string contains a relay URL, pubkey/npub/nsec-shaped hex, or raw exception message **not** passed through the matching `scrub*ForLogs` function first. Concretely:

```kotlin
// ❌ FLAG — raw url, no scrubbing
Log.d(TAG, "Connecting to $relayUrl")

// ✅ correct
Log.d(TAG, "Connecting to ${scrubUrlForLogs(relayUrl)}")

// ❌ FLAG — raw exception message, could contain a URL/pubkey embedded in it
Log.w(TAG, "Failed: ${e.message}")

// ✅ correct
Log.w(TAG, "Failed: ${scrubThrowableMessageForLogs(e)}", e)
```

Search: grep `Log\.(d|w|e|i)\(` for interpolated `$relayUrl`/`$url`/`$pubkey`/`$npub`/`.message` patterns that don't also contain `scrub` on the same line. As of this skill's writing, `LogScrubber` was already used in 24 files with no unscrubbed instances found in a sampled audit — treat a new unscrubbed hit as a real, not hypothetical, regression to flag, not noise.

## Check 2: throwable dropped in catch blocks

Same shape as the generic pattern: a `catch (e: ...)` block whose `Log.w`/`Log.e` interpolates `${e.message}` (raw or via `scrubThrowableMessageForLogs(e)`) into the message string but doesn't *also* pass `e` as the log call's third argument loses the actual stack trace.

```kotlin
// ❌ FLAG — message captured, stack trace lost
catch (e: Exception) { Log.w(TAG, "Anonymous login failed: ${scrubThrowableMessageForLogs(e)}") }

// ✅ correct — matches Umbra's established shape, e.g. LoginViewModel.kt:84
catch (e: Exception) {
    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Anonymous login failed: ${scrubThrowableMessageForLogs(e)}", e)
}
```

Note Umbra's real example passes `e` as the *third* argument to the real `Log.d(String, String, Throwable)` overload while *also* embedding a scrubbed summary in the message — both scrubbing (Check 1) and the throwable (Check 2) are satisfied simultaneously. That's the target shape for any new catch-block log, not just one or the other.

## Check 3: unguarded debug-level string building

Android's `Log.d`/`Log.v` calls still evaluate their string-template argument even when the log level is filtered out in a release build — there's no lambda overload to defer that cost the way a custom Log wrapper elsewhere might have. Umbra's established mitigation is gating behind `Log.isLoggable(TAG, Log.DEBUG)`:

```kotlin
// ✅ established shape (Bech32Encoder.kt, LoginViewModel.kt, others)
if (Log.isLoggable(TAG, Log.DEBUG)) {
    Log.d(TAG, "Parsed ${scrubPubkeyForLogs(pubkey)} in ${elapsedMs}ms")
}
```

**What to flag:** a `Log.d`/`Log.v` call doing non-trivial string interpolation (multiple `${...}` substitutions, function calls inside the template) with no `Log.isLoggable` guard around it. A single cheap interpolation (`Log.d(TAG, "Loaded $count items")`) is a judgment call, not an automatic flag — this check is about avoiding real per-call cost (scrub function calls, `toString()` on non-trivial objects) on a hot path, not blanket-wrapping every debug log.

## Do NOT flag

- Calls passing a `Throwable` as the real third argument alongside scrubbed content — that's the correct shape (see Check 2's "after" example).
- Static strings with no interpolation.
- `Log.d(TAG, "message")` calls with cheap, non-sensitive interpolation outside a hot path — Check 3 is about cost + scrubbing risk together, not a blanket lambda-style rule that doesn't apply to `android.util.Log` anyway.
- Anything already routed through `scrubMessageForLogs`/`scrubUrlForLogs`/`scrubPubkeyForLogs`/`scrubThrowableMessageForLogs`.

## Search commands

```
# Check 1 — unscrubbed url/pubkey candidates (manually verify each hit — grep can't tell scrubbed from not)
Log\.(d|w|e|i)\(.*\$\{?(relayUrl|url|pubkey|npub)\b

# Check 2 — catch-block message-only logs
Log\.(w|e)\([^)]*\$\{(e|t|throwable|cause)\.message\}[^)]*\)$   → then check no `, e)`/`, t)` third arg

# Check 3 — unguarded Log.d/Log.v with interpolation, not preceded by an isLoggable check in the same block
Log\.(d|v)\(.*\$\{
```

## Related

- AUDIT.md — the authoritative logging rules this skill enforces; re-read it if a finding seems ambiguous rather than guessing.
