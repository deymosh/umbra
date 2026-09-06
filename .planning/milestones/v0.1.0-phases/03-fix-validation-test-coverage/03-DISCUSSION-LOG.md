# Phase 3: Fix Validation & Test Coverage - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-04
**Phase:** 3-Fix Validation & Test Coverage
**Areas discussed:** Visual/timing bugs (LOG-2, 3, 13), Concurrency-bug test rigor (LOG-4, 12), Reuse existing tests vs. write new, Scope expansion to all 38 fix-applied entries

---

## Visual/timing bugs (LOG-2, LOG-3, LOG-13)

| Option | Description | Selected |
|--------|-------------|----------|
| Partial logic tests + stay open | Write a narrow unit test for whatever underlying logic IS pure/testable, but still classify all three as needing human eyeball in KNOWN_ISSUES.md — the test is bonus coverage, not proof of fix. | ✓ |
| Pure human-eyeball, no test attempt | Skip writing any test for these three entirely; classify as needing on-device validation and move on. | |
| Case-by-case | Decide per-bug during the discussion rather than one blanket rule. | |

**User's choice:** Partial logic tests + stay open
**Notes:** No Compose UI test / Robolectric infra exists in the project, so the rendered outcome itself can never be unit-tested for these three. `ImageLoadGateTest.kt` already exists and covers the gate's own logic, but LOG-2's actual root cause was in Compose `LaunchedEffect` composition wiring, not the gate — flagged for research to assess.

---

## Concurrency-bug test rigor (LOG-4, LOG-12)

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, same rigor as Phase 2 | Force real overlapping coroutines (TestDispatcher/runTest) to prove the race is actually closed, matching Phase 2's D-07 precedent. | ✓ |
| Simpler behavioral test is enough | A sequential test asserting correct final state is lower effort and still satisfies "would have failed pre-fix." | |
| Case-by-case | Decide per-bug. | |

**User's choice:** Yes, same rigor as Phase 2 (recommended)
**Notes:** None additional.

---

## Reuse existing tests vs. write new

| Option | Description | Selected |
|--------|-------------|----------|
| Cite the existing test if it qualifies | If an existing test already exercises the fixed behavior and would fail pre-fix, cite it as evidence rather than duplicating coverage. | ✓ |
| Always write a new dedicated test | Every entry gets its own newly-authored test regardless of what already exists. | |

**User's choice:** Cite the existing test if it qualifies (recommended)
**Notes:** None additional.

---

## Scope expansion to all 38 fix-applied entries

**User's free-text prompt:** "Revisar todas las known issues con fixed applied para moverlas a done no solo las previamente citadas" (Review all known issues with fix-applied status to move them to done, not just the previously cited ones.)

Follow-up clarifying questions and answers:

| Question | Options | Selected |
|----------|---------|----------|
| Apply the same audit to all 38 entries, not just the original 10? | Yes, all 38 (confirmed) / Just the original 10 | Yes, all 38 (confirmed) |
| Should Phase 3 also tackle LOG-44 (deferred NostrSessionManager/RelayConfigViewModel test-infra gap)? | No, stays deferred / Yes, close it out too | No, stays deferred (recommended) |
| How should REQUIREMENTS.md reflect the expanded traceability? | Add new VALID requirements for the 28 (recommended) / Light touch, context-only | Add new VALID requirements for the 28 (recommended) |

**Notes:** `docs/KNOWN_ISSUES.md` has 38 entries at `fix applied — needs on-device validation`, not just VALID-01..10's ten. The other 28 are Phase 1/2's own fixed bugs that never got moved to DONE.md. This is a deliberate, user-confirmed scope expansion — same methodology, larger set. LOG-44 (TODO.md, in progress) and LOG-35 (KNOWN_ISSUES.md, still `open`) are both explicitly excluded.

---

## Claude's Discretion

- Exact classification (automated-verifiable vs. needs-eyeball) for each of the 28 newly-in-scope entries — methodology is locked, per-entry verdicts are not.
- Whether `ImageLoadGateTest.kt`'s existing coverage is close enough to cite for LOG-2, vs. needing a new composable-level test.

## Deferred Ideas

- **LOG-44** (missing `NostrSessionManagerTest`/`RelayConfigViewModelTest` coverage) — needs an architectural change (interface seam or mocking framework), not an audit-and-cite pass. Stays in `docs/TODO.md` at `in progress`.
