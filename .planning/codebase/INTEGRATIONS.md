# External Integrations

**Analysis Date:** 2026-09-02

## APIs & External Services

**Nostr Protocol (Core):**
- Relay WebSocket connections (NIP-01 protocol)
  - Transport: OkHttp WebSocket over SOCKS5 TOR proxy
  - Implementation: `data/nostr/NostrClient.kt`, `data/nostr/NostrSessionManager.kt`
  - All relay endpoints routed through single `@Named("tor") OkHttpClient` from `data/di/NetworkModule.kt:59`
  - Relay capacity: supports 100+ concurrent relay connections via configurable connection pool

**Nostr NIP Implementations:**
- NIP-01: Base Protocol - Event subscription/publication
- NIP-02: Contact List - `domain/nip02/**`
- NIP-05: DNS ID Verification - `data/repository/Nip05RepositoryImpl.kt`, `domain/nip05/**`
  - Fetches `.well-known/nostr.json` from user-provided domains via TOR
  - Caches verification state (24h verified, 15m failed) in encrypted SharedPreferences
- NIP-11: Relay Information - `data/repository/RelayInfoRepositoryImpl.kt`, `domain/nip11/**`
  - Fetches relay server capabilities via HTTP GET to `{relay_url}/api/v0`
  - Bounded timeout (60s call, 45s connect) to prevent hangs
- NIP-17: Private Direct Messages - `domain/nip17/**`
- NIP-18: Reposts - `domain/nip18/**`
- NIP-19: Bech32 Encoding - `domain/nip19/**` (npub/nprofile/note/nevent/naddr)
- NIP-21: nostr: URI Scheme - Deep links via Intent filter in `AndroidManifest.xml:66-72`
- NIP-22: Event Created At Limits - `domain/nip22/**`
- NIP-25: Reactions/Emoji - `domain/nip25/**`, `data/repository/ReactionEmojiRepositoryImpl.kt`
- NIP-30: Emoji Alias Sets - `domain/nip30/**`
- NIP-36: Sensitive Content - `domain/nip36/**`
- NIP-44: Encrypted Payloads (ChaCha20-Poly1305) - `domain/nip44/**`
  - Gateway: `domain/nip55/AmberSignerGateway` (delegates to Amber app)
- NIP-45: Event Counts - `domain/nip45/**`
- NIP-51: User Sets (mute/pin lists) - `domain/nip51/**`, NIP-44 encrypted via Amber
- NIP-55: Amber Signer (external key management) - `data/amber/**`, `domain/nip55/**`
  - See "Authentication & Identity" section below
- NIP-65: Relay Lists - `domain/nip65/**`, `data/repository/RelayListDecryptionCoordinator.kt`
- NIP-67: Relay-Originated Search - `domain/nip67/**`
- NIP-68: Relay-Originated Events - `domain/nip68/**`
- NIP-77: Negentropy Sync - `domain/nip77/**`, `data/repository/NegentropySyncOrchestrator.kt`
  - Efficient event set reconciliation protocol
- NIP-92: Media Attachments - `domain/nip92/**`
- NIP-A4: Blossom Media Upload - `domain/nipb7/**`, `data/repository/MediaUploadRepositoryImpl.kt`
- NIP-B7: Blossom File Server - `domain/nipb7/**`
  - Upload: PUT `/upload` with Authorization header
  - List blobs: GET `/list`
  - Delete: DELETE `/blob/{hash}`
  - Mirror: GET `/mirror`
  - Heads: HEAD `/upload` with SHA-256 verification
  - All via TOR-routed OkHttp client, bounded timeout (60s call, 45s write, 30s read)
- NIP-C7: Blossom Deletion - `domain/nipc7/**`

## Data Storage

**Databases:**

Single encrypted Room database (never a second unencrypted one):
- **SQLCipher-encrypted Room database:** `EncryptedUmbraDatabase` in `data/db/**`
  - Location: `app/databases/umbra.db` (on device)
  - Encryption: 256-bit SQLCipher with device-local Android Keystore-backed passphrase (`data/db/EncryptedDatabasePassphraseProvider.kt`)
  - Passphrase: randomly generated on first access, stored in encrypted SharedPreferences backed by Android Keystore (independent of Nostr credentials)
  - DAOs: `EventDao`, `EventTagDao`, `UserProfileDao`, `RelayDao`, `FeedFilterDao`, `ReactionEmojiDao`
  - Journal mode: Write-Ahead Logging (WAL) for performance
  - Initialized in `di/DatabaseModule.kt:35-50`
  - Data: Signed-in user's own events + profile/relay/feed-filter caches for everyone else

**In-Memory Caches:**
- **EventLruCache:** `data/repository/EventLruCache.kt`
  - Non-owned event cache (everyone else's content)
  - Access-order LRU eviction
  - Never persisted to disk (matches Amethyst's pure in-memory pattern)

**File Storage:**
- Image cache: `context.cacheDir/image_cache` (Coil, 512 MB max size)
- No persistent file-based storage for events or secrets

**Caching:**
- NIP-05 verification cache: encrypted SharedPreferences (`umbra_nip05_cache`)
  - TTL: 24h verified, 15m failed
- Image memory cache: Coil, 25% of heap
- Image disk cache: Coil, 512 MB LRU

## Authentication & Identity

**Auth Provider:** Amber (external non-custodial signer)
- Package: `com.greenart7c3.nostrsigner` (official greenart7c3 fork)
- Protocol: NIP-55 (`domain/nip55/AmberSignerGateway.kt`, `data/amber/AmberConnector.kt`)
- Implementation: `data/amber/AmberSignerGatewayImpl.kt`, `data/amber/AmberConnector.kt`, `data/amber/AmberRequestCoordinator.kt`

**Signing Flows:**
1. **ContentResolver path** (preferred when pre-approved) — synchronous IPC, no UI
   - ContentProvider URI: `content://{amber-package}.SIGN_EVENT`
   - Returns signed event JSON or null (falls back to Intent flow)
2. **Intent path** (for new permissions/rejections) — shows Amber's UI for approval
   - Intent action: `com.greenart7c3.nostrsigner.SIGN_EVENT`
   - Request/response via Android Intents with extras (eventJson, npub, etc.)

**Supported Operations:**
- `get_public_key` - Retrieve signing account's pubkey
- `sign_event` - Sign Nostr event with private key (BIP-340 Schnorr)
- `nip44_encrypt` - ChaCha20-Poly1305 encryption with Amber's key
- `nip44_decrypt` - ChaCha20-Poly1305 decryption with Amber's key

**Key Storage:** Amber handles all private key material (no `nsec` on device)

**Permission Model:** Per-event-kind approve/reject policies managed by Amber

## TOR Proxy

**Runtime Configuration:**
- Proxy: Orbot app (`org.torproject.android`)
- Endpoint: SOCKS5 at `127.0.0.1:9050` (configurable, validated in `TorProxyConfig.kt`)
- Connection check: `data/nostr/OrBotConnectivityCheck.kt`

**Configuration:**
- Host: `TorProxyConfig.host` (default `127.0.0.1`, allows loopback only)
- Port: `TorProxyConfig.port` (default `9050`, allows 9050/9150)
- Status: `TorProxyConfig.isReady` (false until Orbot broadcasts STATUS=ON)
- Update: `TorProxyConfig.update(host, port)` called by Tor gate UI when Orbot status changes

**Enforcement:**
- OkHttpClient `ProxySelector` routes all traffic through configured endpoint (`NetworkModule.kt:79-88`)
- DNS resolution delegated to SOCKS proxy (no local DNS lookup — `.onion` hostnames never touch system DNS)
- WebSocket connections use same proxy as HTTP
- No fallback to plaintext or bypass — TOR gate screens all network until proxy is ready

## Monitoring & Observability

**Error Tracking:** None (no external error reporting service)

**Logs:**
- Approach: `UmbraLog` (tag-based wrapper around `android.util.Log`)
- Scrubbing: Relay URLs, pubkeys, event/profile content redacted in release builds via `LogScrubber.kt`
- Gating: Logs only emitted if `Log.isLoggable(tag, LEVEL)` returns true (avoids allocation in release)
- Location: Logcat (standard Android logging)

**Metrics:** None (no telemetry or analytics)

## CI/CD & Deployment

**Hosting:** App distribution outside Play Store (F-Droid, direct APK, Zapstore)
- No Play Store constraints applied
- Permissions not restricted by Play policy (e.g., `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` used for legitimate keepalive)

**CI Pipeline:** GitHub Actions (`.github/workflows/android-ci.yml`)
- Trigger: Every push to `master` and all pull requests
- Steps:
  1. Checkout code
  2. Setup JDK 17 (Temurin distribution)
  3. Setup Gradle
  4. Run lint, unit tests, benchmark APK assembly
  5. Upload benchmark APK as artifact (uncompressed, raw `.apk`)
- Runs: `lintDebug`, `testDebugUnitTest`, `assembleBenchmark`
- On failure: Build fails; PR cannot be merged until CI passes

**Release Pipeline:** `.github/workflows/android-release.yml` (exists but not detailed in this analysis)

## Environment Configuration

**Required Environment Variables:** None hardcoded; all configuration via UI or runtime discovery

**Runtime Configuration:**
- TOR proxy: Discovered from Orbot's STATUS broadcast, user can override in Tor gate screen
- Relay list: Persisted in Room database, synced from NIP-65 lists
- Feed filters: User-editable in `FeedConfigScreen` (mutes, NSFW, content filters)
- Signing account: Selected from Amber's available keys

**Secrets Location:**
- Private keys: Never on device — held exclusively by Amber
- Database passphrase: Android Keystore-backed encrypted SharedPreferences
- Auth tokens: Not applicable (Nostr uses no centralized auth tokens)

## Webhooks & Callbacks

**Incoming Webhooks:** None

**Outgoing Webhooks:** None

**Deep Links:**
- NIP-21 `nostr:` URI scheme: `nostr://npub/nprofile/note/nevent/naddr` handled via Intent filter (`AndroidManifest.xml:67-72`)
- Examples: `nostr://npub1...`, `nostr://nprofile1...`

## Network Configuration

**Network Security:**
- Base policy: HTTPS required (`network_security_config.xml:5`)
- Localhost exemption: Cleartext allowed for `127.0.0.1`, `localhost`, `[::1]` (testing, Orbot)
- `.onion` exemption: Cleartext allowed for all `*.onion` domains (Tor hidden services)
- TLS: Standard Android CA trust store (no custom certs, no pinning, no trust-all overrides)
- DNS: Delegated to SOCKS5 proxy (no local resolution)

**User-Agent & Headers:**
- Standard OkHttp defaults (no custom User-Agent to avoid relay fingerprinting)
- Relay auth: NIP-42 AUTH events carry signed challenge/response

---

*Integration audit: 2026-09-02*
