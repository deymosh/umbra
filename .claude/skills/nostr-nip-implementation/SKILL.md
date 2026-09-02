---
name: nostr-nip-implementation
description: Use when implementing or extending a Nostr NIP in Umbra (new kind, new domain/nipXX module, new list type, new repository). Encodes the established ContactList/MuteList pattern plus concrete kind constants and tag semantics cross-referenced from two other Nostr clients (named in the Reference clients section below) so new NIP work is consistent with the rest of the codebase and protocol-correct.
---

# Implementing a NIP in Umbra

Read [AUDIT.md](../../../AUDIT.md) first — its rules (TOR-only, Amber-only signing, Clean Architecture layering) are absolute and override anything below.

## Reference clients

Two other Kotlin/Compose Nostr clients are useful prior art — fetch their source with `gh api repos/<owner>/<repo>/contents/<path>` (both are public, no auth needed beyond a logged-in `gh`) rather than guessing tag layouts from memory:

- **Amethyst** (`vitorpamplona/amethyst`) — the most NIP-complete Android client that exists. Its README's "Supported Features" checklist is the best single reference for whether a NIP is worth prioritizing and what a mature implementation covers. Architecture differs from Umbra on purpose (mutable in-memory Note/User object graph, LiveData/Flow per-object) — don't port that pattern, Umbra's immutable-state + Room design is a deliberate, stricter choice for auditability. Use Amethyst for *protocol* correctness and *feature scope*, not for *architecture*.
- **Wisp** (`barrydeen/wisp`) — architecturally close to Umbra: Kotlin, Compose, MVVM, StateFlow-only (no LiveData/RxJava), NIP-55/Amber remote-signer abstraction, each NIP as a standalone object with pure parse/build functions (`app/src/main/kotlin/com/wisp/app/nostr/NipXX.kt`). When a NIP has non-obvious tag structure (list kinds, private/encrypted tags, addressable events), read Wisp's `NipXX.kt` for the parse+build pair before writing your own — it's the closest analog to how Umbra's `domain/nipXX/` packages are organized.

## The established pattern (NIP-02 ContactList → NIP-51 MuteList)

For a "the user publishes a replaceable list of pubkeys/values" NIP (contact lists, mute lists, pin lists, bookmark lists, relay sets, follow sets...), follow the shape already used by `ContactListRepositoryImpl` and `MuteListRepositoryImpl` exactly:

1. **Domain model** — `domain/nipXX/<Thing>.kt`: a plain data class (`ownerPubkey`, the set/list of values, `updatedAt`). No Room/Android types.
2. **Repository interface** — `domain/repository/<Thing>Repository.kt`: `getX(pubkey): Flow<X?>`, mutation methods returning `Result<Unit>`, plus any `isX()`/`getCurrentX()` convenience reads.
3. **Repository impl** — `data/repository/<Thing>RepositoryImpl.kt`, `@Singleton @Inject constructor(userPreferences, eventRepository)`:
   - `init {}` subscribes to two things: `eventRepository.observeRecentEvents(limit = 4000)` (catches other people's lists as they arrive) and a `userPreferences.getPublicKeyFlow().flatMapLatest { eventRepository.observeEventsByPubkeyAndKind(owner, KIND, limit = 32) }` (keeps the logged-in user's own list hot from the encrypted archive).
   - Both funnel into one `ingestXEvents(events: List<Event>)`: group by `pubkey.lowercase()`, pick the max by `compareBy { createdAt }.thenBy { tags.size }.thenBy { id }` per owner (latest-event-wins), parse tags, `updateCache()`.
   - Mutation methods (`mute`/`follow`/etc.) read-modify-write an in-memory `MutableStateFlow<Map<ownerPubkey, X>>` — they do **not** sign or publish. Signing happens in the ViewModel (see below).
   - `resolveX(ownerPubkey)` bootstraps from `eventRepository.observeEventsByPubkeyAndKind(owner, KIND, limit = 1).first().firstOrNull()` when the in-memory cache is cold.
4. **Event builder** — add a function to `domain/nip01/NostrEventBuilder.kt` (`fun xList(values: Set<String>): String`) mirroring `contactList()`/`muteList()`: normalize (lowercase, filter valid length, `distinct().sorted()`), build tags, `buildUnsignedEvent(kind, content, tags)`.
5. **DI** — add the `@Binds` pair in `di/RepositoryModule.kt` next to the existing repository bindings.
6. **ViewModel wiring — this is where signing happens.** Both `FeedViewModel` and `ProfileViewModel` manually construct their own `InteractionActionsCoordinator` (`ui/common/InteractionActionsCoordinator.kt`, never Hilt-injected — the shared sign/publish primitive both ViewModels use) and call its `requestSignAndPublish(...)`, committing the repository mutation inside the `onSigned` callback — i.e. state only changes *after* Amber confirms the signature, never optimistically beforehand. This is now the **one** established idiom, used consistently by every mute/pin/follow/like/repost/delete action in both ViewModels:
   ```kotlin
   interactionActionsCoordinator.requestSignAndPublish(
       buildEventJson = { NostrEventBuilder.xList(currentSet + or - target) },  // lazy — see below
       currentUserHex = userPreferences.getPublicKey(),
       onSigned = { /* commit the repository mutation here, only now */ },
   )
   ```
   Older guidance describing a `ProfileViewModel`-specific optimistic-update-then-rollback path (`pendingXAction`, `handleSignResult()` rolling back on rejection) or a `FeedViewModel`-specific `pendingSignQueue`/`PendingSignEntry` queue is **obsolete** — both were deleted when `ProfileViewModel` converged onto `FeedViewModel`'s commit-after-sign shape; neither exists in the codebase anymore. Use `buildEventJson`'s **lazy** form (a suspend lambda, not a pre-built string) whenever the event content is derived from a caller-owned list/set that could change during Amber's unbounded approval wait — rebuilding from the live list right before signing, rather than a stale pre-wait snapshot, is what prevents two overlapping same-kind actions from reverting each other (see [`umbra-signer`](../umbra-signer/SKILL.md) for the concrete regression this fixed).
   - **Never** sign or call `AmberConnector`/`AmberSignerGateway` outside a ViewModel (AUDIT.md §2.6), and never publish without going through `PublishSignedEventUseCase` — `requestSignAndPublish` already does this internally, don't bypass it.
7. **Read-side wiring into the feed** — if the new list should gate what's shown (like mutes), add a `Flow<Set<String>>`/`Flow<X>` sourced from the repository into `FeedViewModel`'s `combine(...)` that builds `notesFlow`, and fold it into the `mutedPubkeys`/filter arguments passed to `eventRepository.observeFeedNotes(...)`. Don't filter in `EventRepositoryImpl` internals — it already takes these as caller-supplied parameters by design.
8. **Tests** — one test file per repository (`data/repository/<Thing>RepositoryImplTest.kt`) covering: latest-event-wins ingestion, mutate-then-read, and the "no authenticated user" failure path. Plus a `NostrEventBuilderTest` case for the new builder function. When writing a fake `EventRepository.observeEventsByPubkeyAndKind`, make sure it actually sorts by `createdAt` descending and respects `limit` — the real implementation does, and a fake that returns unsorted/unlimited results will make bootstrap-path tests flaky in a way that looks like a repository bug but isn't (this bit a real test during MuteListRepositoryImplTest development — see git history).

## NIP-51 kind/tag reference (cross-checked against a reference client's NIP-51 implementation)

Umbra has kind 10000 (mute list) done. Remaining NIP-51 kinds, if picked up next, with a reference client's field/tag shapes as a starting point (adapt to Umbra's plain-`p`-tag-only style used so far — no NIP-44 private tags yet, since Umbra's NIP-44 support is still partial):

| Kind | Purpose | Tags |
|---|---|---|
| 10001 | Pin list | `e` (event id, optional relay hint) |
| 10003 | Bookmark list | `e` (events), `a` (addressable coordinates), `t` (hashtags) |
| 10006 | Blocked relays | `relay` |
| 10007 | Search relays | `relay` |
| 10012 | Favorite relays | `relay` |
| 30000 | Follow set (addressable, needs `d` tag) | `d`, `title`/`name`, `p` (members) |
| 30002 | Relay set (addressable) | `d`, `title`/`name`, `relay` |
| 30003 | Bookmark set (addressable) | `d`, `title`/`name`, `e`, `a`, `t` |
| 30015 | Interest set (addressable) | `d`, `title`/`name`, `t` (hashtags, lowercased) |

Addressable kinds (3000x) need a `d` tag and are looked up by `(kind, pubkey, d)`, not just `(kind, pubkey)` — check whether `EventRepository` has an addressable-event lookup (`getLatestAddressableEvent`) before adding a new one; it already exists and is used elsewhere.

## Don't

- Don't introduce a `LocalSigner`/on-device-nsec path to match another client — Umbra's Amber-only constraint is intentional and stricter, not a gap.
- Don't port a mutable global object graph (`Note`/`User` singletons) — conflicts with AUDIT.md's `@Immutable` state + Clean Architecture rules.
- Don't duplicate the ingestion/latest-wins logic per NIP — if a third or fourth list type shows up, consider extracting the common "replaceable-list-of-pubkeys" scaffolding, but two instances (contact, mute) isn't enough repetition yet to justify it.
