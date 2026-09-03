# TODO

General project backlog — suggested/planned tasks (features, refactors, roadmap items), distinct
from open bugs (tracked in [KNOWN_ISSUES.md](KNOWN_ISSUES.md)) and completed work (logged in
[DONE.md](DONE.md)). See [`.claude/CLAUDE.md`](../.claude/CLAUDE.md)'s "Bug tracking" section for
the full convention — locally sequential numbers shared across all three files, independent of
GitHub issue numbers.

NIP-specific implementation sequencing lives in
[nip-priority-roadmap.md](nip-priority-roadmap.md) — this file is the general project backlog
(features, refactors, non-NIP roadmap items), not a place to duplicate that roadmap's entries.

Entry format:
```
### LOG-<n> — <short title>
- **Status:** backlog | in progress | not applicable
- **Added:** <YYYY-MM-DD>
- **Why:** <1-2 line rationale — why this is worth doing / where it came from>

<description — what the task/feature/refactor actually is>
```

An item marked `not applicable` stays here (not deleted) with a note explaining why it was
triaged out, so the reasoning isn't lost. Once an item ships, it moves verbatim to
[DONE.md](DONE.md) with a `**Completed:**` date appended and a `**From:** TODO LOG-<n>` back-reference.

### LOG-36 — SettingsScreen's logout catch block is unreachable dead code
- **Status:** backlog
- **Added:** 2026-09-03
- **Why:** Found by code review of Phase 1 (Error Visibility & Log Hygiene) — harmless (no leak,
  no crash) but worth cleaning up or documenting so it isn't mistaken for a load-bearing fix.

LOG-26's fix wraps `loginViewModel.logout()` in `SettingsScreen.kt` with a new
`try { ... } catch (e: Exception) { settingsScreenLogger.e(e) { "Logout failed" } }`. But
`LoginViewModel.logout()` (`ui/auth/LoginViewModel.kt:225-234`) already wraps its own body in
`try { logoutUseCase() } catch (e: Exception) { logger.e(e) { "Logout failed" } } finally { ... }`
— it catches `Exception`, never rethrows, and the `finally` always runs. `logout()` is a normal
(non-throwing-by-contract) suspend function, and the only other statement inside `SettingsScreen`'s
new `try` is a plain state assignment that cannot throw — so the new `catch` added by LOG-26's fix
can never execute; the real visibility improvement already happened one layer down in
`LoginViewModel.logout()` itself. Fix: either remove the now-redundant `try/catch` in
`SettingsScreen.kt` (since `logout()` never throws), or, if defense-in-depth against a future
change to `logout()`'s contract is the intent, say so in a comment.

### LOG-32 — LogoutUseCase's outer catch and unwrapped final cleanup call are still silent
- **Status:** backlog
- **Added:** 2026-09-02
- **Why:** Found while fixing LOG-27's seven per-step cleanup catches — a real, adjacent gap of the
  same class, but deliberately outside the seven-site scope that fix was locked to.

`LogoutUseCase.invoke()`'s outer method-wide `catch (_: Exception) { }` still discards its
exception with zero logging, and the final `userPreferences.clearAll()` call inside that outer
try block is the one cleanup step with no per-step handler of its own — so a failure there falls
straight into the silent outer catch instead of getting the same `logger.e(e) { }` treatment the
other seven steps now have. Fix: either wrap `userPreferences.clearAll()` in its own per-step catch
matching the other seven, or log the outer catch directly; either closes the gap.

### LOG-33 — NegentropySyncOrchestrator's sync-aborted debug log interpolates a raw relay-supplied reason string
- **Status:** backlog
- **Added:** 2026-09-02
- **Why:** Found while fixing LOG-18's three unscrubbed log sites — a real scrubbing gap of the
  same class, but not one of the three sites LOG-18's fix was scoped to.

`NegentropySyncOrchestrator`'s sync-aborted debug log interpolates a relay-supplied reason string
directly into the log message, without routing it through the message-scrubbing helper
(`LogScrubber.scrubMessageForLogs`) that this file's other relay-sourced values already use. Since
the reason string originates from the remote relay, it could embed a relay URL, pubkey, or other
content that should be scrubbed before it reaches a release-build log. Fix: wrap the reason string
in `LogScrubber.scrubMessageForLogs()` before interpolating it, matching the pattern already used
elsewhere in this file.
