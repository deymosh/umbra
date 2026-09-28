---
name: umbra-issue-log
description: Logging a bug or backlog item found mid-session, or moving one between docs/KNOWN_ISSUES.md, docs/TODO.md and docs/DONE.md (LOG-n ids).
---

# Umbra issue log

Bugs and backlog items found or suggested mid-session — via code review, manual testing, or the user pointing one out — are logged across three files, distinct from GitHub Issues (CONTRIBUTING.md's "Reporting bugs" section is for external contributors formally filing an issue; these are Claude Code's own running lists for items that aren't necessarily issues yet):

- **[docs/KNOWN_ISSUES.md](../../../docs/KNOWN_ISSUES.md)** — open bugs not yet fixed.
- **[docs/TODO.md](../../../docs/TODO.md)** — the general project backlog: suggested/planned tasks, features, and refactors that are *not* bugs. NIP-specific sequencing stays in `docs/nip-priority-roadmap.md` and is cross-linked from TODO.md rather than duplicated.
- **[docs/DONE.md](../../../docs/DONE.md)** — append-only log of completed work, fed by both of the above once an item is finished.

Each entry gets a locally sequential ID (`LOG-1`, `LOG-2`, ...) — independent of and never matching a GitHub issue/PR number, and never reused once an entry moves to DONE.md — so the user can say "fix LOG-3" or "do LOG-7" and mean one exact, unambiguous item regardless of which file it's currently in or whether it was ever filed as a GitHub issue. The `LOG-` prefix (rather than a bare `#<n>`) is deliberate: a plain `#14` in a doc or commit message is indistinguishable from a GitHub issue/PR reference and GitHub auto-links it as one, which is wrong here. This is a **single global counter shared across all three files**: before assigning a new ID, check the highest number already used across all three, and increment — there's no separately-maintained per-file counter to fall out of sync.

## Bugs (docs/KNOWN_ISSUES.md → docs/DONE.md)

- **`docs/KNOWN_ISSUES.md`** — one entry per open bug:
  ```
  ## LOG-<n> — <short title>
  - **Status:** open
  - **Found:** <YYYY-MM-DD>
  - **Where:** <file/screen/flow>

  <description — what's wrong, how to repro if known>
  ```
- When a fix is committed, update that entry's status in place to `fix applied — needs on-device validation` and add a `**Fix:**` line pointing at the commit/PR. Don't move it to DONE.md yet — an applied fix isn't confirmed working until it's actually been run.
- **Emulator/device validation stays opt-in** (see Workflow above) — Umbra doesn't run autonomous on-device test passes. A `fix applied` entry just sits in KNOWN_ISSUES.md until the user explicitly asks to validate it (e.g. via the `run-umbra` skill) or confirms it themselves.
- Once validated, move the entry verbatim from `docs/KNOWN_ISSUES.md` to `docs/DONE.md`, appending a `**Validated:** <YYYY-MM-DD>` line.

## Backlog (docs/TODO.md → docs/DONE.md)

- **`docs/TODO.md`** — one entry per backlog item:
  ```
  ## LOG-<n> — <short title>
  - **Status:** backlog | in progress | not applicable
  - **Added:** <YYYY-MM-DD>
  - **Why:** <1-2 line rationale — why this is worth doing / where it came from>

  <description — what the task/feature/refactor actually is>
  ```
- An item that gets triaged out is marked `not applicable` in place rather than deleted, so the reasoning stays on record.
- Once shipped, move the entry verbatim from `docs/TODO.md` to `docs/DONE.md`, appending a `**Completed:** <YYYY-MM-DD>` line and a `**From:** TODO LOG-<n>` back-reference. A backlog item doesn't need on-device validation the way a bug fix does (no `**Validated:**` line), though it can still get one if it was UI-facing and the user confirms it on-device.

## General

- `docs/DONE.md` is an append-only historical record — don't edit past entries beyond adding the one date line each transition calls for.
- Keep all three files updated as a normal part of the work itself — log an item the moment it's found/suggested, update its status the moment a fix or a piece of work lands — not just when the user separately asks for it.
