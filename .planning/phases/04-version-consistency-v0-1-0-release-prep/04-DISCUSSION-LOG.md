# Phase 4: Version Consistency & v0.1.0 Release Prep - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-05
**Phase:** 4-Version Consistency & v0.1.0 Release Prep
**Areas discussed:** Version display format, CHANGELOG release entry, Release skill depth, Tag & verification recording

---

## Version display format

| Option | Description | Selected |
|--------|-------------|----------|
| Just "0.1.0" | Drop the beta label entirely — plain `BuildConfig.VERSION_NAME`, no suffix | ✓ |
| "0.1.0" + static suffix | Keep a hand-written qualifier, e.g. "0.1.0 (early release)" | |
| Include build metadata | Show versionCode or git short-hash appended | |

**User's choice:** Just "0.1.0"
**Notes:** —

| Option | Description | Selected |
|--------|-------------|----------|
| Keep as-is | Only the value changes; label/row layout untouched | ✓ |
| Also review the row | Also check label text/row styling while touching the code | |

**User's choice:** Keep as-is
**Notes:** —

---

## CHANGELOG release entry

| Option | Description | Selected |
|--------|-------------|----------|
| Today (2026-09-05) | Date the section to when this phase's changelog work lands | ✓ |
| Tag/push date | Placeholder now, real date set only at actual tag push | |

**User's choice:** Today (2026-09-05)
**Notes:** —

| Option | Description | Selected |
|--------|-------------|----------|
| Keep empty [Unreleased] | Standard Keep a Changelog practice, ready for next entry | ✓ |
| Remove it for now | Drop the header until the next unreleased change lands | |

**User's choice:** Keep empty [Unreleased]
**Notes:** —

| Option | Description | Selected |
|--------|-------------|----------|
| Move verbatim | Re-head only, no content changes | |
| Light cleanup | Re-head, plus fix stale/duplicate bullets noticed along the way | ✓ |

**User's choice:** Light cleanup
**Notes:** —

| Option | Description | Selected |
|--------|-------------|----------|
| Add a fixes summary | New Fixed bullets/subsection summarizing Phases 1-3's hardening work | ✓ |
| Leave untouched | CHANGELOG.md stays scoped to pre-existing feature history only | |

**User's choice:** Add a fixes summary
**Notes:** This milestone's LOG-N fixes were previously only tracked in docs/DONE.md; user wants a reader-facing summary in the changelog too.

---

## Release skill depth

| Option | Description | Selected |
|--------|-------------|----------|
| Runnable step-by-step | Literal ordered runbook — exact commands, exact files, ending in the confirm-before-push gate | ✓ |
| Reference + checklist | Narrative style matching umbra-gradle/umbra-signer, explains why without exact commands | |

**User's choice:** Runnable step-by-step
**Notes:** —

| Option | Description | Selected |
|--------|-------------|----------|
| General, reusable runbook | Works for any future release, v0.1.0 only as worked example | ✓ |
| v0.1.0-specific first pass | Document only what's done now; generalize later | |

**User's choice:** General, reusable runbook
**Notes:** —

---

## Tag & verification recording

| Option | Description | Selected |
|--------|-------------|----------|
| Annotated, simple message | `git tag -a v0.1.0 -m "..."` short one-line message | ✓ |
| Annotated, changelog body | Tag message embeds the full [0.1.0] CHANGELOG bullet list | |
| Lightweight tag | No message, relies on generate_release_notes | |

**User's choice:** Annotated, simple message (after clarifying round — user first corrected/confirmed the tag naming convention itself: `v0.1.0`, matching `vMAJOR.MINOR.PATCH` semver and the `android-release.yml` `v*` trigger)
**Notes:** User's first response emphasized getting the tag naming convention right before picking a message style — confirmed as `v0.1.0`.

| Option | Description | Selected |
|--------|-------------|----------|
| Phase docs only | Recorded in PLAN.md/SUMMARY.md/PROJECT.md, no new doc | |
| New RELEASE_CHECKLIST doc | Dedicated docs/RELEASE_CHECKLIST.md, reusable for future releases | ✓ |

**User's choice:** New RELEASE_CHECKLIST doc
**Notes:** Pairs with the reusable umbra-release skill.

| Option | Description | Selected |
|--------|-------------|----------|
| Try assembleRelease first | Confirm the scouting finding (no signingConfig on `release` build type means it should build unsigned locally); use as REL-02 evidence if it succeeds | ✓ |
| Just use assembleBenchmark | Keep the roadmap's original assumption, don't re-derive | |

**User's choice:** Try assembleRelease first
**Notes:** Scouting found `app/build.gradle.kts`'s `release` build type has no `signingConfig` — signing is entirely CI-side via `r0adkll/sign-android-release`, so a local unsigned R8-minified build should be achievable without keys, which would supersede STATE.md's blocker note.

---

## Claude's Discretion

- Exact wording of the tag's annotation message
- Exact phrasing of the `[0.1.0]` Fixed-summary in CHANGELOG.md
- Exact structure/checklist item wording of `docs/RELEASE_CHECKLIST.md`

## Deferred Ideas

None — discussion stayed fully within phase scope; no scope-creep items surfaced.
