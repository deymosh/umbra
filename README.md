<div align="center">

<img src="docs/logo.svg" alt="Umbra" width="112" height="112">

# Umbra

**Privacy-first, censorship-resistant Nostr client for Android — all traffic
routed through TOR. No exceptions. Moderation is always yours to control.**

[![CI](https://github.com/deymosh/umbra/actions/workflows/android-ci.yml/badge.svg)](https://github.com/deymosh/umbra/actions/workflows/android-ci.yml)
[![latest release](https://img.shields.io/github/v/release/deymosh/umbra?sort=semver&label=release)](https://github.com/deymosh/umbra/releases/latest)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

</div>

Umbra connects to the Nostr network exclusively via Orbot's SOCKS5 proxy. If
Tor isn't running, the app makes no network connections — no fallback, no
plaintext leaks.

---

## Table of Contents

- [Why](#why)
- [Features](#features)
- [How it works](#how-it-works)
- [Stack](#stack)
- [Repository layout](#repository-layout)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Build (developer)](#build-developer)
- [NIPs supported](#nips-supported)
- [Contributing & security](#contributing--security)
- [Privacy & legal](#privacy--legal)

---

## Why

Most Nostr clients are built for convenience. Umbra is built for people who need privacy — who they follow, what they read, which relays they use, and when they're online should not leak.

The threat model is simple: your IP address reveals identity, location, and habits. Tor hides your IP; Umbra makes minimal other assumptions.

Privacy and censorship resistance are what Nostr is for, and Umbra doesn't compromise on either. Content moderation — muting, NSFW hiding, feed filters — is always something *you* configure, never something the app decides for you. Umbra ships with sensible defaults so a fresh install isn't full of noise, but every default is a plain, editable setting you can change or remove; nothing is hardcoded or forced.

---

## Features

**Feed & reading**

- **Feed** — chronological notes and picture posts (NIP-68) with images, video, hashtags, mentions, replies, reposts, reactions, and thread support
- **Threads & comments** — full reply trees for notes, plus NIP-22 comments rendered and composed for anything that isn't a plain note
- **Hashtag feeds, bookmarks and read later** — follow a tag, save notes to your NIP-51 bookmark list, or keep a private on-device reading queue
- **Notifications** — replies, mentions, reactions, reposts and zaps grouped per note, with an unread marker
- **Profiles** — notes, replies, pictures, pinned notes, counters (NIP-45 COUNT where relays support it) and NIP-05 verification state
- **Search** — search events by content, author, or profile name (NIP-50 relay search)

**Posting, profile & media**

- **Composer** — replies, quotes, mentions, media attachments, a "mark as sensitive" toggle, and drafts that survive leaving the screen
- **Profile editing** — update your own name, about, website, NIP-05, and LUD-16, published via your signer
- **Zaps** — NIP-57 zaps signed by your NIP-55 signer and paid in your own wallet; NIP-A3 payment targets offered when the recipient publishes them
- **Blossom media uploads** — profile/banner and composer media upload path via NIP-B7/Blossom with upload server selection, fallback retrieval, and EXIF stripping before upload
- **Media metadata** — NIP-92 `imeta` generation for uploads, and alt text, aspect ratio, blurhash and extensionless-media detection when rendering

**Privacy & control**

- **Signing** — any installed NIP-55 signer app (Amber suggested); `nsec` never touches Umbra
- **Anonymous mode** — read-only usage without providing identity
- **Relays** — connect to clearnet and .onion relays, always routed through TOR; per-relay details, subscriptions, logs and what each relay stores for you
- **Transparency** — per-relay network usage, app resource usage, and an on-device database inspector
- **Privacy extras** — tracking parameters stripped from opened links, and an optional panic wipe
- **Mute list & feed filters** — mute/unmute users with a dedicated review UI (NIP-51), plus editable feed filters (NSFW, excluded hashtags/tags/content) with sensible-but-removable defaults — moderation is always user-controlled, never enforced by the app
- **NIP-17 / NIP-44 groundwork** — relay-list domain model and partial DM/privacy transport scaffolding is present, though the full encrypted messaging UI is still planned

---

## How it works

```
Your app → Orbot (SOCKS5 :9050) → TOR network → Nostr relay
```

All network traffic (WebSockets, image/video loading, NIP-11 relay queries) uses a single proxied OkHttp client. There is no code path that bypasses the Tor proxy. Relay URLs and sensitive fields are scrubbed from logs in production.

Signing is performed by a NIP-55 signer app (Amber is the suggested one) via Android intents; Umbra never exposes private keys.

---

## Stack

Kotlin · Jetpack Compose · Clean Architecture · Hilt · Room · OkHttp · Media3 · Coil · BouncyCastle

Signature verification uses BIP-340 Schnorr on secp256k1 via BouncyCastle. Events failing verification are dropped before caching.

---

## Repository layout

```
umbra/
├── app/                        # the Android app — single Gradle module, com.umbra.app
│   └── src/main/java/com/umbra/app/
│       ├── domain/             #   pure Kotlin: models, use cases, repository interfaces,
│       │                       #   and one package per NIP (domain/nip01, nip05, …)
│       ├── data/               #   implements domain interfaces: SQLCipher Room, relay
│       │                       #   client, in-memory event cache, Amber connector
│       ├── ui/                 #   Compose screens, viewmodels, reusable components
│       ├── di/                 #   Hilt modules — incl. the single Tor-proxied OkHttp client
│       └── util/
├── docs/                       # NIP coverage detail & roadmap · UI snapshots · release checklist
├── scripts/                    # toolchain installer (Linux: repo-local JDK 21 + Android SDK)
└── .github/workflows/          # android-ci.yml · android-release.yml
```

---

## Requirements

For everyday use:

- Android 8.0+ (API 26)
- Orbot installed and running
- A NIP-55 signer such as Amber (optional) for signing; read-only mode works without it

For building from source:

- JDK 21 and Android SDK 37

---

## Quick start

**Install the app:** grab the APK from the
[latest release](https://github.com/deymosh/umbra/releases/latest) and install
it on your device (Android 8.0+). Install [Orbot](https://guardianproject.info/apps/orbot/)
and make sure it's running — without Tor, Umbra stays offline by design.

**Build from source:**

```bash
git clone https://github.com/deymosh/umbra.git
cd umbra
```

Linux, no local JDK/Android SDK yet: run the bundled toolchain installer — it downloads a repo-local JDK 21 + Android SDK cmdline-tools into `toolchain/` (gitignored, never touches a system-wide install) and writes `local.properties` for you. Safe to re-run; already-installed pieces are skipped.
```bash
scripts/install-toolchain.sh
export JAVA_HOME="$(pwd)/toolchain/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew installDebug
```

Unix/macOS/Linux with an existing JDK 21 + Android SDK:
```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew installDebug
```

Windows:
```powershell
echo "sdk.dir=C:\path\to\Android\Sdk" > local.properties
.\gradlew.bat installDebug
```

For faster iterative Kotlin compile during development:
```bash
./gradlew compileDebugKotlin      # Unix/macOS/Linux
.\gradlew.bat compileDebugKotlin  # Windows
```

If Java isn't on `PATH` on Windows, point the shell at the JDK 21 installation before invoking Gradle:
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21-hotspot"
$env:Path = $env:Path + ";$env:JAVA_HOME\bin"
```

---

## Build (developer)

Requires JDK 21 and Android SDK 37. Use the included Gradle wrapper; do not rely on a system Gradle installation.

---

## NIPs supported

| NIP | Name | Status | Notes |
|-----|------|--------|-------|
| NIP-01 | Basic Event Schema | ✅ Implemented | Core event model, signing, and verification |
| NIP-02 | Follow List | ✅ Implemented | Follow list event handling and repository path |
| NIP-05 | DNS-based Identifiers | ✅ Implemented | Verification with badge display, auto-trigger on profile access |
| NIP-09 | Event Deletion Request | ✅ Implemented | Deletion requests are requested from every feed/profile relay subscription and applied on receipt |
| NIP-10 | Text Notes and Threads | ✅ Implemented | Reply/root markers and thread rendering |
| NIP-11 | Relay Information Document | ✅ Implemented | Fetch relay metadata with caching |
| NIP-17 | Private Direct Messages | ⏳ Partial | DM relay list (`10050`) and the NIP-17 transport model exist; the NIP-44 encryption pipeline, gift-wrap flow, and UI are still pending |
| NIP-18 | Reposts | ✅ Implemented | Event thread and mention rendering |
| NIP-19 | Bech32 Encoding | ✅ Implemented | `npub`/`note` encoding and decoding helpers; link resolution is wired through the resolver stack |
| NIP-21 | `nostr:` URI handling | ✅ Implemented | URI resolution and deep-link routing for profile/thread flows |
| NIP-22 | Comments | ✅ Implemented | Kind-1111 comments render in feeds and threads (root `E`/`A`/`I` scopes, `#E` thread subscription) and are composed when replying to anything that isn't a kind-1 note |
| NIP-25 | Reactions | ✅ Implemented | Reaction event types and engagement flow are in the domain and feed path |
| NIP-27 | Text Note References | ✅ Implemented | Mention/reference parsing and rendering, plus outgoing `p`/`q` tagging on compose |
| NIP-30 | Custom Emoji | ✅ Implemented | `:shortcode:` emoji render in notes and reactions; the composer suggests your own emoji (kind-`10030` list plus referenced `30030` sets) as you type `:` and tags the ones a note uses |
| NIP-36 | Sensitive Content | ✅ Implemented | Reads/builds the `content-warning` tag; wired into the feed's NSFW filter and the composer's "mark as sensitive" toggle |
| NIP-42 | Client Authentication | ✅ Implemented | AUTH challenge/response, with active subscriptions replayed to the relay after a successful login |
| NIP-44 | Encrypted payloads | ⏳ Partial | Envelope model and domain scaffold exist; cryptographic payload pipeline remains incomplete |
| NIP-45 | Counting results | ✅ Implemented | COUNT asked only of relays advertising NIP-45; profile note/follower counts (merged by maximum, never summed) and per-relay "stored for you" counts on Relay details. Threads count from the events they already download. HyperLogLog (`hll`) merging not supported |
| NIP-46 | Nostr Connect | ❌ Not applicable | Umbra signs via **NIP-55** (local Android-intent signing, e.g. Amber), not NIP-46 relay-based remote signing; no NIP-46 code path exists |
| NIP-50 | Search | ✅ Implemented | Full-text event search via relays — a relay-side filter capability negotiated per-relay, not a `domain/nip50` package, since there's no event/tag shape to model |
| NIP-51 | Lists | ⏳ Partial | Mute (`10000`), pin (`10001`) and bookmark (`10003`) lists have repository + UI, edited as deltas so other clients' entries survive; remaining list kinds are builder/parser-only and the addressable sets (`30000`/`30003`/`30015`) are missing |
| NIP-55 | Android Signer Application | ✅ Implemented | Any installed NIP-55 signer (Amber suggested): Android asks which one when several are installed, and every later request goes to the one chosen; `nsec` never touches the device |
| NIP-57 | Lightning Zaps | ✅ Implemented | Zap requests signed by the NIP-55 signer, LNURL-pay over Tor, invoice handed to the user's wallet; receipts counted on notes and grouped in Notifications |
| NIP-65 | Relay List Metadata | ✅ Implemented | Domain model and relay metadata workflow in place |
| NIP-67 | EOSE Completeness Hint | ✅ Implemented | Parses EOSE's optional completeness hint; a `more` hint withholds the feed's per-relay resume watermark instead of assuming full coverage |
| NIP-68 | Picture-first feeds | ✅ Implemented | Kind-20 posts in the home feed and a Pictures tab on profiles, rendered with `imeta` aspect ratio/blurhash/alt; composing kind-20 posts is not offered |
| NIP-77 | Negentropy Syncing | ✅ Implemented | Set-reconciliation sync of the signed-in user's own event history against their write relays, gated on relay NIP-77 support — not a general backfill feature |
| NIP-92 | Media Attachments Metadata | ✅ Implemented | `imeta` parsed and generated; drives alt text, aspect ratio, blurhash, and detection of extensionless image/video URLs |
| NIP-A3 | Payment Targets | ⏳ Partial | Recipients' kind-`10133` targets are fetched and offered in the zap sheet as `payto:` wallet links; editing your own list is pending |
| NIP-7D | Forum Threads | 🕒 Pending | Kind-11 builder/parser only; no feed, thread or compose UI |
| NIP-A4 | Public Messages | 🕒 Pending | Kind-24 builder/parser only; not subscribed to, rendered, or composed |
| NIP-B7 | Blossom media server protocol | ✅ Implemented | Upload/list/delete/mirror fallback support is visible in the upload and profile media flows |
| NIP-C7 | Chats | 🕒 Pending | Kind-9 builder/parser only; no chat UI |

Detailed per-NIP notes live in [docs/nip-social-coverage.md](docs/nip-social-coverage.md); what's coming next is prioritized in [docs/nip-priority-roadmap.md](docs/nip-priority-roadmap.md).

---

## Contributing & security

Before contributing, please read:
- [AUDIT.md](AUDIT.md) — Mandatory security and architecture rules
- [CONTRIBUTING.md](CONTRIBUTING.md) — Development guidelines and PR checklist
- [SECURITY.md](SECURITY.md) — How to report vulnerabilities privately

For questions, open a GitHub issue or discussion.

## Privacy & legal

- [PRIVACY.md](PRIVACY.md) — What data is collected and stored
- [SECURITY.md](SECURITY.md) — Vulnerability reporting
- [CONTRIBUTING.md](CONTRIBUTING.md) — Developer guidelines
- [CHANGELOG.md](CHANGELOG.md) — Version history and roadmap
- **License:** [MIT](LICENSE)
