package com.umbra.app.domain.relay

/**
 * Appends [issue] to [current], capping how many entries any single relay (by normalized URL) may
 * hold at [maxPerRelay] — evicting that SAME relay's own oldest entry when already at the cap,
 * never another relay's.
 *
 * A single global cap on the whole list (the previous design) lets one relay's burst of activity
 * — or simply a large relay pool where every relay emits its own one-time "Connected" message —
 * evict a *different* relay's history first, even when that other relay's entries (e.g. the
 * user's own outbox/inbox relay's connection history) are the ones actually worth keeping. Per-
 * relay bounding means every relay always retains its own recent history regardless of how many
 * other relays exist or how noisy they are.
 *
 * [current] is assumed chronological (oldest first) — true as long as callers only ever append,
 * never reorder.
 */
internal fun appendBoundedRelayIssue(
    current: List<RelayIssue>,
    issue: RelayIssue,
    maxPerRelay: Int
): List<RelayIssue> {
    val key = normalizeRelayUrl(issue.relayUrl)
    val oldestIndexForRelay = current.indexOfFirst { normalizeRelayUrl(it.relayUrl) == key }
    if (oldestIndexForRelay < 0) return current + issue

    val countForRelay = current.count { normalizeRelayUrl(it.relayUrl) == key }
    return if (countForRelay >= maxPerRelay) {
        current.filterIndexed { index, _ -> index != oldestIndexForRelay } + issue
    } else {
        current + issue
    }
}

/**
 * Same bounding rule as [appendBoundedRelayIssue] (per-relay cap, oldest-first eviction), but
 * applies a whole [batch] in one O(current.size + batch.size) pass instead of folding
 * [appendBoundedRelayIssue] once per item — that fold called `current + issue` (a full list copy)
 * on every single item, making the fold O(current.size × batch.size). That's fine for the small,
 * steady-state batches this normally sees (RelayConfigViewModel flushes every 250ms), but the
 * underlying SharedFlow the caller subscribes to replays its *entire* backlog (up to 3000 entries,
 * see UmbraNostrClient._relayIssueFlow) to every fresh collector — i.e. every time the relay config
 * screen is opened. With a large connected-relay pool (every connect emits its own CONNECTED
 * issue), that first batch can be in the thousands, and the O(n×m) fold became a multi-second
 * main-thread stall — the exact "config screen laggy, but only when many relays are connected"
 * symptom, since discovered-but-never-connected relays never emit a CONNECTED issue to replay.
 *
 * Groups into a per-relay queue (preserving each relay's own chronological order, which is all any
 * consumer — [resolveRelayConnectionIndicatorState]'s `lastOrNull`, RelayDetailsScreen's
 * `takeLast(50)` — actually depends on) instead of scanning/copying the whole flat list per item.
 */
internal fun appendBoundedRelayIssues(
    current: List<RelayIssue>,
    batch: List<RelayIssue>,
    maxPerRelay: Int
): List<RelayIssue> {
    if (batch.isEmpty()) return current

    val byRelay = LinkedHashMap<String, ArrayDeque<RelayIssue>>()
    for (issue in current) {
        byRelay.getOrPut(normalizeRelayUrl(issue.relayUrl)) { ArrayDeque() }.addLast(issue)
    }
    for (issue in batch) {
        val deque = byRelay.getOrPut(normalizeRelayUrl(issue.relayUrl)) { ArrayDeque() }
        deque.addLast(issue)
        if (deque.size > maxPerRelay) deque.removeFirst()
    }

    val result = ArrayList<RelayIssue>(current.size + batch.size)
    byRelay.values.forEach { result.addAll(it) }
    return result
}
