# Phase 1: Error Visibility & Log Hygiene - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-02
**Phase:** 1-Error Visibility & Log Hygiene
**Areas discussed:** Log level calibration (LOG-17), User-facing exposure on wipe failures (LOG-20/26/27), LoginViewModel inner-catch fix shape (LOG-28), Verification strategy for logging-only fixes

---

## Log level calibration (LOG-17)

| Option | Description | Selected |
|--------|-------------|----------|
| Blanket ERROR for all 8 | Simplest, most consistent fix — every site was silently losing its throwable, so even the transient relay ones get error-level visibility with stack trace | ✓ |
| Split: ERROR for LoginViewModel/PublishEventUseCases, WARN for the 3 relay-transport sites | Level matches expected-vs-genuine failure nature, but `logger.w()` has no throwable parameter so the 3 relay sites would still lose the stack trace | |
| Let me decide per-site | Walk through each of the 8 sites individually | |

**User's choice:** Blanket ERROR for all 8
**Notes:** Grounded in an Explore-agent read of all 8 exact sites (`PublishEventUseCases.kt:42,69`, `LoginViewModel.kt:97,143,222`, `UmbraNostrClient.kt:371`, `RelayMessageHandling.kt:140`, `RelayWebSocketListener.kt:52`) — 5 sites can hide genuine bugs, 3 are on the relay-transport path where transient failures are expected and already feed the backoff/reconnect ladder. User chose consistency and full visibility over per-site noise reduction.

---

## User-facing exposure on wipe failures (LOG-20/26/27)

| Option | Description | Selected |
|--------|-------------|----------|
| Log-only, matching REQUIREMENTS.md wording | `logger.e(throwable) { }` at each swallowed catch site, no UI changes | ✓ |
| Also surface an aggregated user-facing warning | New UI-facing scope not in Phase 1's requirements, would need to be a deferred idea | |

**User's choice:** Log-only, matching REQUIREMENTS.md wording
**Notes:** REQUIREMENTS.md's BUG-04/09/10 wording already reads as "log the exception," not "surface to the user" — flagged this before asking so the choice was made with that context. The UI-surfacing alternative was captured as a deferred idea rather than dropped.

---

## LoginViewModel inner-catch fix shape (LOG-28)

| Option | Description | Selected |
|--------|-------------|----------|
| Log in place, keep swallowing | `logger.e(e) { "Session activation failed" }` inside the inner catch, continue exactly as today — login still succeeds even if activation fails | ✓ |
| Remove inner catch, let it propagate to outer catch | Login itself would now fail whenever `activateUserSession` throws, even though the pubkey was already saved — a real behavior change | |

**User's choice:** Log in place, keep swallowing
**Notes:** Read the actual `LoginViewModel.kt` code (`loginAnonymously()` and `savePublicKey()`) before asking — confirmed the inner catch is a deliberate design choice (activation failure is non-fatal to login), not an oversight. Removing it would have been a functional regression disguised as a logging fix. User confirmed the behavior-preserving option.

---

## Verification strategy for logging-only fixes

| Option | Description | Selected |
|--------|-------------|----------|
| Add a spy/fake UmbraLogger, assert e()/w() invocation | New test-double pattern; regression-proofs the exact bug class (silent downgrade from `e()` back to `d()`) that created LOG-17 in the first place | ✓ |
| Compile + lint + code review only | Faster, no new test infra, but no regression protection for this specific failure mode | |

**User's choice:** Add a spy/fake UmbraLogger, assert e()/w() invocation
**Notes:** Grepped the existing test suite first — confirmed no test currently asserts *what* a logger call was invoked with; all existing tests inject `NoOpUmbraLogger` purely to silence output. This is genuinely new test infrastructure for this codebase, not an established pattern being reused.

---

## Claude's Discretion

- Exact spy/fake `UmbraLogger` implementation shape (simple recording class vs. a mocking-library-based mock) — check `TESTING.md` and existing test dependencies first.
- Whether the 3 "expected/transient" LOG-17 sites need any log-spam throttling — only decided the level (ERROR), not whether repeated failures need rate-limiting. Don't add throttling speculatively.

## Deferred Ideas

- Aggregated user-facing warning on logout/wipe failure ("Logout completed with warnings") — out of Phase 1 scope, noted as a possible future backlog item.
- Log-spam throttling for the 3 transient-failure LOG-17 sites, if it turns out to be a real problem in practice.

## Side task completed mid-discussion

While researching LOG-17's exact call sites, confirmed `.claude/skills/find-non-lambda-logs/SKILL.md` was stale — it described Umbra as using plain `android.util.Log` with no lambda overload, when the codebase has since migrated to a lambda-based `UmbraLog.tag(TAG)` wrapper with internal `Log.isLoggable` gating. User asked to fix it in the moment; rewrote the skill to match the actual wrapper API and committed it separately (`docs(skills): fix find-non-lambda-logs for the current UmbraLog wrapper`, outside this phase's CONTEXT.md scope).
