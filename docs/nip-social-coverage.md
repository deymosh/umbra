# Umbra Social NIP Coverage Matrix

Source reviewed: https://github.com/nostr-protocol/nips (README list and kind registry)

Legend:
- Social relevance: `YES` means relevant for a social Nostr client like Umbra, `NO` means out of scope for now.
- Umbra status: `YES` implemented, `PARTIAL` present but incomplete, `NO` missing.

| NIP | Title | Social relevance | Umbra status | Notes |
| --- | --- | --- | --- | --- |
| 01 | Basic protocol flow description | YES | YES | Core events/REQ/EOSE flow implemented. |
| 02 | Follow List | YES | YES | Follow list event handling and repository path. |
| 05 | Mapping keys to DNS identifiers | YES | YES | Verification repository + explicit domain module `nip05` in place. |
| 09 | Event Deletion Request | YES | YES | Builder covers single/batch `e`+`a`(addressable)+`k` tags; kind-5 is now requested in every feed/profile relay subscription (previously never fetched at all — a real bug); incoming deletions from other authors now persist to the public Room cache too (previously only removed from the in-memory L1 cache, reappearing after restart). |
| 10 | Text Notes and Threads | YES | YES | Reply/root markers and thread behavior implemented. |
| 11 | Relay Information Document | YES | YES | Domain model moved to `nip11` and fetched via TOR. |
| 17 | Private Direct Messages | YES | PARTIAL | Kinds and relay list (`10050`) modeled; full DM UX not complete. |
| 18 | Reposts | YES | YES | Repost builder and feed behavior implemented. |
| 19 | bech32-encoded entities | YES | YES | Encoder/decoder model under `nip19`. |
| 21 | `nostr:` URI scheme | YES | YES | Single resolver (`domain/nip21`) used by both text rendering and outgoing mention tagging; `nostr:` deep links now route via an `AndroidManifest.xml` intent-filter into `Screen.Profile`/`Screen.Thread` (address/`naddr` links no-op — no article-reading screen yet). |
| 22 | Comment | YES | PARTIAL | Full domain model (`domain/nip22`): root/parent scopes with correct upper/lowercase `E`/`e`, `K`/`k`, `A`/`a`, `P`/`p` tags, plus a `comment()` builder; not yet called from any compose/thread UI. |
| 23 | Long-form Content | YES | PARTIAL | Kind constant fixed this session (was incorrectly `23`, no NIP uses that kind — corrected to the spec's `30023`, which matched nothing in practice until now); full article UX still incomplete. |
| 24 | Extra metadata fields and tags | YES | NO | No explicit support map for extended metadata tags. |
| 25 | Reactions | YES | YES | Kind constants + new `nip25` reaction semantics helpers. |
| 27 | Text Note References | YES | YES | Mention/reference parsing (rendering) plus outgoing tagging: `textNote`/`reply` now auto-scan composed content for `nostr:` entities and add the matching `p`/`q` tags. |
| 29 | Relay-based Groups | YES | PARTIAL | On hold — kind constants present; group UX and controls incomplete. Deliberately deferred (large feature, not started this pass). |
| 30 | Custom Emoji | YES | PARTIAL | New `nip30` domain parser/helpers added; UI integration pending. |
| 32 | Labeling | YES | NO | No label event workflow implemented. |
| 36 | Sensitive Content | YES | YES | `domain/nip36` reads/builds the `content-warning` tag; feed's NSFW filter hides posts carrying it; composer's media upload dialog has a "mark as sensitive" toggle that attaches it to the note on publish. |
| 38 | User Statuses | YES | NO | No dedicated status publishing/subscription flow. |
| 39 | Linking Profiles to Other Platforms | YES | NO | No external identity verification flow. |
| 40 | Expiration Timestamp | YES | NO | No explicit expiration enforcement logic. |
| 42 | Authentication of clients to relays | YES | YES | AUTH challenge/response transport is spec-complete; a successful AUTH now also replays every active channel subscription to that relay (`EventRepository.reapplyChannelsToRelay`), so the original REQs that triggered `auth-required:` (or any other channel on that relay) aren't left dead. |
| 44 | Encrypted Payloads (Versioned) | YES | PARTIAL | New domain envelope model added; cryptographic payload pipeline missing. |
| 45 | Counting results | YES | PARTIAL | Transport/repository layer is spec-complete, including the `approximate` flag (`RelayCountResult.approximate`, parsed end-to-end); consumed for note-count and follower-count on the profile screen, broader UI rollout (e.g. per-relay reaction/reply counts) still pending. |
| 46 | Nostr Remote Signing | YES | NO | Umbra signs via **NIP-55** (Amber, local Android-intent signing) — architecturally unrelated to NIP-46 (relay-based remote signing, `bunker://`, kind 24133), which has zero code. Previously mislabeled in this doc as a partial NIP-46 implementation. |
| 50 | Search Capability | YES | YES | Search filters sent with relay capability negotiation (skips relays that don't advertise `supportedNips.contains(50)`, same pattern as NIP-45 COUNT). Implemented directly in `UmbraNostrClient`/`EventRepositoryImpl`/`SubscriptionType`, not a `domain/nip50` package — it's a relay-side filter field, not an event/tag shape to parse. |
| 51 | Lists | YES | PARTIAL | Public mute list (kind `10000`) and pin list (kind `10001`) fully implemented (repository + tests + UI). Bookmark (`10003`), communities (`10004`), blocked relays (`10006`), search relays (`10007`), and interests (`10015`) now have a domain model + `NostrEventBuilder` function + parser each (`domain/nip51`), matching MuteList/PinList's tag conventions — deliberately no repository/DI/Settings-UI yet since nothing consumes them (would be dead code until a feature needs them). Addressable "sets" variants (`30000`/`30003`/`30015`) still fully missing. |
| 52 | Calendar Events | NO | NO | Not social-feed priority for Umbra now. |
| 53 | Live Streaming and Spaces | YES | NO | No live event chat/space pipeline. |
| 54 | Wiki | NO | NO | Out of current product scope. |
| 56 | Reporting | YES | NO | No abuse reporting event workflow. |
| 57 | Lightning Zaps | YES | PARTIAL | Zap kinds observed; complete zap UX/payments pending. |
| 58 | Badges | NO | PARTIAL | Kind constants only, no complete badge UX. |
| 59 | Gift Wrap | YES | PARTIAL | Kind constants present, full gift-wrap flow absent. |
| 62 | Request to Vanish | YES | NO | No vanish request handling. |
| 64 | Chess (PGN) | NO | NO | Explicitly out of social feed scope. |
| 65 | Relay List Metadata | YES | YES | Domain model now split to `nip65`. |
| 66 | Relay Discovery and Liveness Monitoring | YES | NO | No relay monitoring event flow yet. |
| 67 | EOSE Completeness Hint | YES | YES | `domain/nip67` parses EOSE's optional third element (`finish`/`more`/absent). Wired into the FEED_NOTES per-relay `since` watermark (`FeedRelaySincePolicy.shouldAdvanceWatermark`): a `more` hint withholds the watermark advance instead of silently assuming the relay sent everything. The overwhelming majority of relays don't send this hint at all (`UNSPECIFIED`), which keeps today's pre-NIP-67 behavior unchanged. |
| 68 | Picture-first feeds | YES | PARTIAL | `domain/nip68` now parses a kind-20 event into title/images (reusing NIP-92 `imeta`)/description/content-warning; kind `20` is not yet in any feed subscription filter and has no dedicated gallery rendering (touching feed-kind sets would also touch counting/persistence logic that's currently sized for kind 1 specifically — scoped out of this pass as a UI-sized follow-up). |
| 69 | Peer-to-peer Order events | NO | NO | Marketplace/trading scope excluded. |
| 70 | Protected Events | YES | NO | No protected-event pipeline yet. |
| 71 | Video Events | YES | PARTIAL | Video kind constants and media UI exist; full metadata flow partial. |
| 73 | External Content IDs | NO | NO | Out of immediate social scope. |
| 75 | Zap Goals | NO | NO | Not core social-feed requirement now. |
| 77 | Negentropy Syncing | YES | YES | `domain/nip77` implements the Negentropy Protocol V1 wire format and reconciliation algorithm (verified byte-for-byte against the reference JS implementation), driven by `NegentropySyncOrchestrator`. Scoped to the signed-in user's own Room-persisted events against their own NIP-65 write relays — not a general-purpose backfill, since Umbra only persists the signed-in user's own events (everyone else's content is in-memory-only, see `EventLruCache`). Gated behind `relaySupportsNip(relay, 77)`, same pattern as NIP-45/NIP-50. |
| 78 | Application-specific data | YES | NO | No app-data event flow yet. |
| 7D | Forum Threads | YES | PARTIAL | `domain/nip7d` adds `extractForumThread()` (the `title` tag) and a `forumThread()` builder (kind 11); replies use NIP-22 comments scoped to the thread as root, per spec — no compose/thread UI wired up yet. |
| 84 | Highlights | YES | NO | No highlights events support. |
| 85 | Trusted Assertions | YES | NO | No trust assertion event support. |
| 86 | Relay Management API | NO | NO | Relay server admin API out of client scope. |
| 88 | Polls | YES | NO | No poll kind support. |
| 89 | Recommended Application Handlers | YES | NO | No handler metadata events yet. |
| 92 | Media Attachments Metadata | YES | PARTIAL | `imeta` tags parsed (`domain/nip92`) and used to enrich already-detected inline images (alt text, aspect ratio, decoded blurhash placeholder); now also generated (`ImetaTag.toTag()`) for composer attachments — url/mime/dim/blurhash/x/size/alt, encoded blurhash included; not yet used to detect extensionless media URLs the regex scan would otherwise miss. |
| 94 | File Metadata | YES | NO | No dedicated file metadata event flow. |
| 98 | HTTP Auth | NO | NO | Not needed in current social client flow. |
| 99 | Classified Listings | NO | NO | Marketplace scope excluded. |
| A0 | Voice Messages | YES | NO | No voice-message flow. |
| A4 | Public Messages | YES | PARTIAL | `domain/nipa4` adds a `publicMessage()` builder (p-tagged, no `e` tags per spec) and a parser; no compose/notification UI surfaces kind `24` yet. |
| B0 | Web Bookmarks | YES | NO | No bookmark event flow. |
| B7 | Blossom | YES | YES | BUD-01 (`GET`/`HEAD /<sha256>`), BUD-02 (upload), BUD-03 (`kind:10063` user server list — publish/hydrate/client-upload/client-retrieval-fallback), BUD-04 (mirror), BUD-06 (`HEAD /upload`), BUD-11 (scoped auth tokens), and BUD-12 (list/delete) all implemented (`domain/nipb7`, `MediaUploadRepositoryImpl`, `ui/blossom`). Default server: `nostr.download`. Wired into profile picture/banner upload, composer note attachments (gallery pick + keyboard-inserted GIF/image via `Modifier.contentReceiver`), and inline note-image rendering — every upload path shares one `MediaUploadDialog`/`UploadBlossomBlobUseCase`. |
| C0 | Code Snippets | NO | NO | Not social-feed priority. |
| C7 | Chats | YES | PARTIAL | `domain/nipc7` adds a `chatMessage()` builder (flat stream, `q`-tag quote-reply instead of threading `e` tags) and a parser; no chat UI yet. |
| F4 | Podcasts | NO | NO | Out of current scope. |

## TODO backlog

See [nip-priority-roadmap.md](nip-priority-roadmap.md) for how these are sequenced/prioritized —
this section keeps the concrete blocker/context for each instead of duplicating that ordering.

Protocol-side ("domain side") completeness was the goal of this pass — every NIP below now has
at least a builder/parser in its `domain/nipXX` package even where no UI consumes it yet, per an
explicit product decision to finish the NIP layer before starting UI work or push notifications.

### On hold (explicitly deferred, not started this pass)

- **NIP-29** (Relay-based Groups) — a large feature (group membership, admin events, moderation);
  deliberately left untouched.

### UI-sized follow-ups (domain side is done or adequate; needs feed/compose/screen work)

- **NIP-68** (kind 20 picture-first posts) — `domain/nip68` parses title/images/description/
  content-warning, but kind 20 isn't in any feed subscription filter and has no gallery UI.
  Widening feed kinds touches counting maps and persistence-eligibility checks that are
  currently sized for kind 1 specifically (`EventRepositoryImpl` lines around `counts[1]`,
  `isUsefulClientNote`) — needs its own pass, not a "small effort" add-on.
- **NIP-A4** (kind 24 public messages) and **NIP-C7** (kind 9 chats) — builders/parsers exist
  (`domain/nipa4`, `domain/nipc7`); no compose UI, no notification-screen rendering, no relay
  subscription requests either kind.
- **NIP-22** (comments) / **NIP-7D** (forum threads) — full tag-shape support and builders exist;
  nothing in the compose or thread UI calls them yet.
- **NIP-51** remaining lists (bookmarks `10003`, communities `10004`, blocked relays `10006`,
  search relays `10007`, interests `10015`) — builder/parser only; no repository, no Settings UI,
  since nothing consumes them yet. Full `30000`/`30003`/`30015` addressable "sets" variants are
  still entirely unimplemented.
- **NIP-36** (content warnings) — reading/hiding is wired into the feed filter; composing a note
  with a content warning has no UI toggle yet.

### Large, not attempted this pass

- **NIP-46** real remote signing (`bunker://`, kind 24133) — Umbra's Amber integration uses
  NIP-55 local intents, architecturally unrelated; a real NIP-46 remote-signer relationship would
  be a separate signing backend, not a small addition.
- **NIP-57** Lightning Zaps — kinds observed only, no payment/zap-request UX.
- **NIP-58** Badges — kind constants only.
- **NIP-59** Gift Wrap — kind constants only; NIP-17 DMs (which depend on it) are still ~0% built
  beyond kind constants.
- **NIP-17** Private Direct Messages — kinds/relay-list (`10050`) modeled, full DM UX (including
  the NIP-59 gift-wrap dependency above) not started.

### Smaller remaining gaps

- NIP-45 COUNT: broader UI rollout (per-relay reaction/reply counts) beyond profile note/follower
  counts.
- NIP-44: cryptographic payload pipeline beyond the envelope model.
- NIP-92: `imeta` not yet used to detect extensionless media URLs the regex scan would otherwise
  miss.
- NIP-66 relay liveness/discovery — no event flow yet.
