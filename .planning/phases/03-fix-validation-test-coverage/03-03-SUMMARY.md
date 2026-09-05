---
phase: 03-fix-validation-test-coverage
plan: 03
subsystem: testing
tags: [bug-tracker, documentation, logging, concurrency, room, dao, compose]

# Dependency graph
requires:
  - phase: 03-fix-validation-test-coverage
    provides: "Plan 03-01's VALID-11..VALID-38 requirement IDs and DONE.md move template"
provides:
  - "03-DISPOSITIONS.md — the durable, source-quoted rationale for the 16 fix-applied entries this phase cannot close with a unit test"
affects: [03-fix-validation-test-coverage plan 03-07 (tracker updates), NostrSessionManager test-seam follow-up work]

actuals:
  tokens: 6100
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Source-read-verified disposition (D-09): a quoted logger-field declaration plus quoted fixed call site(s) stand in for a test citation when a class builds its logger internally (private val logger = UmbraLog.tag(TAG)) rather than taking one as a constructor parameter"
    - "Declaration-level architectural-blocker verification: quote the blocked class's full constructor, quote each non-interface dependency's own declaration, and cross-check the shared fakes directory listing, rather than asserting the blocker from research alone"

key-files:
  created:
    - .planning/phases/03-fix-validation-test-coverage/03-DISPOSITIONS.md
  modified: []

key-decisions:
  - "LOG-4's evidence resolved the class's own two fixes independently: the NostrSessionManager half inherits the Context-requiring TorRuntimeManager/BackfillAnchorStore blocker; the UserRepositoryImpl half is blocked too, since UserRepositoryImpl's own constructor requires ImagePrefetcher, which itself needs a live Android Context and a Coil ImageLoader with no fake anywhere in testutil/fakes/. A repo-wide grep for any JVM test constructing android.content.Context returned zero files, closing the idempotency-of-judgment edge case the plan's must_haves flagged — the evidence came out BLOCKED, not TESTABLE, so no unplanned-gap flag is needed."
  - "The NostrSessionManager blocker claim was re-derived from source rather than trusted from 03-RESEARCH.md: of its 11 constructor parameters, 4 are concrete classes (not the 2 research named) — TorRuntimeManager, BackfillAnchorStore, BootstrapOwnProfileUseCase, RelayListDecryptionCoordinator — but only TorRuntimeManager and BackfillAnchorStore require a live Android Context, which is the actual irreducible reason no NostrSessionManagerTest is possible without Robolectric; the other two concrete classes have their own interface-only dependencies and are not independently blocking."
  - "LOG-51 recorded once, in Section 1 (source-read verified), not duplicated into Section 2's blocked group — per the plan's ordering-edge rule, an entry that qualifies for two groups gets the disposition its own decision (D-09) assigned it."

requirements-completed: [VALID-02, VALID-03, VALID-04, VALID-05, VALID-09, VALID-11, VALID-13, VALID-18, VALID-20, VALID-22, VALID-26, VALID-27, VALID-33, VALID-34, VALID-35, VALID-37]

coverage:
  - id: D1
    description: "Six non-injected-logger fixes (LOG-18, 20, 28, 39, 51, 54) get quoted source-read verification (logger field + fixed call site) and ready-to-paste DONE.md rationale text"
    requirement: "VALID-11, VALID-13, VALID-20, VALID-27, VALID-34, VALID-37"
    verification:
      - kind: other
        ref: "grep -q 'LOG-{18,20,28,39,51,54}' .planning/phases/03-fix-validation-test-coverage/03-DISPOSITIONS.md && git status --porcelain app/src (empty)"
        status: pass
    human_judgment: false
  - id: D2
    description: "NostrSessionManager blocker re-derived from source (constructor + 4 non-interface deps + fakes-directory cross-check); LOG-30/38/49/52 recorded with one shared blocker note staying in KNOWN_ISSUES.md; LOG-4 verified separately and recorded BLOCKED with its own tracker rationale"
    requirement: "VALID-04, VALID-22, VALID-26, VALID-33, VALID-35"
    verification:
      - kind: other
        ref: "grep -q 'Blocked by an untestable class' .planning/phases/03-fix-validation-test-coverage/03-DISPOSITIONS.md && grep -q 'LOG-{4,30,38,49,52}' ... && git status --porcelain app/src docs (empty)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Five device-validation entries (LOG-2, 3, 6, 13, 26) recorded with precise per-entry reasons and restatement text; ledger closes with total count and explicit no-emulator-run statement"
    requirement: "VALID-02, VALID-03, VALID-05, VALID-09, VALID-18"
    verification:
      - kind: other
        ref: "grep -q 'LOG-{2,3,6,13,26}' ... && grep -q 'no emulator or device run' .planning/phases/03-fix-validation-test-coverage/03-DISPOSITIONS.md && git status --porcelain app/src docs (empty)"
        status: pass
    human_judgment: false

duration: ~35min
completed: 2026-09-05
status: complete
---

# Phase 3 Plan 3: Non-Test-Closable Dispositions Summary

**Produced `03-DISPOSITIONS.md`, the source-quoted disposition ledger for the 16 fix-applied bug-tracker entries that structurally cannot carry a test citation — six behind non-injected loggers, five behind NostrSessionManager's Context-requiring dependencies (LOG-4 verified as sharing that same blocker for its UserRepositoryImpl half), and five needing the user's own device pass.**

## Performance

- **Duration:** ~35 min
- **Tasks:** 3/3 completed
- **Files modified:** 1 (created)

## Accomplishments
- Verified all six Group D logging fixes (LOG-18, 20, 28, 39, 51, 54) against current source: every one already routes its call site through `LogScrubber` and logs at the correct level, and every one sits behind a class-internal `UmbraLog.tag(TAG)` logger with no constructor-injection seam — quoted both the field declaration and the call site for each.
- Re-derived the `NostrSessionManager` architectural blocker from its actual constructor rather than trusting research: found 4 concrete (non-interface) dependencies, not the 2 research named, but confirmed the irreducible blocker is specifically `TorRuntimeManager` and `BackfillAnchorStore`, both requiring a live `android.content.Context` with no fake anywhere in `testutil/fakes/` and no Robolectric/mocking dependency in the build.
- Recorded one shared blocker-note sentence for LOG-30/38/49/52 (all staying in `docs/KNOWN_ISSUES.md`, not `docs/TODO.md`, per D-10) and verified LOG-4's blocker independently, discovering its second fix (`UserRepositoryImpl.saveRelayList()`) is blocked for a distinct reason: `UserRepositoryImpl` itself requires `ImagePrefetcher`, which needs both a Context and a Coil `ImageLoader`.
- Confirmed no JVM test anywhere in the repository constructs an Android `Context` (`grep -rl "android.content.Context" app/src/test/` returned zero files) — closing the plan's idempotency-of-judgment edge case with a definitive BLOCKED disposition for LOG-4, not a TESTABLE reclassification.
- Recorded the five device-pass entries (LOG-2, 3, 6, 13, 26) with a precise per-entry reason and a ready-to-paste restatement sentence each — including LOG-3's existing bonus-coverage citation and LOG-6's executed dependency-absence check confirming the fake DAO stub never runs real SQL.
- Closed the ledger at exactly 16 entries across three sections with an explicit "no emulator or device run was performed" statement.

## Task Commits

Each task was committed atomically:

1. **Task 1: Source-read verification for the six non-injected-logger fixes** - `a9be1fe` (docs)
2. **Task 2: Verify and record the two architectural blockers** - `b810c4d` (docs)
3. **Task 3: Record the five device-validation entries** - `9160020` (docs)

**Plan metadata:** committed separately after this summary (see final commit).

_Note: this is a documentation-only plan — no `feat`/`fix`/`test` commits, all three task commits are `docs`, and no `app/src` or `docs/` files were touched at any point (verified per-task via `git status --porcelain`)._

## Files Created/Modified
- `.planning/phases/03-fix-validation-test-coverage/03-DISPOSITIONS.md` - New phase artifact: 16-row disposition ledger with quoted source evidence, one shared architectural-blocker note, and ready-to-paste tracker rationale text for Plan 03-07 to consume.

## Decisions Made
- LOG-4's disposition came out BLOCKED (not TESTABLE) on the strength of a repo-wide `android.content.Context` construction grep returning zero matches — recorded per the plan's own idempotency-of-judgment edge case, so no unplanned-gap flag was needed in this summary.
- Recorded 4 non-interface `NostrSessionManager` dependencies (not the 2 research named) but isolated the actual irreducible blocker to the 2 that require a live Context (`TorRuntimeManager`, `BackfillAnchorStore`) — the other 2 (`BootstrapOwnProfileUseCase`, `RelayListDecryptionCoordinator`) are concrete classes with only interface-typed dependencies of their own, so they contribute to "no fake exists today" but are not independently what blocks construction.
- LOG-51 recorded exactly once (Section 1, source-read verified) despite also touching the `NostrSessionManager` blocker class, per the plan's explicit ordering-edge instruction.

## Deviations from Plan

None - plan executed exactly as written. All `must_haves.truths` and `prohibitions` were honored: no `app/src` or `docs/` file was touched at any point (verified after each task), every source-read row quotes verbatim current text alongside its `path:line`, no entry was relocated to `docs/TODO.md`, and no emulator/instrumented test was run or attempted.

## Issues Encountered
None. All source citations resolved cleanly against current `app/src/main` files; no test infrastructure gaps were discovered beyond what 03-RESEARCH.md had already flagged (confirmed, not contradicted, by this plan's independent re-verification).

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
`03-DISPOSITIONS.md` is ready for Plan 03-07 to consume: Section 1's six rows supply the exact rationale text for their `docs/DONE.md` moves; Sections 2 and 3's eleven rows supply the exact restatement text for their `docs/KNOWN_ISSUES.md` entries (Section 2's four LOG-30/38/49/52 rows share one identical sentence; LOG-4 and each Section 3 entry has its own). No blockers for downstream plans — this plan's own architectural-blocker findings (NostrSessionManager's Context-requiring dependencies) are consistent with, and reinforce, the existing LOG-44 deferral already tracked in `docs/TODO.md`.

---
*Phase: 03-fix-validation-test-coverage*
*Completed: 2026-09-05*
