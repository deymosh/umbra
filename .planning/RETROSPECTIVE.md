# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v0.1.0 — Hardening & First Public Release

**Shipped:** 2026-09-06
**Phases:** 4 | **Plans:** 19 | **Sessions:** ~5 (roughly one per phase, 2026-09-02 → 2026-09-06)

### What Was Built
- Every swallowed or debug-only exception (logout, relay disconnects, session activation, wipe paths) promoted to scrubbed, error-level logging with the throwable attached, closing a `Logger.e()` scrub gap found mid-phase (Phase 1)
- Concurrency correctness across relay config, session job scheduling, and event ingestion — atomic/CAS-based state and per-relay locking replacing plain check-then-act fields, each closed race pinned by a genuinely multi-threaded test (Phase 2)
- State correctness: cross-author deletions actually apply to the cache, optimistic UI never claims a write succeeded before Amber confirms it, and failed writes surface instead of vanishing (Phase 2)
- All 38 previously-unvalidated "fix applied" bug-tracker entries individually adjudicated — test-cited, source-read-cited, or explicitly left open with a named blocker — including two rejected false test citations rather than papering over them (Phase 3)
- One true app version (`BuildConfig.VERSION_NAME`), a real dated `CHANGELOG.md`, an actually-executed release-readiness checklist (930 tests, lint, R8-shaped `assembleRelease` all green), and a reusable `umbra-release` runbook skill with a structurally-enforced stop-before-push gate (Phase 4)

### What Worked
- Horizontal layering (visibility → correctness → validation → release) instead of vertical slices matched this being hardening work on a mature codebase, not feature work — Phase 1's error-visibility fixes meant Phase 2's concurrency bugs failed loudly instead of silently, and Phase 3's validation pass had a stable, already-fixed tree to audit rather than a moving target.
- Requiring genuinely concurrent (real-thread, not just coroutine-scheduler) tests for every Phase 2 race fix paid off directly: the 200-iteration `NostrSessionManager` test caught a live `compareAndSet`-ordering race in the plan's own literal wording before it shipped, not after.
- Phase 3's decision to treat "verified by direct source read, cited by file:line" as a third legitimate closing disposition (alongside test-evidence and needs-eyeball) avoided forcing 6 genuinely untestable-behind-a-non-injected-logger fixes into a bucket that would have misrepresented either the evidence or the blocker.
- Standing "never tag/release without per-moment authorization" project policy did exactly its job twice in one milestone: once mid-Phase-4 (executor paused before creating the local tag) and again at milestone close (this workflow's own tag step was skipped on request) — a policy that survives being asked twice, in two different contexts, without drifting.

### What Was Inefficient
- Phase 2 needed a 3-iteration code-review/auto-fix loop (3 critical → 0/4 warning → 0/2 warning) before landing clean — the concurrency fixes were subtle enough that review found real, narrowing-severity issues each pass rather than the loop being wasted motion, but it's still 3x the review cost of a phase that lands clean on the first pass.
- Phase 2's verification needed a second pass (`previous_status: gaps_found` before landing `passed`) — the initial verification run didn't independently re-run tests/diffs against live source before scoring, and the re-verification that followed did exactly that and found no actual regressions, just an under-verified first attempt.
- `phase.complete`'s automated requirement-marking assumes every requirement ID a completed phase declares is satisfied — it silently flipped `REL-03` (the local tag) to `[x]` even though that specific piece of scope had been explicitly and deliberately descoped minutes earlier in the same session. Caught and corrected before milestone close, but worth knowing: a phase-level requirements auto-mark doesn't know about a mid-phase scope reduction and will need a manual correction pass whenever one happens.

### Patterns Established
- Per-relay/per-resource `Mutex` plus a fresh authoritative read (not a stale cached value) is this codebase's now-established fix shape for a CRUD lost-update race — used for both `RelayCrudCoordinator.updateRelayRole` and the DM-role dirty-flag fix.
- `AtomicReference<Job?>` plus small non-blocking scheduling helpers (`launchIfIdle`/`launchReplacing`) is the established replacement for a plain `var Job?` field wherever start/cancel can race — applied across `EventIngestCache`, `NostrSessionManager`, with the remaining plain fields deliberately left alone and documented with their own start()/stop()-serialization invariant rather than converted reflexively.
- A release-readiness checklist should record only what was actually run and observed *this session* — never transcribed from an earlier research pass — enforced here via a `backstop`-verified truth precisely because no mechanical check can otherwise distinguish a real result from a convincingly-written fake one.
- Tag/release authorization is per-moment, not per-plan-approval: a plan or milestone-completion workflow that would create or push a release tag must still stop and ask at the exact moment it's about to do it, even if the plan itself was already approved.

### Key Lessons
1. When a workflow step (like `phase.complete`) bulk-marks all of a phase's declared requirement IDs, re-check any requirement that was deliberately descoped mid-execution — automation doesn't know about a scope change that happened in conversation, only about what's declared in frontmatter.
2. A verification pass that doesn't independently re-run tests/diffs against live source (relying instead on SUMMARY claims) is worth catching before it's treated as ground truth — Phase 2's gaps-found-then-passed cycle is the concrete example.
3. Genuinely concurrent tests (real threads, not just coroutine test dispatchers) are worth the extra setup cost specifically for fixes to racy state — they catch ordering bugs that a single-threaded or virtual-time test cannot.
4. A hard, structural line between "prepare a release" and "publish a release" (separate plan scope, separate explicit confirmation, a runbook whose push step is gated by line-order not just prose) is what makes an irreversible action actually stay irreversible-by-accident-proof rather than just irreversible-in-theory.

### Cost Observations
- Model mix: 100% Sonnet across all four phases (no Opus/Haiku delegation used this milestone)
- Sessions: ~5 (approximate — one per phase plus this milestone-close session; not tracked precisely per-session)
- Notable: Phase 3's scope expansion (10 → 38 bug-tracker entries) was absorbed within the same phase rather than requiring a separate phase, because the audit methodology was identical — only the entry count changed.

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v0.1.0 | ~5 | 4 | First milestone — horizontal (visibility → correctness → validation → release) layering established as the default shape for hardening-on-mature-codebase work; per-moment tag/release authorization policy established and held under two separate asks. |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v0.1.0 | 930 (0 failures) | Not measured as a percentage this milestone | 0 (no new dependencies added — hardening/release work only) |

### Top Lessons (Verified Across Milestones)

1. Genuinely concurrent (real-thread) tests catch ordering races that scheduler-based tests miss — established this milestone, not yet cross-validated by a second one.
2. Per-moment authorization for irreversible actions (tag/release) survives repeated, differently-framed requests to skip it — established this milestone, not yet cross-validated by a second one.
