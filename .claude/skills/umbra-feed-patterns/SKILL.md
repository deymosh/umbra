---
name: umbra-feed-patterns
description: Use when adding or modifying a feed (home, thread, profile), working with FeedFilter/FeedViewModel.notesFlow, EventRepository.observeFeedNotes, or wiring mute/follow-list filtering into a feed. Adapted from a broader feed-patterns skill built around a FeedFilter<T>/ChangesFlowFilter abstraction layer — Umbra has no such layer; filtering is parameters into one repository method, not a class hierarchy.
---

# Feed patterns in Umbra

Umbra's feed abstraction is flatter than a class-hierarchy-based one: no `FeedFilter<T>`/`AdditiveFeedFilter`/`ChangesFlowFilter` class hierarchy, no `ComposeSubscriptionManager`. A feed is a `FeedViewModel` that `combine()`s its inputs into a set of parameters, calls one repository method (`EventRepository.observeFeedNotes(...)`), and shares the result.

## `FeedFilter` — the persisted, user-editable filter config

`domain/feed/FeedFilter.kt`: `id, name, hideNsfw = true, mutedPubkeys: Set<String>, excludedTags: Set<String>, excludedHashtags: Set<String>, isActive, scopeToFollows: Boolean, createdAtMillis, updatedAtMillis`. `mergeActiveFeedFilters(filters)` combines all active filters: ORs `scopeToFollows`, unions the mute/exclude sets, ANDs `hideNsfw`. This is the domain model behind the feed-config UI (`ui/feedconfig/`) — a user-defined filter, not the query-shaping abstraction itself.

## `FeedViewModel.notesFlow` — the actual query assembly

```kotlin
private val notesFlow: SharedFlow<List<NoteView>> = combine(
    _displayLimit, userPreferences.getPublicKeyFlow(), activeFiltersFlow,
    syncedMutedPubkeysFlow, followedPubkeysFlow,
) { limit, currentPubkeyRaw, activeFilters, syncedMutedPubkeys, followedPubkeys ->
    eventRepository.observeFeedNotes(
        since = 0L, limit = limit,
        authors = if (mergedFilter.scopeToFollows) followedPubkeys else emptySet(),
        mutedPubkeys = mergedFilter.mutedPubkeys + syncedMutedPubkeys,
        // ... hideNsfw, excludedHashtagsLower, currentNpub, currentUserPubkey, desiredTagsLower
    )
}.flatMapLatest { it }.flowOn(Dispatchers.IO).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
```

Two mute sources get unioned, not layered: `syncedMutedPubkeysFlow` (the NIP-51 kind-10000 mute list, via `muteListRepository.getMuteList`) and the local per-`FeedFilter` `mutedPubkeys`. `followedPubkeysFlow` (NIP-02 contacts) is only consulted when `scopeToFollows` is true — a feed that isn't follow-scoped ignores it entirely.

`EventRepository.observeFeedNotes` signature (`domain/repository/EventRepository.kt`):

```kotlin
fun observeFeedNotes(
    since: Long = 0L, limit: Int = 300, authors: Set<String> = emptySet(),
    mutedPubkeys: Set<String> = emptySet(), excludedHashtagsLower: Set<String> = emptySet(),
    includeMentions: Boolean = true, hideNsfw: Boolean = true,
    currentNpub: String? = null, currentUserPubkey: String? = null,
    desiredTagsLower: Set<String> = emptySet(),
): Flow<List<NoteView>>
```

**Adding a new feed-affecting filter (a new exclusion, a new scope) means adding a parameter here and threading it through `notesFlow`'s `combine` inputs** — not writing a new `FeedFilter` subtype or a parallel filtering class. Don't filter inside `EventRepositoryImpl`'s internals beyond what's passed in; it's designed to take these as caller-supplied parameters (see `nostr-nip-implementation`'s step 7 for the same rule from the NIP-implementation side).

## Pin lists are not in `notesFlow`

Pinned notes are checked separately and overlaid into `_uiState`/`computedFeedFlow` — they don't participate in the `combine()` that builds `notesFlow`. If you're adding something that should show regardless of mute/follow-scope filtering (like pins), that's the layer to extend, not `notesFlow`'s parameter list.

## `ThreadViewModel` is not a `FeedViewModel` subtype

`ThreadViewModel` (`ui/feed/ThreadViewModel.kt`) is a fully independent `@HiltViewModel` — no reference to `FeedViewModel` anywhere in it, no shared base class. It shares only domain-layer use cases (`PublishSignedEventUseCase`, `DeleteNoteUseCase`, `TrackReferencedAuthorUseCase`) and the same `AmberSignerGateway`/`canSignEvents()` pattern (see [`umbra-signer`](../umbra-signer/SKILL.md)), but has its own state and flows built from scratch. **Don't assume a `FeedViewModel` change propagates to threads, and don't try to extract a shared base class unprompted** — two independent implementations sharing only use-cases is the current, deliberate shape; if a third feed-like screen shows up and the duplication becomes real repetition (not just "these look similar"), that's when extraction is worth considering, not before.

## Wiring a brand-new feed screen

1. Decide the query shape: does it need a new `EventRepository.observeFeedNotes(...)` parameter, or does existing `authors`/`mutedPubkeys`/`desiredTagsLower` already cover it?
2. Subscribe the underlying relay channel — see [`umbra-relay-client`](../umbra-relay-client/SKILL.md) for `NostrChannels`/`subscribeChannel` wiring; there's no `ComposeSubscriptionManager`, it's a direct `init{}` call in the ViewModel.
3. Build the `combine(...) → flatMapLatest → flowOn(IO) → shareIn(WhileSubscribed(5_000), replay = 1)` chain following `FeedViewModel.notesFlow`'s shape (see [`kotlin-flow-state-event-modeling`](../kotlin-flow-state-event-modeling/SKILL.md) for why that specific `shareIn` config is correct for an async-only-collected feed).
4. Apply stable `LazyColumn` keys/`contentType` in the screen (`key = { it.id }, contentType = { it.kind }`, matching `NotesFeedSection.kt`/`ThreadScreen.kt`) — this is already load-bearing perf work, see [`nostr-performance-review`](../nostr-performance-review/SKILL.md).

## Don't

- Don't introduce a `FeedFilter<T>`/`AdditiveFeedFilter` abstraction to match another client's shape — Umbra's flatter, parameter-based shape is deliberate and there's exactly one feed + one thread implementation, not enough variety yet to justify a filter-class hierarchy.
- Don't add a new mute-source without unioning it the same way `syncedMutedPubkeysFlow` and the local filter's `mutedPubkeys` are unioned — a feed that only checks one of two active mute mechanisms is a real (and non-obvious) privacy leak in a client whose whole premise is privacy.
- Don't build a shared `FeedViewModel`/`ThreadViewModel` base class as a drive-by refactor — see above.
