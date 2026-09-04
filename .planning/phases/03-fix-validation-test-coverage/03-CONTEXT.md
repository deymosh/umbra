# Phase 3: Fix Validation & Test Coverage - Context

**Gathered:** 2026-09-04
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase audits every `docs/KNOWN_ISSUES.md` entry currently at `fix applied — needs on-device validation` and, for each one, records an explicit automated-verifiable-vs-needs-human-eyeball determination with a one-line rationale. Entries judged automated-verifiable get a named, passing unit test that would have failed against the pre-fix code (either newly written, or an existing test identified and cited); those entries then move verbatim into `docs/DONE.md` with a `**Validated:**` line. Entries genuinely needing a running app stay in `KNOWN_ISSUES.md`, restated so it's unambiguous they're awaiting the user's own `run-umbra` pass — no on-device/emulator validation happens inside this phase itself.

**Scope was expanded during discussion** (see D-08 below) from the ROADMAP-stated "ten" (VALID-01..10 / LOG-1,2,3,4,6,7,11,12,13,14) to all 38 entries currently at `fix applied` status — the other 28 are Phase 1/2's own fixes (BUG-01..14 plus code-review follow-ups) that never got moved to `DONE.md`. This is the same activity (audit + cite-or-write test + move-or-restate) applied to the full backlog, not a new capability.

This is validation/test-coverage work only — no new bug fixes, no behavior changes, no new UI, no on-device/emulator runs (stays opt-in per `.claude/CLAUDE.md`).

</domain>

<decisions>
## Implementation Decisions

### Visual/timing bugs — LOG-2, LOG-3, LOG-13
- **D-01:** The project has no Compose UI test / Robolectric infrastructure, so the actual rendered pixels for LOG-2 (image sometimes never loads — `ImageLoadGate`/`LaunchedEffect` lifecycle), LOG-3 (video aspect-ratio mismatch), and LOG-13 (avatar/banner stuck on placeholder — Tor-cold-start retry) can never be asserted by a unit test. For all three: write a narrow unit test for whatever underlying logic IS pure/testable (e.g. LOG-3's `pixelWidthHeightRatio` → `aspectRatio` calculation, LOG-13's retry-schedule invocation count/timing), but still classify all three as needing human eyeball in `KNOWN_ISSUES.md` — the test is bonus coverage, not proof the visual bug is fixed. Do not claim `DONE.md` status on the strength of a partial logic test alone.
- **Note for implementers:** `ImageLoadGateTest.kt` already exists and thoroughly covers the gate's own acquire/release/cancel-safety logic in isolation (permit leak on cancellation, try/finally correctness). LOG-2's actual root cause was in the Compose `LaunchedEffect` wiring in `NostrImageComponents.kt` (a window between `DisposableEffect`'s synchronous `onDispose` and the acquiring coroutine's cancellation), not in `ImageLoadGate` itself — assess during research whether `ImageLoadGateTest.kt`'s existing coverage is close enough to cite, or whether it only covers a different (already-safe) layer and a new, narrower test is still worth attempting for the composable-level logic.

### Concurrency-bug test rigor — LOG-4, LOG-12
- **D-02:** LOG-4 (relay-list save TOCTOU race, fixed via `ConcurrentHashMap.compute()`) and LOG-12 (same-relay concurrent dial race, fixed via `UmbraNostrClient.dialingRelays` guard) both get real-race tests — `TestDispatcher`/`runTest` launching genuinely overlapping coroutines and asserting no update is lost or no duplicate dial occurs — matching Phase 2's D-07 precedent and `EventIngestCacheTest.kt`'s existing pattern. Not simpler sequential-call behavioral tests. — **Reversibility:** reversible — a stronger test can always replace a weaker one later without touching production code.

### Existing test reuse — all 38 entries
- **D-03:** Several entries already have a test file from when their fix originally landed (`ImageLoadGateTest.kt`, `EventLruCacheTest.kt`, `FutureEventRecheckTickerTest.kt`, `RelayCrudCoordinatorTest.kt`, and likely others among the 28 added-scope entries, since Phase 2's own success criteria required test coverage for each unit-testable fix). When an existing test genuinely already exercises the fixed behavior and would demonstrably fail against pre-fix code, cite it on the `KNOWN_ISSUES.md`/`DONE.md` entry as the evidence — do not duplicate coverage by writing a second test for the same behavior. Only write a new test where no existing one qualifies, or where existing coverage is partial (tests a helper in isolation but not the actual regression path).

### Scope expansion — all 38 fix-applied entries, not just VALID-01..10
- **D-08:** Audit all 38 `docs/KNOWN_ISSUES.md` entries at `fix applied — needs on-device validation` status, not just the 10 originally scoped by ROADMAP.md (VALID-01..10 / LOG-1,2,3,4,6,7,11,12,13,14). The other 28 — LOG-18, 19, 20, 21, 22, 23, 24, 26, 27, 28, 29, 30, 31, 34, 37, 38, 39, 40, 41, 42, 46, 47, 49, 51, 52, 53, 54, 55 — are Phase 1/2's own fixed bugs (BUG-01 through BUG-14 plus the code-review auto-fix chain's follow-ups) that were never moved to `DONE.md`. This is a deliberate, explicit user-confirmed expansion using the exact same methodology (D-01/D-02/D-03 above) applied to the larger set, not new work of a different kind.
  - **REQUIREMENTS.md impact:** add new `VALID-11` onward requirement IDs for the 28 extra entries during planning, to preserve the existing requirement-to-phase traceability table convention (each `VALID-NN` maps one-to-one to a `LOG-N` entry, same as VALID-01..10 do today).
  - **Explicitly excluded from this expansion — LOG-44** (`docs/TODO.md`, status `in progress`): the deferred `NostrSessionManagerTest`/`RelayConfigViewModelTest` gap needs an actual architectural change (new interface seam or a mocking framework — both classes currently have concrete-class dependencies with no seam) — not an audit-and-cite pass. Stays deferred in `TODO.md`, out of Phase 3.
  - **Explicitly excluded — LOG-35** (`docs/KNOWN_ISSUES.md`, status `open`, not `fix applied`): no fix has landed for this one yet, so it isn't eligible for validation-audit at all; it stays `open` for a future fix.
  - — **Reversibility:** reversible — expanding audit scope doesn't touch production code; if research/planning finds the 28 extra entries are too large for one phase, they can be split into a follow-on plan without redoing anything already captured here.

### Claude's Discretion
- Exact classification (automated-verifiable vs. needs-eyeball) for each of the 28 newly-in-scope entries is left to research/planning — this context locks the *methodology* (D-01/D-02/D-03/D-08), not a pre-judged verdict per entry. Many of the 28 are pure logic/concurrency fixes (e.g. LOG-19 in-memory deletion lookup, LOG-29/37/42/47/53 relay-lock races, LOG-20/26/27/28/39/46/51/54 logging-visibility fixes) that look straightforwardly automated-testable on inspection, but confirm case-by-case during research rather than assuming.
- Whether `ImageLoadGateTest.kt`'s existing coverage is "close enough" to cite for LOG-2, vs. needing a new composable-level test, is left to research/implementation discretion per the note under D-01.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Bug tracker (primary source — read first)
- `docs/KNOWN_ISSUES.md` — all 38 `fix applied` entries this phase audits (original 10: LOG-1, 2, 3, 4, 6, 7, 11, 12, 13, 14; expanded 28: LOG-18, 19, 20, 21, 22, 23, 24, 26, 27, 28, 29, 30, 31, 34, 37, 38, 39, 40, 41, 42, 46, 47, 49, 51, 52, 53, 54, 55) plus the one still-`open` entry that must stay excluded (LOG-35).
- `docs/TODO.md` — LOG-44's deferred-architectural-gap entry, explicitly excluded from this phase's scope per D-08.
- `docs/DONE.md` — append-only target for every entry this phase validates; follow its existing `**Validated:** <date>` line convention.
- `.claude/CLAUDE.md`'s "Bug tracking" section — the full status-transition convention (`open` → `fix applied — needs on-device validation` → moved to `DONE.md`), shared `LOG-N` counter rules.
- `.planning/REQUIREMENTS.md` — VALID-01..10 definitions and Phase 3 success criteria; needs VALID-11+ added per D-08's traceability requirement.

### Prior-phase context (establishes conventions this phase's tests must follow)
- `.planning/phases/01-error-visibility-log-hygiene/01-CONTEXT.md` — D-04's recording-logger test-double pattern, relevant to any LOG-1x/2x logging-visibility entries in the expanded scope.
- `.planning/phases/02-concurrency-state-correctness/02-CONTEXT.md` — D-07's real-concurrent-race testing rigor, directly extended by this phase's D-02 to LOG-4/LOG-12, and relevant to any of the 28 expanded-scope concurrency entries (LOG-29, 37, 38, 40, 42, 47, 49, 52, 53).
- `.planning/codebase/CONCERNS.md` — root-cause analysis for the bugs this phase validates (cross-reference against KNOWN_ISSUES.md's one-line summaries; CONCERNS.md is more precise for several entries).

### Existing tests likely relevant (verify/cite per D-03, don't assume without checking)
- `app/src/test/java/com/umbra/app/util/ImageLoadGateTest.kt` — LOG-2 (partial, see D-01 note).
- `app/src/test/java/com/umbra/app/data/repository/cache/EventLruCacheTest.kt` — LOG-1.
- `app/src/test/java/com/umbra/app/ui/common/FutureEventRecheckTickerTest.kt` — LOG-7 / LOG-11.
- `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt` — LOG-29 and possibly LOG-31/37/42/47/53 (all touch the same coordinator).
- `app/src/test/java/com/umbra/app/data/repository/EventIngestCacheTest.kt` — the `kotlinx-coroutines-test` real-race pattern to match for D-02's new tests; may also already partially cover LOG-19/21/40/41.

### Testing conventions
- `.planning/codebase/TESTING.md` — JUnit 4, no mocking framework, manual fakes, given/when/then backtick naming — apply to every new test this phase writes.
- `app/src/test/java/com/umbra/app/util/MainDispatcherRule.kt` — reuse for any new coroutine-dispatcher test.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `EventIngestCacheTest.kt`'s `kotlinx-coroutines-test`/`TestDispatcher` real-concurrency pattern — direct precedent for D-02's LOG-4/LOG-12 tests.
- `ImageLoadGateTest.kt`, `EventLruCacheTest.kt`, `FutureEventRecheckTickerTest.kt`, `RelayCrudCoordinatorTest.kt` — existing test files that may already satisfy several entries' "test that would have failed pre-fix" bar per D-03; verify each before writing new coverage.

### Established Patterns
- JUnit 4, `org.junit.Assert.*`, no mocking framework — manual `Fake[Interface]` classes for dependencies, given/when/then backtick method names (`.planning/codebase/TESTING.md`).
- "Force a real race, don't just assert final sequential state" — Phase 2's D-07 precedent, now extended by this phase's D-02 to two more concurrency bugs.

### Integration Points
- Every one of the 38 audited entries maps to exactly one `docs/KNOWN_ISSUES.md` section today; a validated entry's exact text moves verbatim to `docs/DONE.md` per the established bug-tracking convention — no rewriting the description, only appending the `**Validated:**` line.

</code_context>

<specifics>
## Specific Ideas

No specific UI/visual references — this phase is test-authoring and bug-tracker bookkeeping only. The one concrete, non-obvious commitment from discussion: the scope quietly doubled from "the ten" ROADMAP names to all 38 currently-fix-applied entries (D-08) — this is the single most consequential decision in this context and must not be missed by research/planning.

</specifics>

<deferred>
## Deferred Ideas

- **LOG-44** (missing `NostrSessionManagerTest`/`RelayConfigViewModelTest` coverage) — raised while discussing scope expansion (D-08). Explicitly stays out of Phase 3: it requires an architectural change (interface seam or mocking framework introduction), not an audit-and-cite pass. Remains in `docs/TODO.md` at `in progress` for a future phase or dedicated task.

### Reviewed Todos (not folded)
None — `todo.match-phase` returned zero matches for Phase 3 (LOG-44 was surfaced by the user directly during discussion, not by the automated todo-matcher, and was explicitly excluded rather than folded).

</deferred>

---

*Phase: 3-Fix Validation & Test Coverage*
*Context gathered: 2026-09-04*
