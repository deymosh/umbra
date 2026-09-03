# Phase 2: Concurrency & State Correctness - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-03
**Phase:** 2-Concurrency & State Correctness
**Areas discussed:** Failure UX for delete/mute/pin, Job-field audit scope (LOG-30), Relay role-toggle lock granularity (LOG-29), Concurrency test rigor

---

## Failure UX for delete/mute/pin

### Q: FeedViewModel.muteUser/togglePin (LOG-24) — what should the user see when the local write fails after Amber already published?

| Option | Description | Selected |
|--------|-------------|----------|
| Reuse ProfileViewModel's error strings | error_mute_author / error_unmute_author etc., ResWithArgs with the exception message | ✓ |
| New dedicated strings for this pin/mute-desync case | e.g. "Mute published, but not saved locally" | |

**User's choice:** Reuse ProfileViewModel's existing error strings.

### Q: ProfileViewModel.deleteEvent (LOG-22) — what should happen once delete gets rollback-style treatment?

| Option | Description | Selected |
|--------|-------------|----------|
| Restore note + show error message | Re-insert into state.notes and set errorMessage | |
| Restore note silently, no error message | Just re-add without an explicit error | |

**User's choice (free text, translated from Spanish per user's request):** "We NEVER want optimistic UI — only when Amber signs should changes be applied. Delete will be fixed with this logic, and any other optimistic UI site should follow the same rule."

**Notes:** This reframed the fix shape entirely — instead of choosing between the two rollback-flavor options offered, the user rejected pre-confirmation state mutation as a concept. Follow-up discussion (below) scoped which code sites this principle actually applies to, and confirmed cache-removal timing.

### Q: Scope check — does the "no UI before Amber confirms" rule apply only to deleteEvent, or also to OwnerTagSetCache.updateCache and RelayConfigScreen's switch toggle?

| Option | Description | Selected |
|--------|-------------|----------|
| Only deleteEvent's onOptimisticApply | The one real Amber-signing-race violation | ✓ |
| Also audit the other two | Broader interpretation of "optimistic" | |

**User's choice:** Only deleteEvent's onOptimisticApply. The other two sites don't race Amber confirmation specifically (OwnerTagSetCache already writes after Amber signs; the relay switch never involves Amber at all).

### Q: Should cache/archive removal also become conditional on Amber confirming?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — gate cache removal on Amber confirming too | Nothing changes anywhere if Amber rejects/fails | ✓ |
| No — leave cache removal unconditional | Only the visible list moves to commit-after-sign | |

**User's choice (free text):** "Yes, gate cache removal on Amber confirming too — but I believe it should almost never delete from cache since delete is only available for user-owned events, which aren't in the cache, is this right?"

**Notes:** Claude verified this premise against `EventRepositoryImpl.deleteEvent` (lines 1536-1545) and found it incorrect — the method removes from both the encrypted Room archive AND the in-memory `EventIngestCache` (`eventIngestCache.removeCachedEvent`), since a user's own posts are also ingested into the same in-memory cache as everyone else's when rendered in feeds. This was surfaced to the user directly; the "Yes, gate it" decision stands regardless of the corrected premise.

---

## Job-field audit scope (LOG-30)

### Q: Should all 6 of NostrSessionManager's plain Job? fields convert to AtomicReference<Job?>, or only demonstrable races?

| Option | Description | Selected |
|--------|-------------|----------|
| Convert all 6 to AtomicReference<Job?> | Class-wide standardization | |
| Convert only ones with a demonstrable race | Audit call sites first | ✓ |

**User's choice:** Convert only ones with a demonstrable race.

---

## Relay role-toggle lock granularity (LOG-29)

### Q: Per-relay-id lock, or one global lock for all relay-role writes?

| Option | Description | Selected |
|--------|-------------|----------|
| Per-relay-id lock | Only same-relay toggles serialize; different relays proceed concurrently | ✓ |
| One global lock | Simpler, but adds unnecessary serialization across unrelated relays | |

**User's choice:** Per-relay-id lock.

---

## Concurrency test rigor

### Q: Behavioral tests, or force real concurrent races where feasible?

| Option | Description | Selected |
|--------|-------------|----------|
| Behavioral tests are enough | Assert end state after sequential calls | |
| Force real concurrent races where feasible | Use TestDispatcher/runTest to launch overlapping coroutines | ✓ |

**User's choice:** Force real concurrent races where feasible.

---

## Claude's Discretion

- Exact Mutex scoping/lifetime structure for the per-relay-id lock (D-06).
- Which specific NostrSessionManager fields end up converted per the LOG-30 audit rule (D-05) — the decision rule is locked, not the resulting field list.
- LOG-19 (BUG-03) and LOG-31 (BUG-14) fix approaches were not contested during discussion — CONCERNS.md's documented approach for both stands as-is.

## Deferred Ideas

None — discussion stayed within Phase 2's 8-bug scope.
