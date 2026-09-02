# Privacy Policy

**Last updated:** May 2026

## Overview

Umbra is a privacy-first Nostr client. This document explains what data is collected, how it is stored, and what guarantees we provide.

## Data Collection and Storage

### What Umbra stores locally

Umbra stores the following data **exclusively on your device** in an encrypted SQLite database:

- **Events** — Nostr notes you view or author (verified via BIP-340 Schnorr signature)
- **Profiles** — User metadata (names, pictures, NIP-05 identifiers)
- **Relay list** — Which Nostr relays you connect to
- **Feed filters** — Your feed configuration and preferences
- **Relay metadata** — Information about relays (name, supported NIPs, contact)

All data is stored in Android's private app database directory and is inaccessible to other applications.

### What Umbra does NOT store

The following data is **never** stored or persisted:

- **Private keys (`nsec`)** — Not stored anywhere on device. Signing is delegated to Amber.
- **Full pubkeys in logs** — Only truncated (first 8 hex chars) appear in debug logs
- **Note content in logs** — Event `content` field is never logged
- **Relay URLs in logs** — Relay hostnames are scrubbed (`[relay]`)
- **User authentication tokens** — No tokens exist; anonymous or Amber-based auth only

### Encrypted storage for sensitive events

If you are logged in as a user:
- Events you author are stored in an **encrypted database partition** that cannot be accessed by relay observers
- Public events are stored separately for feed browsing
- Draft signable content and user-authored archive data stay in the encrypted app state; the complete user-to-user DM encryption flow remains a roadmap item rather than a settled shipping guarantee

## Network Traffic

### All network goes through TOR

- Every network request (HTTP, WebSocket, image loading, video streaming) routes through Orbot's SOCKS5 proxy at `127.0.0.1:9050`
- **No fallback:** If Tor is not running, Umbra makes no network connections
- **No DNS leaks:** Hostnames are resolved inside the Tor network, not on your device
- Relay operators see only a Tor exit node IP, never your real IP

### What relays observe

When you connect to a Nostr relay through Umbra:
- Relay sees your events and subscriptions (this is how Nostr works)
- Relay does NOT see your IP address (it sees a Tor exit node)
- Relay does NOT see your device identifier or browser fingerprint
- If you use multiple relays, they cannot correlate your activity across them without your explicit events

## Threat Model

### What Umbra protects against

- **IP-based identification** — Your real IP is hidden from relays
- **ISP observation** — Your Nostr activity is hidden from network providers
- **Device fingerprinting** — No user agent, persistent cookies, or device IDs
- **Plaintext eavesdropping** — All relay connections use TLS over Tor
- **Key compromise on device** — Private keys are never stored; only Amber has them
- **App-imposed censorship** — Umbra never hides content on your behalf. Muting, NSFW hiding, and feed content filters are your own editable, fully removable settings (`domain/feed/FeedFilter.kt`) with sensible defaults, not fixed app-side rules — nothing is hardcoded or unremovable

### What Umbra does NOT protect against

- **Event content analysis** — Your public notes are visible on the relay (encryption is at application level, not Nostr protocol level)
- **Relay operator attacks** — A relay operator who controls the relay can log when you were online, but not your IP
- **Quantum computing** — Nostr uses secp256k1; quantum breaks ECDSA. This affects all Nostr clients equally.
- **Malware on your device** — If your Android device is compromised, an attacker has access to all data and network traffic
- **Compromised Amber** — If Amber is backdoored, it can steal your keys or sign malicious events
- **Moderation by relays or other users** — A relay can refuse to store or serve your notes, and other clients/users can mute or ignore you; Umbra doesn't control the wider Nostr network, only what it does with the data it receives

## Third-party services

Umbra does not use:
- Analytics services (Google Analytics, Mixpanel, etc.)
- Crash reporting services (Sentry, Firebase Crashlytics, etc.)
- CDNs for app updates
- Cloud backups

All crash reports and error handling happen locally.

## Media and Images

- Images and videos are loaded through the Tor proxy
- Image URLs are embedded in Nostr events; Umbra does not scrape or mirror them
- Media is cached locally on your device, not sent to any server

## Permissions

Umbra requests the following Android permissions:

- **INTERNET** — Required for Nostr relay connections
- **CHANGE_NETWORK_STATE** — Required to check Tor connectivity status (via Orbot)

Umbra does NOT request:
- `READ_CONTACTS` — You manage follows manually
- `CAMERA` — No video recording or streaming
- `MICROPHONE` — No audio recording
- `LOCATION` — Never requested or used
- `READ_CALENDAR` or `READ_SMS` — Never requested

## Data Retention

- **Events** — Stored until you delete them or clear all data
- **Profiles** — Cached for offline viewing; cleared on logout
- **Relay metadata** — Cached for up to 24 hours
- **NIP-05 verification status** — Cached for 24 hours (verified) or 15 minutes (failed transient verification)

You can delete all app data at any time via **Settings > Apps > Umbra > Storage > Clear Data**.

## Open Source

Umbra is open source under the [MIT license](LICENSE). You can audit the source code at [github.com/deymosh/umbra](https://github.com/deymosh/umbra).

Security-critical code is audited via:
- Automated tests (`testDebugUnitTest`)
- Manual code review (see [AUDIT.md](AUDIT.md))
- Community review on GitHub

## Changes to this policy

If we make material changes to this privacy policy, we will:
1. Update this document and commit to git
2. Increment the version tag
3. Notify users in the app (if applicable)

## Questions?

For privacy concerns or questions, open an issue on GitHub or contact the maintainers privately (see [SECURITY.md](SECURITY.md)).
