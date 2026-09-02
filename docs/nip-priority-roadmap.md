# Umbra NIP Priority Roadmap

This roadmap sequences **unimplemented or partial** NIPs only — a fully-shipped NIP is removed
from here once it's `YES` in [docs/nip-social-coverage.md](docs/nip-social-coverage.md), which
remains the source of truth for full status (including what's already done). Don't re-add a NIP
here just because it once appeared — check coverage status first.

## Priority 1 (core social interoperability)

1. NIP-22 Comments — full tag-shape support and a `comment()` builder exist; not yet called from
   any compose/thread UI

*(NIP-01, 02, 05, 10, 11, 18, 19, 25, 65 shipped — removed from this tier.)*

## Priority 2 (network efficiency and relay correctness)

1. NIP-45 Count messages — transport/repository layer complete; broader UI rollout (per-relay
   reaction/reply counts) beyond profile note/follower counts still pending
2. NIP-66 Relay discovery/liveness — no event flow yet

*(NIP-42, 50, 67, 77 shipped — removed from this tier.)*

## Priority 3 (direct messaging and private social)

1. NIP-17 Private direct messages
2. NIP-44 Versioned encrypted payloads
3. NIP-59 Gift wrap
4. NIP-62 Request to vanish

## Priority 4 (rich social features)

1. NIP-30 Custom emoji end-to-end
2. NIP-51 Lists — remaining list types (bookmarks, communities, blocked relays, search relays,
   interests) and the addressable "sets" variants (`30000`/`30003`/`30015`); domain model exists,
   no repository/DI/Settings UI yet
3. NIP-57 Lightning zaps
4. NIP-58 Badges — kind constants only, no badge UX
5. NIP-68 Picture-first feed semantics
6. NIP-71 Video events end-to-end
7. NIP-7D Forum threads UI — domain builder/parser exist, no compose/thread UI
8. NIP-84 Highlights
9. NIP-88 Polls
10. NIP-89 Recommended app handlers
11. NIP-92 Media attachments metadata — `imeta` parsed/generated; not yet used to detect
    extensionless media URLs
12. NIP-A0 Voice messages
13. NIP-A4 Public messages UI — domain builder/parser exist, no compose/notification UI
14. NIP-B0 Web bookmarks
15. NIP-C7 Chats UI — domain builder/parser exist, no chat UI

*(NIP-27 and NIP-36 shipped — removed from this tier.)*

## Deferred (large features, explicitly on hold)

- NIP-29 Relay-based Groups — group membership, admin events, and moderation are a large feature;
  kind constants exist but the group UX/controls are deliberately untouched. Not sequenced into a
  priority tier above until a decision is made to pick it up.

## Platform and distribution roadmap

Non-NIP product/platform items, tracked separately from protocol-sequencing priorities above:

- Push notifications (via relay subscription hints)
- F-Droid packaging and distribution
- Google Play Store release (requires legal privacy policy)
- Light theme
- Internationalization (i18n)
- Hardware key support (YubiKey, NFC)
- Biometric signing
- Desktop client (TBD)

## Notes

- Out-of-scope NIPs for Umbra social client are tracked as `NO` in [docs/nip-social-coverage.md](docs/nip-social-coverage.md).
- Any new NIP work must preserve TOR-only transport and Amber-only signing constraints from [AUDIT.md](AUDIT.md).
