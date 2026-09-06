---
phase: 04-version-consistency-v0-1-0-release-prep
plan: 03
subsystem: release-tooling
tags: [release-skill, runbook, git-tag, scope-change]

# Dependency graph
requires:
  - phase: 04-version-consistency-v0-1-0-release-prep
    provides: "04-01's single source of truth for versionName, and 04-02's dated CHANGELOG.md [0.1.0] section plus docs/RELEASE_CHECKLIST.md"
provides:
  - ".claude/skills/umbra-release/SKILL.md, a reusable numbered release runbook for any future Umbra release"
affects: []

# Actuals (#2632)
actuals:
  tokens: 1200
  tasks: 1
  commits: 1

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Runbook-style skill (numbered, sequential, exact commands) as a deliberate departure from this catalog's usual narrative-plus-checklist skill voice, used specifically for a process that must be followed literally rather than interpreted."

key-files:
  created:
    - .claude/skills/umbra-release/SKILL.md
  modified: []

key-decisions:
  - "Task 2 of this plan (create the local v0.1.0 annotated tag) was explicitly descoped from phase 4 by the user during execution on 2026-09-06. The user will decide separately, as a standalone action outside this phase, when to create the tag. This is a direct user directive overriding the plan's own scope, not a deviation the executor found on its own."
  - "REL-03 (\"Prepare the v0.1.0 git tag locally\") is therefore intentionally left incomplete in REQUIREMENTS.md — not a gap, a scope change. SKILL-01 is fully satisfied by this plan's Task 1 and is marked complete."

requirements-completed: [SKILL-01]

coverage:
  - id: D1
    description: ".claude/skills/umbra-release/SKILL.md exists with catalog-conformant two-key frontmatter, a numbered sequential runbook covering version bump, changelog move, verification commands, checklist recording, tag creation, a stop-and-confirm gate structurally preceding the push step, the single-ref-scoped push command, and a workflow_dispatch-first recovery path -- with no keystore path, key material, or bulk-tag-push command anywhere, and the 0.1.0 release appearing only as a worked example"
    requirement: "SKILL-01"
    verification:
      - kind: other
        ref: "grep -cx 'name: umbra-release' == 1; git tag -a / git cat-file -t / git push origin / workflow_dispatch / RELEASE_CHECKLIST all present >=1; stop-gate line (60) < git push origin line (67); grep -c 'git push --tags' == 0; grep -ciE keystore-extension patterns == 0; grep -cE private-key/long-blob patterns == 0; grep -cE 'D-[0-9]{2}|PLAN\\.md|CONTEXT\\.md' == 0"
        status: pass
    human_judgment: true
    rationale: "Whether the runbook reads as genuinely usable top-to-bottom by a future maintainer with less context than its author, and whether the 0.1.0 references are convincingly example-only rather than baked into the runbook's own steps, is a judgment call the mechanical greps only partially cover."
---

# Phase 4 Plan 3: Release Runbook Skill Summary

**`.claude/skills/umbra-release/SKILL.md` now exists as a numbered, reusable release runbook with a structural stop-and-confirm gate before the tag push — but this plan's Task 2 (cutting the local `v0.1.0` tag) was explicitly pulled out of phase 4's scope by the user mid-execution, deferred to a separate action they'll trigger themselves.**

## Performance

- **Duration:** ~10min
- **Completed:** 2026-09-06
- **Tasks:** 1 of 2 planned (Task 2 descoped, see below)
- **Files modified:** 1 (created)

## Accomplishments

- Authored `.claude/skills/umbra-release/SKILL.md`: catalog-matching two-key frontmatter (`name: umbra-release`, `description`), then an 8-step numbered runbook — version bump (both `versionName` and `versionCode`, with their differing rules stated), moving `CHANGELOG.md`'s `[Unreleased]` bullets into a dated section, the three verification commands (`lintDebug`, `testDebugUnitTest`, `assembleRelease`), recording results in `docs/RELEASE_CHECKLIST.md`, annotated tag creation with its `git cat-file -t` confirmation, an unmissable stop-and-confirm gate, the single-ref `git push origin vX.Y.Z` form (with an explicit warning against `git push --tags`), and a `workflow_dispatch`-first recovery path for a partial release failure.
- Closed with a signing/security section naming all four CI secrets (`SIGNING_KEY`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) by name only, stating signing is entirely CI-side and that no keystore file, keystore path, or Gradle release signing config belongs in this repository.
- Verified every acceptance criterion from the plan's Task 1 mechanically (frontmatter shape, step ordering, stop-gate-before-push line ordering, absence of `git push --tags`/keystore paths/key material/planning-doc references, 0.1.0 appearing only as a worked example).

## Task Commits

1. **Task 1: Author the umbra-release runbook skill** - `5518dda` (docs)

_Task 2 (create the local `v0.1.0` tag) was not executed — see Deviations below. No commit exists for it because it produces a git tag object, not a file change._

## Files Created/Modified

- `.claude/skills/umbra-release/SKILL.md` (new) — numbered release runbook, general-purpose (placeholders throughout), with 0.1.0 used only as a worked example

## Decisions Made

- Wrote the runbook for any future release using `vX.Y.Z`-style placeholders throughout its actual steps, reserving concrete `0.1.0` references for a single worked-example callout — per the plan's own instruction that this must not read as a 0.1.0-specific document.

## Deviations from Plan

**[User directive — scope change] Task 2 (local `v0.1.0` tag creation) removed from phase 4's scope.**

- Found during: Phase 4 execution, after Task 1 committed and the orchestrator paused to ask explicit authorization for the tag-creation action (per this project's standing instruction that tag/release actions always need per-moment authorization, not just plan approval).
- Issue: N/A — not a bug or gap. The user responded that tag creation should be considered entirely outside plan 04-03 and phase 4; they will decide separately when to create it.
- Fix: None applied — this is an explicit scope reduction, not a defect to fix. `REL-03` ("Prepare the `v0.1.0` git tag locally") is left unchecked in `REQUIREMENTS.md` and phase 4 is considered complete without it.
- Files modified: None (no tag was created; no code or doc file changes resulted from this decision beyond this SUMMARY and `STATE.md`'s blocker note).
- Verification: `git tag -l` shows no tags in the repository — confirms nothing was created despite the plan's original Task 2 instructions.
- Commit hash: N/A (no commit; this is a non-action).

**Total deviations:** 1 (1 user-directed scope change, 0 auto-fixed). **Impact:** `SKILL-01` is fully complete; `REL-03` remains open by design, to be closed by a separate, explicitly user-initiated action outside this phase. Phase 4's automated verification will report `REL-03` as an open requirement — this is expected and intentional, not a phase completion gap to chase.

## Issues Encountered

None.

## User Setup Required

None.

## Next Phase Readiness

- Phase 4 is otherwise complete: version single-source-of-truth (04-01), dated changelog + release checklist (04-02), and the release runbook skill (04-03 Task 1) are all done.
- The `v0.1.0` git tag remains uncreated by design. When the user is ready, they will say so; creating it is then a single `git tag -a v0.1.0 -m "..."` command per the new runbook's own Step 5, entirely local and reversible, with the push step (Step 7) requiring its own separate explicit go-ahead exactly as the runbook's stop-and-confirm gate describes.

---
*Phase: 04-version-consistency-v0-1-0-release-prep*
*Completed: 2026-09-06*

## Self-Check: PASSED

- FOUND: commit `5518dda` (Task 1)
- FOUND: `.claude/skills/umbra-release/SKILL.md` exists, 99 lines, catalog-conformant frontmatter
- CONFIRMED: `git tag -l` returns empty — Task 2 correctly not executed
- CONFIRMED: `REL-03` intentionally left open in `REQUIREMENTS.md`; `SKILL-01` marked complete
