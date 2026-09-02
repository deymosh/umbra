# Phase 1: Error Visibility & Log Hygiene - Context

**Gathered:** 2026-09-02
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase promotes 6 requirements' worth of swallowed or debug-level exception handling to properly scrubbed, error-level logging, so failures on privacy-critical and session-critical paths leave a diagnostic trail instead of vanishing silently:

- **BUG-01** (LOG-17): 8 sites across `PublishEventUseCases.kt`, `LoginViewModel.kt`, `UmbraNostrClient.kt`, `RelayMessageHandling.kt`, `RelayWebSocketListener.kt` lost throwable attachment during a prior logging migration (`Log.d(TAG, message, e)` → `logger.d { message }`, which has no throwable parameter).
- **BUG-02** (LOG-18): 3 sites in `EventRepositoryImpl.kt`/`NegentropySyncOrchestrator.kt` interpolate unscrubbed relay URLs / throwable messages.
- **BUG-04** (LOG-20): `EventRepositoryImpl.clearAllData()`'s `disconnectFromAll()` catch block swallows silently.
- **BUG-09** (LOG-26): `SettingsScreen.kt`'s independent logout entry point has the same swallowed-exception bug LOG-25 already fixed for `FeedScreen.kt`.
- **BUG-10** (LOG-27): `LogoutUseCase`/`TrimMemoryCachesUseCase` swallow every per-step cleanup exception with zero logging.
- **BUG-11** (LOG-28): `LoginViewModel`'s inner `try/catch` around `activateUserSession` swallows the exception before the outer, already-working logging path can see it.

This is logging visibility work only — no new behavior, no UI changes beyond what's explicitly decided below, no concurrency/race fixes (those are Phase 2).

</domain>

<decisions>
## Implementation Decisions

### Log level (LOG-17's 8 sites)
- **D-01:** All 8 LOG-17 sites are promoted to `logger.e(throwable) { "..." }` (blanket ERROR), not split by whether the underlying failure is expected/transient (e.g. relay disconnects) vs. a genuine bug (e.g. local prefs/session-state setup failures). Every site was silently losing its throwable; error-level visibility with the stack trace preserved takes priority over noise reduction on the transient-failure sites. Do not manually re-scrub the throwable's message inside the `e()` lambda — `logger.e()` already calls `LogScrubber.scrubThrowableMessageForLogs()` internally and appends it to the caller's message; only pass a short static description string as the lambda body.
- Sites: `PublishEventUseCases.kt:42,69`, `LoginViewModel.kt:97,143,222`, `UmbraNostrClient.kt:371` (`logWebSocketFailure`), `RelayMessageHandling.kt:140`, `RelayWebSocketListener.kt:52`.

### Wipe-path failure exposure (LOG-20/26/27)
- **D-02:** LOG-20, LOG-26, and LOG-27 stay **log-only** — `logger.e(throwable) { "..." }` at each currently-silent catch site (`clearAllData()`'s `disconnectFromAll()` catch, `SettingsScreen.kt`'s logout `catch`, each of `LogoutUseCase`'s 7 and `TrimMemoryCachesUseCase`'s 5 per-step cleanup catches). No user-facing UI change (no aggregated "logout completed with warnings" message or similar). This matches REQUIREMENTS.md's BUG-04/09/10 wording, which already describes these as "log the exception," not "surface to the user." — **Reversibility:** reversible — adding a user-facing warning later is additive, doesn't require undoing the logging change.
- Surfacing an aggregated user-facing warning on wipe-path failure was raised as an alternative but explicitly deferred (see `<deferred>` below) — it's new UI-facing scope not in Phase 1's 6 requirements.

### LoginViewModel inner-catch fix shape (LOG-28)
- **D-03:** Keep the inner `try/catch` around `eventRepository.activateUserSession(...)` in both `loginAnonymously()` and `savePublicKey()` — do **not** remove it or let the exception propagate to the outer catch. Add `logger.e(e) { "Session activation failed" }` inside the inner catch instead of `catch (_: Exception) { }`. — **Reversibility:** one-way if reversed carelessly — removing the inner catch instead would change login's success/failure semantics (a user whose pubkey was already saved would now see "login failed" instead of logging in with a possibly-empty feed); this decision preserves current behavior exactly, only adding visibility. Do not let a future change collapse the two catch layers without re-confirming this is still desired.
- Root cause context: the inner catch exists so that `activateUserSession` (backfill/hydration trigger) failing does not block login itself — `nostrSessionController.start()` and `isAuthenticated = true` still run afterward. This is intentional, not an oversight; only the missing log call is the bug.
- Sites: `LoginViewModel.kt:81-83` (`loginAnonymously`), `LoginViewModel.kt:128-130` (`savePublicKey`).

### Verification strategy for logging-only fixes
- **D-04:** Introduce a test-double `UmbraLogger` (spy/fake) that records invocations (which method — `d`/`w`/`e` — and whether a throwable was passed), and use it in place of `NoOpUmbraLogger` in tests covering the classes touched by this phase's fixes. Assert that each fixed catch site now calls `e()` (or `w()` for sites that land there) instead of the previous `d()`. This is a new test pattern — no existing test currently asserts *what* a logger call was invoked with; today's tests only inject `NoOpUmbraLogger` to silence output. — **Reversibility:** reversible — the spy is additive test infrastructure, doesn't change production code shape.
- Rationale: LOG-17 itself was a silent regression from an earlier migration that nobody caught via tests. A spy that fails if `e()` regresses back to `d()`/is removed gives this exact bug class regression protection, which plain compile+lint+manual review does not.
- Compile (`compileDebugKotlin`), lint (`lintDebug`), and full unit test suite (`testDebugUnitTest`) still gate every task per CLAUDE.md's normal workflow — the spy logger is additive to that, not a replacement for it.

### Claude's Discretion
- Exact spy/fake `UmbraLogger` implementation shape (e.g. a simple recording class vs. a MockK-based mock) is left to the planner/researcher — codebase currently has no MockK/Mockito usage pattern to confirm against; check `TESTING.md` and existing test dependencies before choosing.
- Whether the 3 "expected/transient" LOG-17 sites (`UmbraNostrClient.kt`, `RelayMessageHandling.kt`, `RelayWebSocketListener.kt`) get any accompanying rate-limiting/log-spam consideration is left to planner discretion — D-01 only decided the level, not whether repeated errors need throttling. Do not add throttling infrastructure speculatively; only address it if it turns out to be a real problem during implementation.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Bug catalog and fix approaches (primary source — read first)
- `docs/CONCERNS.md` — Tech Debt (LOG-17, LOG-18) and Known Bugs (LOG-20, LOG-26, LOG-27, LOG-28) sections have detailed root-cause analysis and fix approaches for every bug this phase addresses. Treat this as more precise than the one-line descriptions in KNOWN_ISSUES.md/TODO.md.
- `docs/KNOWN_ISSUES.md` — canonical open-bug entries (LOG-18, LOG-20, LOG-26, LOG-27, LOG-28) with `**Status:**`/`**Found:**`/`**Where:**` fields; update these to `fix applied — needs on-device validation` as each fix lands, per CLAUDE.md's Bug tracking section.
- `docs/TODO.md` — LOG-17's entry (backlog item, not KNOWN_ISSUES, since it's a tech-debt promotion rather than a user-visible bug); move to DONE.md once shipped.
- `.planning/REQUIREMENTS.md` — BUG-01, BUG-02, BUG-04, BUG-09, BUG-10, BUG-11 definitions; source of the "log-only, not UI-surfaced" wording that grounds D-02.

### Logging conventions
- `.claude/skills/find-non-lambda-logs/SKILL.md` — just corrected during this discussion to match the actual `UmbraLog.tag(TAG)` lambda-based wrapper (previously described a stale plain-`Log` pattern). Use this skill when auditing/reviewing the fixes this phase produces.
- `AUDIT.md` §1.3 (or equivalent logging section) — authoritative scrubbing rules; re-read if a specific site's scrubbing need is ambiguous.
- `app/src/main/java/com/umbra/app/util/logging/Logger.kt`, `UmbraLog.kt`, `domain/logging/UmbraLogger.kt` — the actual wrapper implementation (3 methods: `d(() -> String)`, `w(() -> String)`, `e(Throwable, () -> String)`; internal `Log.isLoggable` gating; `e()` auto-scrubs via `LogScrubber.scrubThrowableMessageForLogs`).
- `app/src/main/java/com/umbra/app/util/LogScrubber.kt` — `scrubUrlForLogs`, `scrubEndpointForLogs`, `scrubPubkeyForLogs`, `scrubThrowableMessageForLogs`, `scrubMessageForLogs`.

### Testing conventions
- `.planning/codebase/TESTING.md` — check for existing mocking/test-double conventions before choosing the spy `UmbraLogger`'s implementation shape (D-04).
- `app/src/test/java/com/umbra/app/util/logging/LoggerTest.kt` — existing tests for the wrapper itself (not the call sites); shows current usage of `NoOpUmbraLogger` as the baseline pattern being extended by D-04.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `NoOpUmbraLogger` (`domain/logging/UmbraLogger.kt`) — already used across many `*Test.kt` files (e.g. `ComposerViewModelTest.kt`, `InteractionActionsCoordinatorTest.kt`, `ProfileNip05VerificationStateTest.kt`, `RelayIssueBannerCoordinatorTest.kt`) as the default test double. D-04's spy logger should follow the same injection pattern — same constructor slot, different implementation — not a new DI wiring approach.
- `LogScrubber` (`util/LogScrubber.kt`) — every scrubbing need in this phase (LOG-18's unscrubbed sites, any manual message text) routes through this existing object; no new scrubbing logic needed.

### Established Patterns
- Every class obtains its logger via `private val logger = UmbraLog.tag(TAG)` with `private const val TAG = "UmbraXxx"` declared alongside it — this pattern is already correct and unaffected by this phase's fixes; only the *call sites* (which method: `d`/`w`/`e`) change.
- `logger.e(throwable) { "..." }` is the only method taking a throwable — this constrains every LOG-17/20/26/27/28 fix to funnel through this exact call shape once the level decision (D-01) is applied.

### Integration Points
- `LoginViewModel.kt`'s `loginAnonymously()`/`savePublicKey()` both call `eventRepository.activateUserSession(...)` inside the same inner-catch shape (D-03 applies identically to both call sites).
- `LogoutUseCase`/`TrimMemoryCachesUseCase` each wrap multiple independent cleanup steps in their own `catch (_: Exception) { }` — D-02's fix is per-step, not a single wrapping change; each of the 7 + 5 steps needs its own `logger.e(throwable) { }` call.

</code_context>

<specifics>
## Specific Ideas

No specific UI/visual references — this phase is logging-only. The one concrete implementation detail locked in discussion: don't manually re-scrub inside `logger.e()` lambdas since the wrapper already does it (see D-01).

</specifics>

<deferred>
## Deferred Ideas

- **Aggregated user-facing warning on logout/wipe failure** — raised while discussing LOG-20/26/27 (D-02). Would show something like "Logout completed with warnings" if any per-step cleanup fails. Explicitly out of Phase 1's scope (REQUIREMENTS.md describes these as log-only); worth considering as a future backlog item if on-device use ever surfaces a real case where silent-but-logged wipe failures cause user confusion.
- **Rate-limiting/throttling for the 3 "expected/transient" LOG-17 sites** (relay disconnects, malformed relay frames) — not decided either way; left to planner/implementer discretion per Claude's Discretion above, not a deferred phase-boundary item, but noting it here in case ERROR-level logging on these sites turns out to be noisy in practice.

### Reviewed Todos (not folded)
None — `todo.match-phase` returned zero matches for Phase 1.

</deferred>

---

*Phase: 1-Error Visibility & Log Hygiene*
*Context gathered: 2026-09-02*
