---
name: umbra-app-state
description: Use when working with EventLruCache, EventRepositoryImpl's two-tier cache, the SQLCipher Room database (EncryptedUmbraDatabase), UserPreferences, or deciding whether a new kind of event/state should persist to Room or stay in-memory only. Adapted from a broader account-state skill built around a mutable, always-persist-everything object graph; Umbra's state model is a deliberately different, stricter design (immutable + own-user-only persistence) and the two should not be conflated.
---

# App state in Umbra: Room (own user) + EventLruCache (everyone else)

AUDIT.md's rule in one line: **"Only the signed-in user's own events persist [to Room]. Everyone else's content lives only in an in-memory, access-order `EventLruCache` and is re-fetched from relays as needed."** This skill is where that rule actually lives in code — read it before adding any new kind of cached/persisted state.

## The two tiers

**L1 — `EventLruCache`** (`data/repository/EventLruCache.kt`): a plain `LinkedHashMap<String, Event>(16, 0.75f, accessOrder = true)` with `removeEldestEntry` calling `onEvicted(eldest.value)`. API: `get(id)`, `put(event)`, `remove(id)`, `snapshot(): List<Event>`, `clear()`, `size`. Deliberately not `android.util.LruCache` — needs to be testable in plain JVM unit tests without Robolectric. In `EventRepositoryImpl`, `cachedEvents = EventLruCache(maxSize = 50_000, onEvicted = { cachedEngagementIndex.remove(it.id) })`.

**L2 — Room, SQLCipher-encrypted**: `EncryptedUmbraDatabase` (`data/db/EncryptedUmbraDatabase.kt`), `@Database(entities = [EventEntity, UserProfileEntity, EventTagEntity, RelayEntity, FeedFilterEntity], version = 2)`, accessed via `eventDao()`/`userProfileDao()`/`eventTagDao()`/`relayDao()`/`feedFilterDao()`. `EventRepositoryImpl.cachedEventsMutex: Mutex` guards writes into it.

## The persistence decision — two separate checks, don't conflate them

`EventRepositoryImpl.shouldPersistEvent(event)` decides Room vs not:

```kotlin
if (isCurrentUserPubkey(event.pubkey)) return true   // own events: always persist
// everyone else: only if kind ∈ USEFUL_PERSISTED_KINDS, or a reply/reaction/repost/zap,
// or explicitly pinned via pinnedProfileAuthors — subject to mute/NSFW/excluded-tag filtering
```

`isCurrentUserPubkey` compares against `currentUserArchivePubkey()` (`userPreferences.getPublicKey() ?: activeUserPubkey`).

Separately, `shouldStoreInMemoryCache(eventPubkey, currentUserPubkey)` decides `EventLruCache` vs not — `true` for **everyone except the current user** (the current user's own events skip the LRU entirely and go straight to Room; they don't need a second, evictable copy). So for a given event exactly one of "goes to Room" or "goes to the LRU" is the steady-state outcome, split on whether the author is the signed-in user — not "both tiers for everyone" the way a generic two-tier-cache description might suggest.

**When adding a new event kind or feature that caches something:** don't add a new ad-hoc cache. Route through `shouldPersistEvent`/`shouldStoreInMemoryCache`'s existing decision, or extend `USEFUL_PERSISTED_KINDS` if the new kind genuinely needs to survive app restart for other users' content (rare — most new kinds should rely on the LRU + re-fetch-from-relay model, matching the in-memory event graph philosophy documented in CLAUDE.md).

## Session / login state

`UserPreferences` (`domain/preferences/UserPreferences.kt`, backed by encrypted `SecurePreferences`) is the actual session store: `savePublicKey`, `getPublicKey(): String?`, `isLoggedIn()`, `isAnonymousSession()`, `canSignWithAmber()`, `logout()`, `clearAll()`, `getPublicKeyFlow(): StateFlow<String?>`.

**Don't confuse this with `NostrSessionManager`** (`data/nostr/NostrSessionManager.kt`) — that's a different class, the app-level relay-connect/backfill orchestrator (owns its own `CoroutineScope`, reconciles the enabled relay set on a debounce — see [`umbra-relay-client`](../umbra-relay-client/SKILL.md) and [`umbra-coroutines`](../umbra-coroutines/SKILL.md)). `UserPreferences` is "who is logged in," `NostrSessionManager` is "what is the app doing about it."

## Where this differs from a mutable, always-persist design on purpose

A mutable, always-on object graph (`Note`/`User` singletons, LiveData/Flow per object) that persists everyone's events it has ever seen is a design other Nostr clients use. Umbra's design is stricter by choice, not by gap: immutable `@Immutable` UI state + Clean Architecture layering (CLAUDE.md), and only the signed-in user's own archive survives a restart — everyone else's content is treated as ephemeral, re-fetchable from relays, matching the in-memory event graph philosophy CLAUDE.md explicitly cites as the model for `EventLruCache`. Don't port a "persist everything, evict never" object graph here even if it looks like it would simplify a feature — it conflicts with the audit rule directly.

## Don't

- Don't add a second `LruCache`/in-memory event store for a new feature — extend `EventLruCache`'s existing eviction/lookup, or reuse `EventRepositoryImpl`'s existing hooks.
- Don't persist another user's content to Room without checking `shouldPersistEvent`'s existing allow-list logic first — that's the audit-relevant line, not a place to special-case around.
- Don't read `UserPreferences.getPublicKey()` synchronously somewhere reactive should be used instead — prefer `getPublicKeyFlow()` when the value needs to drive UI/repository reactivity (see how `FeedViewModel.notesFlow` folds it into its `combine(...)`, covered in [`umbra-feed-patterns`](../umbra-feed-patterns/SKILL.md)).
