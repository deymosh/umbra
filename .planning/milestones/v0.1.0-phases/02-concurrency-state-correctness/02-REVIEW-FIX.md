---
phase: 02-concurrency-state-correctness
fixed_at: 2026-09-04T12:00:00Z
review_path: .planning/phases/02-concurrency-state-correctness/02-REVIEW.md
iteration: 3
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 02: Code Review Fix Report

**Fixed at:** 2026-09-04T12:00:00Z
**Source review:** `.planning/phases/02-concurrency-state-correctness/02-REVIEW.md`
**Iteration:** 3

**Summary:**
- Findings in scope: 4 (0 critical, 2 warning, 2 info — `fix_scope: all`)
- Fixed: 4
- Skipped: 0

**Verification:** `./gradlew compileDebugKotlin`, `./gradlew lintDebug`, and
`./gradlew testDebugUnitTest` (full suite) all passed clean, plus
`RelayCrudCoordinatorTest` (the file touched for IN-02) was run individually and confirmed all six
of its cases — the five pre-existing plus the one added by this pass — pass with zero
failures/errors. Ran in the main checkout — `workflow.use_worktrees` is `false` in
`.planning/config.json`, so no isolated worktree was created for this run; every commit below
landed directly on `gsd/v0.1.0-hardening-first-public-release`. Findings were resumed from a prior
fixer run for this exact pass that was interrupted by a session rate limit after WR-01, WR-02, and
IN-01 were already committed; this run verified those three against the current source (all
correct, no rework needed) before finishing IN-02 and this report.

This is the third and final `--auto` fixer pass for Phase 2, closing out the iteration-3 re-review's
four findings. WR-01/WR-02/IN-01 from the original review and iteration 2's own WR-01 through IN-02
are all superseded by this file's history; nothing from either prior pass was reopened.

## Fixed Issues

### WR-01: `ownProfileBootstrapMutex` only guards `maybeBootstrapOwnProfile`'s own body — `stopOwnProfileBootstrap()` is still called unguarded from two other places that mutate the same fields

**Files modified:** `app/src/main/java/com/umbra/app/data/nostr/NostrSessionManager.kt`
**Commit:** `b838a7d`
**Applied fix:** Split `stopOwnProfileBootstrap()` into a private, non-locking
`stopOwnProfileBootstrapLocked()` callable only by code that already holds
`ownProfileBootstrapMutex` (both in-lock call sites inside `maybeBootstrapOwnProfile` now use it
directly), and wrapped the watcher job's own trailing teardown call — which runs on its own
separately-scheduled coroutine outside the block that launched it — in a fresh
`ownProfileBootstrapMutex.withLock { }`. `stop()` cannot take the lock at all
(`NostrSessionController.stop()` is a plain, non-suspend `fun`), so it mutates
`ownProfileBootstrapWatcherJob`/`ownProfileBootstrapPubkey` directly instead, mirroring the
`getAndSet(null)?.cancel()` pattern already used there for `retryJob`/`userBackfillJob`; `start()`
now also unconditionally resets `ownProfileBootstrapPubkey` to `null`, so a lost race in `stop()`
can at worst leave a bootstrap channel running slightly longer than intended but can never cause a
same-pubkey re-login to silently skip re-bootstrapping. Verified against the current source during
this run: `stopOwnProfileBootstrapLocked()` exists and is called from both in-lock sites in
`maybeBootstrapOwnProfile` (lines 462, 466), the watcher job's tail wraps its call in
`ownProfileBootstrapMutex.withLock { }` (line 480), `stop()` uses `getAndSet(null)?.cancel()` for
the watcher job and resets the pubkey directly (lines 323–324), and `start()` unconditionally
resets `ownProfileBootstrapPubkey = null` (line 213) — matches the review's fix guidance exactly,
no rework needed.

### WR-02: `RelayCrudCoordinator.saveRelay`'s new-relay branch still decides "does this relay already exist" from the stale `state.value.relays` mirror, independent of the merge-computation fix

**Files modified:** `app/src/main/java/com/umbra/app/ui/relay/RelayCrudCoordinator.kt`
**Commit:** `fe082a2`
**Applied fix:** `existingRelay` is now resolved from a fresh `relayRepository.getAllRelays().first()`
read first, falling back to the throttled `state.value.relays` mirror only if that repository read
itself doesn't find a match — consistent with this file's established "fresh read, not throttled
mirror" principle already used throughout `updateRelayRole`. Verified against the current source
during this run: `saveRelay`'s `relay.id.isEmpty()` branch reads `freshRelays =
relayRepository.getAllRelays().first()` before computing `existingRelay` (lines 94–99), matching
the review's fix guidance exactly, no rework needed.

### IN-01: The same throwable-discarding log pattern iteration 2 just fixed in `NostrSessionManager` (IN-02) is still present, unfixed, in `InteractionActionsCoordinator.kt`

**Files modified:** `app/src/main/java/com/umbra/app/ui/common/InteractionActionsCoordinator.kt`
**Commit:** `96861b2`
**Applied fix:** Both `requestSignAndPublish`'s `catch` block and `publishSignedEvent`'s
`onFailure` handler now call `logger.e(e) { ... }` instead of `logger.d { ... }`, keeping the same
scrubbed message text as the log line's content — matching `NostrSessionManager`'s and
`RelayConfigViewModel`'s existing correct pattern. Verified against the current source during this
run: both sites (lines 97, 116) call `logger.e(e) { ... }`, matching the review's fix guidance
exactly, no rework needed.

### IN-02: No regression test exercises `RelayCrudCoordinator.saveRelay`'s merge-branch fresh-read fix

**Files modified:** `app/src/test/java/com/umbra/app/ui/relay/RelayCrudCoordinatorTest.kt`
**Commit:** `95f5722`
**Applied fix:** Added a `RecordingRelayRepository`-based test that seeds an existing relay
(`isReadEnabled = true`) directly into the repository while leaving it out of the `state.value.relays`
passed to `subject()` — simulating the 300ms-throttled UI mirror not yet having caught up — then
calls `saveRelay` with a blank id and the same URL (`isWriteEnabled = true`) and asserts the
repository still holds exactly one row for that URL with both the pre-existing `isReadEnabled` and
the newly-merged `isWriteEnabled` flags set. This fails under the pre-WR-02/WR-53 code (which would
route into the unguarded "add new" branch, producing two rows) and passes against the current fix,
proving the merge path resolves `existingRelay` via a fresh repository read rather than the stale
mirror. This test was found already written but uncommitted in the working tree from the
interrupted prior run; this pass read it, confirmed it correctly proves the claimed behavior, ran
it standalone (`RelayCrudCoordinatorTest`, all 6 cases pass), and committed it as-is with no
changes needed.

## Skipped Issues

None — all four in-scope findings were fixed.

## Documentation

A separate `docs(02)` commit (`59ea63c`) logged all four findings per CLAUDE.md's bug-tracking
discipline: LOG-52 (WR-01), LOG-53 (WR-02), LOG-54 (IN-01), and LOG-55 (IN-02) as
`docs/KNOWN_ISSUES.md` entries with status `fix applied — needs on-device validation`, each naming
its corresponding fix commit (`b838a7d`, `fe082a2`, `96861b2`, `95f5722`).

---

_Fixed: 2026-09-04T12:00:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 3_
