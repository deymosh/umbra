---
name: nostr-nips-reference
description: Use when you need to look up a Nostr NIP number, an event kind number, or check exact protocol semantics (tag structure, required fields, kind ranges) before implementing or reviewing NIP-related code in Umbra. Points at the canonical source (nostr-protocol/nips on GitHub) with the exact lookup commands, plus the kind-numbering convention and a table of the kinds Umbra's own domain/nipXX packages already implement — so protocol questions get answered from the spec, not guessed from memory or from what one other client happens to do.
---

# Nostr NIPs / event-kind reference

The canonical source is `github.com/nostr-protocol/nips` — one markdown file per NIP (`01.md`, `02.md`, ..., `5A.md`, `7D.md`, etc., non-numeric suffixes for NIPs added out of sequence), plus a `README.md` with the master kind-number table. It is public; fetch it directly with `gh api` rather than relying on memory of this file, which can drift from upstream as new NIPs land:

```bash
# Master list of NIPs + the Event Kinds table (kind -> description -> NIP)
gh api repos/nostr-protocol/nips/readme --jq '.content' | base64 -d

# Full text of a specific NIP, e.g. NIP-51 (Lists)
gh api repos/nostr-protocol/nips/contents/51.md --jq '.content' | base64 -d

# List every NIP file (to check a NIP exists / find its exact filename for odd ones like 5A, 7D)
gh api repos/nostr-protocol/nips/contents --jq '.[].name'
```

The README explicitly says its kind table "is not exhaustive" and points to `github.com/nostr-protocol/registry-of-kinds` as the machine-readable, more complete registry — check there if a kind isn't in the README table.

## Kind-numbering convention (from NIP-01 — stable, rarely changes)

The numeric range a kind falls in determines relay storage/replacement behavior. This matters when designing a new `domain/nipXX` feature in Umbra (e.g. it's why NIP-51 lists use `10000`–`10999`/`30000`–`39999` ranges, not arbitrary numbers):

| Range | Behavior | Notes |
|---|---|---|
| `n == 1 \|\| n == 2 \|\| 4 <= n < 45 \|\| 1000 <= n < 10000` | **Regular** | Relays are expected to store every event. |
| `n == 0 \|\| n == 3 \|\| 10000 <= n < 20000` | **Replaceable** | Only the latest event per `(pubkey, kind)` must be kept. This is why kind-0 (metadata), kind-3 (contacts), and all the `1000x` NIP-51 lists are safe to just re-publish wholesale on every change — matches Umbra's `ContactListRepositoryImpl`/`MuteListRepositoryImpl`/`PinListRepositoryImpl` "latest-event-wins" ingestion pattern exactly. |
| `20000 <= n < 30000` | **Ephemeral** | Not expected to be stored at all — for things like NIP-42 AUTH (kind `22242`). |
| `30000 <= n < 40000` | **Addressable** | Latest event per `(kind, pubkey, d-tag)` is kept — needs a `d` tag. This is the NIP-51 *sets* range (follow sets `30000`, bookmark sets `30003`, interest sets `30015`, etc.) — different from the `1000x` *lists* range, which is replaceable (one per pubkey, no `d` tag) rather than addressable (many per pubkey, keyed by `d` tag). Don't conflate the two when implementing a new NIP-51 kind — check whether the spec's kind falls in `1000x`/`2000x` (singular list) or `3000x` (many named sets) before designing the repository shape.

Tie-break for replaceable events with identical timestamps: keep the lowest event `id` (lexical order), discard the rest.

## Kinds Umbra already implements

Cross-reference with `domain/nipXX/` packages and `AUDIT.md`/`docs/nip-social-coverage.md` for current status — this table is a quick index, not the source of truth for "is X done":

| Kind | Name | NIP | Umbra location |
|---|---|---|---|
| 0 | User Metadata | 01 | `domain/nip01`, `UserRepository` |
| 1 | Short Text Note | 01/10 | `domain/nip01`, feed |
| 3 | Follows | 02 | `domain/nip02`, `ContactListRepository` |
| 5 | Event Deletion Request | 09 | `DeleteNoteUseCase` |
| 6 | Repost | 18 | `NostrEventBuilder.repost` |
| 7 | Reaction | 25 | `domain/nip25` |
| 1111 | Comment | 22 | `domain/nip22` |
| 10000 | Mute list | 51 | `domain/nip51/MuteList.kt`, `MuteListRepository` |
| 10001 | Pin list | 51 | `domain/nip51/PinList.kt`, `PinListRepository` |
| 10002 | Relay List Metadata | 65 | `domain/nip65/RelayListMetadata.kt` |
| 10050 | DM relay list | 17/51 | `domain/nip17/DmRelayList.kt` |
| 22242 | Client Authentication | 42 | `NostrEventBuilder.relayAuth` |
| 24133 | Nostr Connect | 46 | `data/amber` (Amber uses NIP-55 intents, not this kind, for local signing — but NIP-46 concepts inform the remote-signer relationship) |

Remaining NIP-51 kinds with no Umbra implementation yet (spec confirmed against the table above and `51.md`): `10003` bookmark list, `10004` communities list, `10006` blocked relays, `10007` search relays, `10012` favorite relays, `10015` interests list, `30000` follow sets, `30002` relay sets, `30003` bookmark sets, `30015` interest sets. See the `nostr-nip-implementation` skill for the pattern to follow, and a reference NIP-51 client implementation for a worked example of tag shapes.

## How to use this when implementing or reviewing

1. Before writing an event builder or parser for a NIP Umbra doesn't have yet, fetch that NIP's `.md` file and read the actual tag/kind spec — don't infer it from what NIP-01/NIP-02 already do, since tag conventions vary (e.g. `p`/`e` tags for lists vs `d`-tag-keyed addressable sets vs NIP-44-encrypted private content).
2. Before reviewing a PR that adds a new kind constant, check the kind falls in the range implied by its behavior (a "list" should not be in the `30000`+ addressable range unless it's genuinely meant to support multiple named instances per user).
3. If a NIP references another NIP for shared primitives (e.g. NIP-51 lists reference NIP-44 for private tags, NIP-17 relies on NIP-59 gift wrap and NIP-44 encryption), fetch those too — don't implement the dependent NIP in isolation.
