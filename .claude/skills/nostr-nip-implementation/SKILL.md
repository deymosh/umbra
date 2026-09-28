---
name: nostr-nip-implementation
description: Looking up a NIP/kind/tag spec, or implementing/extending one (new kind, domain/nipXX package, list type, repository, signing wiring).
---

# NIPs in Umbra

AUDIT.md's rules (Tor-only, Amber-only signing, Clean Architecture) override everything here.

## 1. Read the spec, not memory

```bash
gh api repos/nostr-protocol/nips/readme --jq '.content' | base64 -d        # NIP list + kind table
gh api repos/nostr-protocol/nips/contents/51.md --jq '.content' | base64 -d # one NIP
# (or https://raw.githubusercontent.com/nostr-protocol/nips/master/51.md)
```

- The README kind table isn't exhaustive. `nostr-protocol/registry-of-kinds` is.
- Fetch dependent NIPs too (NIP-51 private tags → NIP-44; NIP-17 → NIP-59 + NIP-44).
- Prior art:
  - **Amethyst** (`vitorpamplona/amethyst`) for protocol correctness and feature scope. Don't port its architecture.
  - **Wisp** (`barrydeen/wisp`, `nostr/NipXX.kt`) for parse/build pairs shaped like Umbra's `domain/nipXX/`.

Kind ranges (NIP-01) decide storage:

| Range | Behaviour |
|---|---|
| 1, 2, 4–44, 1000–9999 | regular (all stored) |
| 0, 3, 10000–19999 | replaceable: latest per (pubkey, kind); ties → lowest id |
| 20000–29999 | ephemeral (e.g. 22242 AUTH) |
| 30000–39999 | addressable: latest per (kind, pubkey, `d`) |

NIP-51 `1000x` *lists* (one per user) are not `3000x` *sets* (many, keyed by `d`). Check which one a spec kind is before designing the repository.

Already implemented:
- kinds 0, 1, 3, 5 (`DeleteNoteUseCase`), 6, 7, 1111 (NIP-22)
- 10000 mute, 10001 pin, 10002 NIP-65, 10050 DM relays
- 22242 AUTH

`docs/nip-social-coverage.md` is the source of truth. Candidate NIP-51 kinds and their tags:

| Kind | Tags |
|---|---|
| 10003 bookmarks | `e`, `a`, `t` |
| 10006 / 10007 / 10012 blocked / search / favourite relays | `relay` |
| 30000 follow set | `d`, `title`, `p` |
| 30002 relay set | `d`, `title`, `relay` |
| 30003 bookmark set | `d`, `title`, `e`, `a`, `t` |
| 30015 interest set | `d`, `title`, `t` (lowercase) |

For addressable lookups, use the existing `EventRepository.getLatestAddressableEvent`.

## 2. The established pattern (ContactList / MuteList / PinList)

1. **Model**: `domain/nipXX/<Thing>.kt`, a plain data class (`ownerPubkey`, values, `updatedAt`) with no Android or Room types.
2. **Interface**: `domain/repository/<Thing>Repository.kt`. It exposes `getX(pubkey): Flow<X?>`, mutations returning `Result<Unit>`, and convenience reads.
3. **Impl**: `data/repository/<Thing>RepositoryImpl.kt`, `@Singleton`.
   - `init` subscribes to `eventRepository.observeRecentEvents(4000)` plus the owner's own `observeEventsByPubkeyAndKind(owner, KIND, 32)` via `getPublicKeyFlow().flatMapLatest`.
   - Both feed one `ingestXEvents`: group by lowercase pubkey, latest wins by `createdAt`, then `tags.size`, then `id`.
   - Mutations only edit the in-memory `MutableStateFlow` cache. **They never sign.**
   - When the cache is cold, bootstrap from `observeEventsByPubkeyAndKind(owner, KIND, 1).first()`.
4. **Builder**: add a function to `NostrEventBuilder` (normalize, lowercase, `distinct().sorted()`, `buildUnsignedEvent`).
5. **DI**: add the `@Binds` in `di/RepositoryModule.kt`.
6. **Signing happens in the ViewModel**, through `InteractionActionsCoordinator.requestSignAndPublish(buildEventJson = { ... }, currentUserHex, onSigned = { commit mutation })`.
   - Commit only after Amber signs.
   - Use the *lazy* `buildEventJson` whenever content derives from a list that can change during Amber's wait, so overlapping actions don't revert each other.
   - Never touch Amber outside a ViewModel, and never bypass `PublishSignedEventUseCase`. See `umbra-signer`.
7. **Feed gating** (like mutes): combine the repository flow into `FeedViewModel.notesFlow` and pass it to `observeFeedNotes(...)`. Don't filter inside `EventRepositoryImpl`.
   - Any user-visible hiding must be user-editable (`FeedFilter` style), never hardcoded.
8. **Tests**:
   - Repository tests cover latest-wins, mutate-then-read, and the no-user failure path.
   - Add a `NostrEventBuilderTest` case.
   - Fake `observeEventsByPubkeyAndKind` must sort by `createdAt` descending and respect `limit`.

## Don't

- Add an on-device signer or nsec path.
- Port a mutable global Note/User object graph.
- Copy the ingestion scaffolding per NIP without reason. Extract it once repetition genuinely justifies it.
