# Changelog

All notable changes to Umbra are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-09-05

### Added

- Feed display with chronological notes
- Profile view with recent notes and metadata
- Relay browser and configuration
- NIP-05 verification with badge display
- Anonymous (read-only) mode without Amber
- Amber signer integration for event publishing
- Thread view and reply composer
- NIP-17 DM relay list support (backend/data model only — messaging UI and NIP-44 encryption pipeline still planned)
- Hashtag and mention rendering
- Image and video preview in feed
- Full-text search with NIP-50 relay support
- Relay metadata caching (NIP-11)
- Dark theme (light theme future work)
- Mute list management UI (NIP-51 kind 10000) — mute/unmute from profile, dedicated "Mutes" tab with review/undo
- Profile editing UI (name, about, website, NIP-05, LUD-16) with Amber-signed kind 0 publish
- Profile picture upload via Blossom (BUD-01/02/03/04/06/11/12 — full client protocol support), with EXIF/metadata stripped before upload, uploading to the user's own kind:10063 server list (default: nostr.download), and a Settings > Media screen to manage that list
- BUD-03 client-retrieval fallback: a broken inline note image is retried against the author's own Blossom servers, then the app default, before giving up
- Composer media attachments: attach images/GIFs from the gallery (multi-select, up to 4) or insert a GIF directly from the soft keyboard (e.g. Gboard); a shared upload-configuration dialog (server picker, alt text, NIP-36 "mark as sensitive" toggle) now appears before every Blossom upload in the app, not just the composer
- Blurhash generation for uploaded images (`BlurHash.encode`, ported from Amethyst's real encoder) — attached alongside dimensions and alt text as outgoing NIP-92 `imeta` tags
- Follower count on profile (NIP-45 COUNT, best-effort across relays that support it)

### Changed

- Improved NIP-05 verification to auto-trigger on profile access
- Optimized event verification with BIP-340 Schnorr
- Enhanced logging scrubber to redact sensitive fields
- Improved Room query performance with proper indexing
- Non-owned events (everyone except the signed-in user) are no longer persisted to a local
  database at all — they live in a larger, smarter in-memory cache and are re-fetched from
  relays as needed, matching how other Nostr clients (e.g. Amethyst) handle this; only the
  signed-in user's own events continue to persist to the encrypted archive
- Removed the second, unencrypted local database — cached profiles, relay list, and feed filter
  settings now live only in the encrypted database alongside the user's own events, so nothing
  local is stored unencrypted on disk
- Default Blossom media server changed from blossom.primal.net to nostr.download; a user with
  their own kind:10063 server list configured now uploads there instead, regardless of default

### Fixed

- NIP-05 badge now appears in feed view (previously only in profile)
- Note content now strips leading/trailing whitespace
- Fixed relay reconnection logic for transient failures
- Corrected Room JOIN queries to include profile verification state
- Deletion requests (NIP-09, kind 5) from other users are now actually requested from relays —
  previously the feed/profile subscriptions never asked relays for kind 5 at all, so a deleted
  post could keep reappearing until the in-memory cache happened to be rebuilt
- A relay requiring NIP-42 AUTH now has its subscriptions replayed after a successful login,
  instead of silently ending up with zero live subscriptions on that relay
- Opening a note (via a link, mention, or notification) that had scrolled out of the recent feed
  cache no longer shows "Note not found" — it now falls back to a one-shot relay lookup
- Scrolling back through feed/thread history no longer risks evicting the very content you just
  scrolled to — the in-memory cache now evicts by recency of access, not insertion order
- Looking up a single cited/quoted event by relay hint no longer waits behind an unrelated burst
  of events from other relays — event verification and caching now run with bounded concurrency
  instead of one at a time on a single coroutine, so a hint relay's response is no longer
  head-of-line-blocked by whatever else happened to be streaming in at the same time
- Exceptions in publish, login/logout, cache-cleanup, and relay-transport code that were
  previously discarded silently, or logged without the underlying exception, now surface at
  error level with the exception attached and its content scrubbed, instead of vanishing without
  a trace
- Closed a set of concurrency and race-condition bugs across relay configuration, session
  management, and event ingestion, where unsynchronized shared state let a relay-role toggle,
  connection retry, or scheduled background job silently overwrite or duplicate another one's
  effect — the affected state now updates through atomic references and per-resource locks
  instead of unguarded read-modify-write sequences
- Fixed several state-correctness gaps: a deletion request for another author's cached
  addressable content (e.g. a long-form post or list) now actually removes it instead of leaving
  a retracted item visible; optimistic mute, pin, and delete actions in the UI now either reflect
  what was actually saved or are rolled back on failure, instead of silently disagreeing with
  what was actually published; and a background write that fails after signing now surfaces to
  the user instead of being discarded
- Retroactively added regression test coverage for a number of the relay-handling,
  event-caching, and session-cleanup fixes above that had previously shipped without a dedicated
  test of their own

### Security

- All network traffic enforced through TOR proxy
- Private keys never stored on device (Amber-only signing)
- Logging scrubber prevents accidental data leaks
- BIP-340 event signature verification before caching
- Relay subscription IDs are now random and content-free — they previously embedded Umbra's
  internal purpose taxonomy (e.g. `outbox-notes-a1b2c3`, or literally the search query typed
  into note search), which handed every relay a readable label for what a given subscription
  was for

## Roadmap

Forward-looking roadmap items live in [docs/nip-priority-roadmap.md](docs/nip-priority-roadmap.md) — this
file is a historical record of what shipped, not a second roadmap.

---

## Security notes

For information about security updates and vulnerability reporting, see [SECURITY.md](SECURITY.md).

All security-critical changes are documented and tested. See [AUDIT.md](AUDIT.md) for architecture guarantees.
