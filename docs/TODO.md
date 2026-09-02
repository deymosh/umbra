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

### LOG-17 — Publish failure logs drop the throwable and emit at debug level
- **Status:** backlog
- **Added:** 2026-08-24
- **Why:** Found across three separate logging-migration plans and deliberately not fixed in any of them, to keep each migration a behaviour-preserving 1:1 translation with zero regressions. Folded into this single entry at the migration closeout instead of filed as three duplicates, since all eight sites below share the same root cause and the same fix shape.

- Eight sites share this shape:

- `domain/usecase/PublishEventUseCases.kt` — `PublishSignedEventUseCase`/`PublishAuthEventUseCase`'s two `.onFailure` handlers
- `ui/auth/LoginViewModel.kt:97,143,222` — anonymous login failure, save-public-key failure, logout failure
- `data/nostr/UmbraNostrClient.kt`'s `logWebSocketFailure` non-SOCKS branch, `data/nostr/RelayMessageHandling.kt`'s `onWebSocketMessage` catch block, `data/nostr/RelayWebSocketListener.kt`'s incoming-drain `onFailure` handler

All eight are debug-level, so release builds already filter them — the stack-trace loss is invisible in release regardless. The fix for each is a deliberate, individually-reviewable promotion to `UmbraLogger`'s three-argument exception overload (`logger.e(throwable) { ... }`), which attaches the throwable and auto-scrubs its message — not something to fold into a migration diff, since a level promotion (DEBUG to ERROR) is itself a real behaviour change whose release-log-visibility impact should be weighed per site.
