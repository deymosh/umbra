# Umbra — Master Audit Reference

> Complete reference for all security, privacy, architecture, performance, and UI audits.
> Every audit and fix prompt must reference this document.
> Last updated: 2026-09-02 — verified against the live codebase, not just hand-edited; re-verify Part 5.1/Part 6 against the actual directory tree at the same time as any other change here, since those are the sections most likely to drift.

---

## Project identity

Umbra is a Nostr client for Android. Its defining constraints are that **all network traffic routes through TOR via Orbot SOCKS5 proxy at 127.0.0.1:9050 — no exceptions, ever**, and that **all content moderation is performed by the user, never enforced by the app** — Umbra is privacy-first and censorship-resistant, matching what Nostr as a protocol is designed to guarantee. It signs events exclusively through Amber (external signer). No private keys are stored on-device in any form.

The moderation constraint: muting, NSFW hiding, and feed content filters exist as ordinary user-owned `FeedFilter` state (`domain/feed/FeedFilter.kt`/`FilterDefaults.kt`), editable and fully removable from `FeedConfigScreen`. Defaults exist for a clean out-of-box experience (a starter set of excluded noise hashtags/tags, NSFW hidden by default) but every one of them is a normal editable entry, not a hardcoded app-side rule — a reviewer should flag any new content-hiding logic that isn't built the same way.

Stack: Kotlin 2.4.10 · Jetpack Compose · MVVM + Clean Architecture · Hilt · Room · OkHttp 5.4.0 · Media3 1.11.0 · Coil 3.5.0 · kotlinx.serialization · BouncyCastle 1.85.2
Build: AGP 9.3.1 · Gradle 9.x · JDK 17 · compileSdk 37 · minSdk 26 · jvmTarget 17

---

## Part 1 — Security and privacy (highest priority)

### 1.1 TOR / network enforcement

Every network request without exception — HTTP, WebSocket, image loading (Coil), video streaming (Media3/ExoPlayer), file downloads (DownloadManager is forbidden — use OkHttp with proxy), DNS resolution — must go through the single `@Named("tor") OkHttpClient` singleton defined in `NetworkModule`.

**Rules:**
- `OkHttpClient.Builder()` appears ONLY in `NetworkModule` — everywhere else use `torClient.newBuilder()` which inherits the proxy
- `Proxy.Type.SOCKS` is mandatory — resolves hostnames remotely inside TOR, preventing DNS leaks
- `.onion` hostnames must never touch system DNS
- `ws://*.onion` uses `ConnectionSpec.CLEARTEXT` (TOR provides end-to-end encryption)
- `wss://*.onion` uses default OkHttp TLS — no trust-all overrides, no custom hostname verifiers
- No `HttpURLConnection`, `URLConnection`, or `InetAddress.getByName()` anywhere
- No `DownloadManager` — downloads must go through the proxied OkHttpClient
- No new `ImageLoader` instantiation — always use the app-global Coil ImageLoader from Hilt
- `TorProxyConfig.isReady` must be checked before any network call in any ViewModel or repository
- Coil `ImageLoader` must use the `@Named("tor")` client
- Media3 `OkHttpDataSource.Factory` must use the `@Named("tor")` client
- All WebSocket connections to Nostr relays must use the `@Named("tor")` client

**What to flag:**
- Any `new OkHttpClient()` or `OkHttpClient.Builder()` outside `NetworkModule`
- Any ViewModel injecting `OkHttpClient` directly
- Any network call without a `TorProxyConfig.isReady` guard
- Any `InetAddress.getByName()` or DNS resolution before proxy
- Any `sslSocketFactory` override or `hostnameVerifier { true }` block
- Any `DownloadManager` usage
- Any `ImageLoader.Builder()` outside `NetworkModule`

### 1.2 Key management

- `nsec` (Nostr private key) must never appear anywhere — not in code, not in logs, not in state, not in UI
- `npub` (public key) stored only in `SecurePreferences` (Android Keystore + AES/GCM)
- `SecurePreferences` uses per-instance key alias: `"umbra_prefs_${name}"`
- Signing is exclusively through `AmberSignerGateway` — no in-app Schnorr signing of user events
- `ANONYMOUS_PUBKEY` = 64 zeros — read-only sentinel, not a real keypair
- `canSignWithAmber()` is the only gate for any write action (publish, react, repost)
- No keypair generation for anonymous mode

**Allowed nsec references (do not flag):**
- `Bech32Encoder.kt` — string literal `"nsec"` used only for NIP-19 format detection, never to store or process a private key value
- `LogScrubber.kt` — string literal `"nsec"` used only to detect and scrub private keys from log output. This IS the protection layer.
- Any code that checks `if (input.startsWith("nsec1"))` to REJECT or WARN — correct behavior

**What to flag:**
- Any string matching `[0-9a-f]{64}` used as a default or fallback key
- Any `nsec` literal or variable name in production code outside the allowed files above
- Any signing logic outside `AmberSignerGateway`
- Any `SharedPreferences` (unencrypted) storing keys or pubkeys

### 1.3 Log scrubbing

All logs must be safe in release builds.

**Rules:**
- `scrubRelay()` returns `"[relay]"` in release, may show truncated URL (max 24 chars) in debug
- Every log containing relay URLs, pubkeys, IP addresses, or user data must be gated — gating is
  performed by `util/logging/Logger` (which wraps `Log.isLoggable` internally), not by a
  hand-written check at the call site. Call sites obtain a pre-tagged instance via
  `UmbraLog.tag(TAG)` and emit through `logger.d { }` / `logger.w { }` / `logger.e(throwable) { }`;
  the message lambda is only evaluated when the level is loggable, so there is no per-call-site
  `if (Log.isLoggable(...))` guard left to write or forget
- Profile fields (`name`, `picture`, `nip05`, `about`, `lud16`) must never appear in logs
- Event `content` must never appear in logs
- Full `pubkey` (64 hex chars) must never appear in logs — at most `pubkey.take(8)` in debug only
- Log `"Profile updated"` not `"Profile updated: ${profile.name}"`
- Log `"Event received"` not `"Event from ${event.pubkey}: ${event.content}"`
- The project has `LogScrubber.kt` with `scrubUrlForLogs`, `scrubPubkeyForLogs`, `scrubThrowableMessageForLogs`, `scrubThrowableForLogs` — use these helpers consistently. `Logger.e(throwable) { }` scrubs both halves automatically: the message via `LogScrubber.scrubThrowableMessageForLogs`, and the `Throwable` object itself via `LogScrubber.scrubThrowableForLogs` (a replacement throwable with a scrubbed message and the original stack frames but no cause) before it ever reaches `Log.e`'s own stack-trace formatting — `Log.e(tag, msg, throwable)` reprints the *throwable's* unscrubbed `toString()`/cause chain independent of `msg`, so passing the original object through would leak whatever the message scrub had just redacted. Every other message — `logger.d { }`/`logger.w { }`, and the non-throwable part of a `logger.e` message — still requires an explicit `scrub*ForLogs` call, since the utility only scrubs what it's explicitly told to

**What to flag:**
- Any `Log.*` call printing relay URL without `Log.isLoggable` gate
- Any `Log.*` call printing profile fields
- Any `Log.*` call printing full pubkey or event content
- Inconsistent scrubbing — some logs using helpers and others not
- A direct `android.util.Log`/`Log.*` call anywhere outside `util/logging/Logger.kt` — every other
  call site must go through `UmbraLog.tag(TAG)` and the `Logger`/`UmbraLogger` API instead; this is
  what keeps the logging migration from eroding one file at a time

### 1.4 External URL handling

Every URL opened in an external browser must show `ExternalUrlWarningDialog` first, warning the user their real IP may be exposed.

**Rules:**
- `ExternalUrlWarningDialog` component exists in `ui/components/` — use it everywhere
- Pattern: `var pendingExternalUrl by remember { mutableStateOf<String?>(null) }` + dialog on non-null
- Exception: Amber signer intents only — not browser navigation
- Orbot install fallback must also show the dialog before opening the store
- `UrlPreviewCard` is the reference implementation — do not modify it

**What to flag:**
- Any `context.startActivity(Intent(Intent.ACTION_VIEW, ...))` without `ExternalUrlWarningDialog`
- Any inline `AlertDialog` duplicating the warning pattern instead of using the component

### 1.5 Data storage security

- `SecurePreferences` (Android Keystore AES/GCM) for all sensitive persistent data
- Room database in internal storage (default `Room.databaseBuilder` path — never external storage)
- Single encrypted (SQLCipher) Room database — no second, unencrypted one. Non-owned events aren't persisted to Room at all (in-memory only)
- No `@RawQuery` or `execSQL()` with concatenated user input — all Room queries use bound parameters
- Event content truncated at 64KB before persisting: `MAX_CONTENT_SIZE = 65_536`
- Event tags truncated at 500 max, each value at 1KB: `MAX_TAGS_COUNT = 500`, `MAX_TAG_VALUE_SIZE = 1_024`
- `EventCrypto.verifyEvent(event)` (ID integrity + BIP-340 Schnorr) called before persisting any event
- Events failing verification are dropped silently (debug log only, no content logged)

### 1.6 Media3 / UnstableApi containment

Media3 marks some APIs as `@UnstableApi`. The annotation must NOT propagate up the call chain.

**Rules:**
- `@UnstableApi` or `@file:Suppress("UnstableApiUsage")` appears in ONE file only — the file that directly instantiates `ExoPlayer` or `PlayerView`
- No other file carries `@UnstableApi` or `@OptIn(UnstableApi::class)`
- The unstable API is isolated at the lowest level composable and does not propagate to `NostrTextRenderer`, `EventCard`, `FeedScreen`, or `NavHost`

**What to flag:**
- `@UnstableApi` on any file other than the one directly instantiating ExoPlayer/PlayerView
- `@OptIn(UnstableApi::class)` on screen-level composables or ViewModels

### 1.7 Media uploads

Any client-side media upload (profile picture, future image/video attachments) must strip EXIF
and container metadata (GPS coordinates, device make/model, original timestamp) before the file
leaves the device — a picked photo or video must never carry more than the pixels/frames the
user intended to share. Ported from Amethyst's `service/uploads/MetadataStripper.kt`
(github.com/vitorpamplona/amethyst), which solves the same problem for the same threat model —
see that file for the canonical reference if extending this further.

**Rules:**
- `MediaMetadataStripper.strip(uri, mimeType, context)` (`util/MediaMetadataStripper.kt`) is the
  single entry point; dispatches by mime type to an image or video path
- Images: clear a curated list of sensitive EXIF tags in place via `ExifInterface.setAttribute`
  + `saveAttributes()` — lossless (no recompression, no quality loss, animated GIF/WEBP keep
  their animation), unlike decode-to-`Bitmap`-and-re-encode
- AVIF is inspect-only (its HEIF/ISOBMFF container can't be reliably rewritten by
  `ExifInterface`): passes through unchanged only if verified free of sensitive tags, otherwise
  fails closed
- Video: remuxed via `MediaExtractor`/`MediaMuxer` into a fresh MP4 container — copies codec
  samples as-is (no re-encode, no quality loss) into a muxer that never received the source's
  metadata atoms; the rotation hint is read separately and reapplied since it's a container-level
  property, not part of any track format
- **Fail-closed contract**: `StrippingResult.stripped == false` means the file could not be
  positively confirmed stripped (unsupported format, corrupt file, extraction/mux failure) — the
  caller MUST refuse the upload in that case, never fall back to uploading the original
  unstripped. See `EditProfileViewModel.onPictureMetadataStripFailed()` / `EditProfileScreen.kt`
  for the reference call site
- Use `androidx.exifinterface.media.ExifInterface`, never `android.media.ExifInterface` — lint
  (`ExifInterface` check) already enforces this project-wide, the platform class has known bugs
- Called on the picked `Uri` before any bytes are handed to an upload repository — never upload
  bytes read directly from the original picked `Uri`
- Uploads themselves route through the same `@Named("tor") OkHttpClient` as everything else (see
  1.1) — `MediaUploadRepositoryImpl` is not an exception to the single-client rule

**What to flag:**
- Raw picked-file bytes passed to an upload call without going through `MediaMetadataStripper`
- Any code path that uploads a file when `StrippingResult.stripped == false`
- A new upload path using its own `OkHttpClient` instead of the injected `@Named("tor")` one

---

## Part 2 — Clean Architecture (second priority)

### 2.1 Layer boundaries

```
ui/screen → ui/viewmodel → domain/usecase → domain/repository (interface)
                                           → domain/model
data/ → implements domain/repository interfaces
data/ → uses domain/model (maps to/from data entities)
```

**Hard rules:**
- `ui/` ViewModels import only from `domain/`
- `domain/` never imports from `data/` or `ui/`
- `data/` implements `domain/` interfaces and maps to domain models before returning
- No `EventEntity`, `UserProfileEntity`, or any Room/OkHttp type in `domain/`
- No `androidx.room.*` imports in `domain/model/`
- Room DAOs never injected outside `data/repository/`
- `EncryptedUmbraDatabase` only injected in `di/DatabaseModule`

**What to flag:**
- Any `@HiltViewModel` class importing from `com.umbra.app.data.*`
- Any domain model with `android.*` or `androidx.*` imports
- Any DAO injected into a ViewModel or UseCase
- Any `EncryptedUmbraDatabase` injected anywhere outside `DatabaseModule`

**Known, accepted exceptions** (found during a full architecture audit; don't re-flag these as
new findings — they're deliberate tradeoffs, not oversights):
- `domain/nip55/AmberSignerGateway.kt` and `domain/nip44/Nip44Gateway.kt` import `android.content.Intent`
  — Amber's NIP-55 signing and NIP-44 encrypt/decrypt protocols are fundamentally intent-based; the
  domain contract has to name that type to describe what it hands back to the caller. No pure-Kotlin
  abstraction removes this without just hiding the same `Intent` behind a wrapper type for no real
  testability gain.
- `domain/media/MediaDataSourceProvider.kt` / `VideoCacheDataSourceProvider.kt` import Media3's
  `OkHttpDataSource.Factory`/`DataSource.Factory` — same reasoning: these interfaces exist
  specifically to hand ExoPlayer a data source, and ExoPlayer's own API is the Media3 type. An
  app-specific wrapper would just rename the same shape.
- `androidx.compose.runtime.Immutable` imported in a handful of domain model files (`Event.kt`,
  `FeedFilter.kt`, `NoteView.kt`, `UserProfile.kt`, `TorRuntimeState.kt`) — this is a compiler
  annotation with no Android/device dependency (works in a plain JVM unit test), unlike
  `androidx.room.*`/`androidx.media3.*`, which the "no androidx.\* in domain" rule is actually
  aimed at. Kept for the recomposition-stability benefit these classes need as UI state fields.
- `PublishSignedEventUseCase`/`PublishAuthEventUseCase` (`domain/usecase/PublishEventUseCases.kt`)
  and `Bech32Encoder.kt` used to call `android.util.Log` directly from `domain/`. That gap is
  closed: both use cases take a pure-Kotlin `domain/logging/UmbraLogger` port
  by constructor injection, wired via `di/UseCaseModule.kt`; `Bech32Encoder` (a Kotlin `object`,
  not constructor-injectable) holds a `private var logger: UmbraLogger` defaulting to
  `NoOpUmbraLogger`, set exactly once via `Bech32Encoder.setLogger(...)` from
  `UmbraApp.onCreate()`. The Android-backed implementation (`util/logging/Logger`) lives outside
  `domain/` entirely, so `domain/` now has zero `android.util.Log` references — verified by grep
  across the whole directory.

### 2.2 ViewModel design

- State via `StateFlow<UiState>` only — no `LiveData`, no `var` properties for state
- `_state.update { it.copy(...) }` always — never `_state.value = _state.value.copy(...)`
- No `OkHttpClient`, `OkHttpDataSource.Factory`, or network types injected directly into ViewModels
- No `context.startActivity()` — emit `SharedFlow<SideEffect>` and handle in Composable
- No `BroadcastReceiver` in ViewModels — belongs in `TorRuntimeManager` or data layer
- `var onSignEvent` forbidden — use `SharedFlow<Intent>` + `AmberSignEffect` composable
- `@ApplicationContext Context` allowed only if documented with a comment explaining why
- `onCleared()` must cancel all jobs and clear relay channels
- UI state data classes annotated `@Immutable`

**What to flag:**
- `var onSignEvent: ((String) -> Unit)? = null` in any ViewModel
- `_state.value = _state.value.copy(...)` anywhere
- `context.startActivity()` in any ViewModel
- `BroadcastReceiver` registered in any ViewModel
- `OkHttpClient` injected into any ViewModel
- Missing `@Immutable` on UI state data classes

### 2.3 Use case design

- One class = one `operator fun invoke()` with typed parameters
- Return `Result<T>` or `Flow<T>` — never throw exceptions to ViewModel
- No Hilt annotations in `domain/usecase/` — use cases are plain Kotlin classes
- Named `XxxUseCase` (PascalCase, singular)

**What to flag:**
- Any use case with multiple public functions
- Use cases catching and swallowing exceptions without surfacing them
- Hilt annotations (`@Singleton`, `@Inject`) on domain use cases
- `Usecase` (lowercase c) — must be `UseCase`

### 2.4 Repository pattern

- Repository interfaces in `domain/repository/` use only domain model types in signatures
- Repository implementations in `data/repository/` handle all error mapping
- Mappers in `data/db/mapper/` handle all Entity ↔ Domain conversions
- `SecurePreferences` cached as lazy property — never instantiated on every call

**What to flag:**
- Repository interface method returning `EventEntity` or any data layer type
- `SecurePreferences(context, name)` called inside a function (not a lazy property)
- Missing `.toDomain()` mapping before data leaves the data layer

### 2.5 Hilt module organization

- `NetworkModule` — `OkHttpClient`, `ImageLoader`, `OkHttpDataSource.Factory`
- `DatabaseModule` — `EncryptedUmbraDatabase`, `EventDao`, `UserProfileDao`
- `RepositoryModule` — all repository bindings (`@Binds`)
- `MediaModule` — `MediaDataSourceProvider`
- `@Binds` for interface→implementation, not `@Provides` (unless factory logic required)
- No module mixes categories (network + database = wrong)

**What to flag:**
- `@Provides fun provideX(impl: XImpl): X = impl` — should be `@Binds`
- A single module providing both network and database dependencies

### 2.6 Amber integration boundaries

- `AmberConnector` (data layer) only touched from `data/amber/`
- `AmberSignerGateway` (`domain/nip55/`, signing) and `Nip44Gateway` (`domain/nip44/`, encrypt/decrypt)
  are the only abstractions exposed to ViewModels — both implemented by the single `AmberSignerGatewayImpl`
- No `AmberConnector.*` calls directly in Composables or ViewModels
- Amber result handling (`extractSignedEventFromResult`) in ViewModel via `handleSignResult()`, not in Composable
- `AmberSignEffect` composable in `ui/components/` used in all screens that need signing

**What to flag:**
- `AmberConnector.*` imported in any `ui/` file
- Amber intent construction in a Composable
- `extractSignedEventFromResult` called outside a ViewModel

### 2.7 Coroutines

- IO operations on `Dispatchers.IO`
- CPU-heavy work on `Dispatchers.Default`
- `_state.update` never inside `launch(Dispatchers.IO)` — restructure with `withContext`
- All class-level `CoroutineScope` must use `SupervisorJob()`
- Inner class scopes (e.g. `WebSocketListenerImpl`) must be cancelled in `onClosed()` and `onFailure()`
- No `GlobalScope`, no `runBlocking` in production code
- No bare `launch {}` without explicit scope

**What to flag:**
- `_state.update {}` or `_flow.value =` inside `launch(Dispatchers.IO)`
- `CoroutineScope(Job())` without `SupervisorJob()`
- Inner listener classes with `CoroutineScope` that never call `scope.cancel()`
- `runBlocking` outside of tests

---

## Part 3 — Room database

### 3.1 Schema (version 1)

Single physical database, `EncryptedUmbraDatabase` (`umbra_secure.db`, SQLCipher) — there is no
second, unencrypted database. All local state lives here: `events`, `event_tags`, `user_profiles`,
`relays`, `feed_filters`. The passphrase is a device-local random key in Android Keystore-backed
storage (`EncryptedDatabasePassphraseProvider`), not derived from any Nostr credential, so this
database is available even in anonymous/read-only mode (no Amber sign-in needed).

Only the signed-in user's own events are ever inserted into `events`/`event_tags` — see 3.2,
everyone else's content is in-memory only, never persisted.

`events` indices:
- `pubkey`
- `kind`
- `created_at`
- `(kind, created_at)` — composite
- `(pubkey, kind, created_at)` — composite, most important for profile screen

`user_profiles` indices:
- `updatedAt`

WAL journal mode enabled. `fallbackToDestructiveMigration()` as last resort. Real `Migration` objects for every version change.

### 3.2 Integration checklist

- `EventCrypto.verifyEvent(event)` called before every `eventDao.insertEvent()`
- Only the signed-in user's own events are ever inserted (`encryptedEventDao`) — everyone else's
  content lives only in `EventLruCache` (`data/repository/EventLruCache.kt`), an access-order
  in-memory cache re-fetched from relays as needed (`EventRepository.fetchEventById()`) — no
  Room persistence, no periodic cleanup job, matching Amethyst's pure in-memory event graph
- `initialCacheLoaded: CompletableDeferred<Unit>` gates `connectToEnabledRelays()`
- `getNewestTimestampByKind()` used as `since` filter on relay reconnect
- `isFresh(pubkey)` checked before any relay metadata request — skip profiles fresh < 24h
- Batch inserts `insertEvents(List)` for bursts — never loop `insertEvent()` one by one
- `clearCache()` clears only the in-memory `EventLruCache` + engagement index (a manual,
  user-triggered wipe, distinct from the memory-pressure trim below); `clearAllData()` clears
  every table in the single encrypted database (`events`/`event_tags`/`user_profiles`/`relays`/`feed_filters`)
- `clearAll()` in `UserRepository` clears both memory and `userProfileDao.deleteAll()`
- Periodic stale profile cleanup every 24h: `userProfileDao.deleteStaleProfiles(threshold, excludePubkey)` — the signed-in user's own row is passed as `excludePubkey` so it's never swept just for going unedited
- `UmbraApp.onTrimMemory` reacts to real OS memory pressure by clearing Coil's bitmap cache
  (`TRIM_MEMORY_BACKGROUND`+) and, via `TrimMemoryCachesUseCase`, shrinking `EventLruCache`
  (`EventLruCache.trimTo()`, keeps its normal ceiling — see the deferred-`WeakReference` note in
  Part 4.2), sweeping `UserRepositoryImpl`'s stale profile/relay-list entries on demand
  (`pruneStaleData()`, normally a 24h timer), and trimming `OwnerTagSetCache`-backed
  contact/mute/pin lists down to just the signed-in owner (`trimToOwner()`) at
  `TRIM_MEMORY_UI_HIDDEN`+ (light) or `TRIM_MEMORY_BACKGROUND`+ (aggressive). Also exposed as a
  manual "Trim all caches now" action on the App Resource Usage screen.

### 3.3 N+1 prevention

- `userRepository.getProfiles(pubkeys)` not `getProfile(pubkey)` in a loop
- `eventDao.getEventsByIds(ids)` not `getEventById(id)` in a loop

### 3.4 Dispatcher correctness

- All `suspend` DAO calls inside `withContext(Dispatchers.IO)`
- Room `Flow` queries collected normally — do NOT wrap in `launch(Dispatchers.IO)`

**What to flag:**
- `eventDao.insertEvent()` called outside `Dispatchers.IO`
- Loop calling `insertEvent()` instead of `insertEvents(list)`
- `isFresh()` not checked before relay metadata subscription
- `clearAll()` not calling `userProfileDao.deleteAll()`
- Missing `initialCacheLoaded.await()` in `connectToEnabledRelays()`

---

## Part 4 — Performance and Compose

### 4.1 Recomposition

- All UI state data classes: `@Immutable`
- `Map<String, X>` in state is unstable — use `@Immutable` wrapper or `PersistentMap`
- `hiltViewModel()` forbidden inside `LazyColumn` items — pass dependencies as parameters
- `hiltViewModel()` forbidden in reusable components (`EventCard`, `NostrTextRenderer`, etc.)
- `remember(key) { computation }` for all expensive derived values
- `rememberUpdatedState(callback)` for callbacks passed as parameters

### 4.2 Collections and data

- Class-level mutable collections accessed from coroutines: `ConcurrentHashMap`, `CopyOnWriteArrayList`, or `ConcurrentHashMap.newKeySet()`
- `_state.value = _state.value.copy()` is a race condition — always `_state.update { it.copy() }`
- `EventLruCache + Mutex` for bounded, access-order event cache (`data/repository/EventLruCache.kt`) — eviction by recency-of-access, not insertion order, so content a user has scrolled back to survives unrelated live-feed churn
- `sequence { }` for large collection pipelines instead of chained `.filter().map()`

**Considered and deferred — GC/heap-driven event cache (no fixed ceiling):** Amethyst's
`LargeSoftCache` (`WeakReference`-based despite the name — cleared as soon as nothing else holds
a strong ref, not just under memory pressure) was evaluated as a replacement for the fixed-count
`EventLruCache`. Not adopted, because: (1) it only retains usefully when paired with a separate
"pinning" layer (explicit strong refs for follows/bookmarks/active thread/visible feed) that
Amethyst is still building — without it, a raw swap would likely retain *less* than today's fixed
LRU for ordinary scroll-back; (2) it would turn `EventLruCache`'s synchronous `onEvicted` callback
(which `EventRepositoryImpl` depends on to keep `cachedEngagementIndex` in sync) into an
async/best-effort sweep; (3) it would break every deterministic test in `EventLruCacheTest.kt` —
you cannot reliably force GC to reclaim one specific reference and not another in a plain JVM
unit test. Revisit only as its own dedicated effort (pinning layer + eviction rework +
instrumented-device testing for what's no longer JVM-testable), not a quick swap. See
`EventLruCache.kt`'s doc comment for the same note at the code site.

### 4.3 Static resources

- Regex patterns as file-level `private val` constants — never compiled inside functions or composables
- `JsonUtils` singleton — never `Json { }` inline. Available instances:
  - `JsonUtils.NostrJson` — lenient, `ignoreUnknownKeys = true` (relay messages)
  - `JsonUtils.PrettyJson` — pretty print
  - `JsonUtils.PrettyJsonTwoSpace` — pretty print 2-space indent
  - `JsonUtils.CompactJson` — compact serialization (publishing events)
- No `org.json.JSONObject` or `org.json.JSONArray` — use `kotlinx.serialization`
- `Math.random()` forbidden — use `kotlin.random.Random` or `UUID.randomUUID()`

**What to flag:**
- `Json { }` inline anywhere outside `JsonUtils`
- `Regex(...)` inside a `@Composable` function or non-top-level scope
- `hiltViewModel()` in `EventCard`, `NostrTextRenderer`, or `EventContent`
- Missing `@Immutable` on any data class used as ViewModel state

---

## Part 5 — UI components

### 5.1 Existing components (never re-implement)

This is a representative sample of the most-reused, flagship components, not an exhaustive index —
`ui/components/` (plus its `ui/components/media/` subpackage for image/video-specific ones) has
close to 60 files combined; check both directories before adding a new composable, rather than
trusting a hardcoded count here.

| Component | File | Purpose |
|---|---|---|
| `UserAvatar` | `ui/components/media/UserAvatar.kt` | Profile picture + fallback initials, routed through the gated image-load engine |
| `UserIdentityBadge` | `ui/components/UserIdentityBadge.kt` | Name + NIP-05 badge |
| `ActionsBottomSheet` | `ui/components/ActionsBottomSheet.kt` | Per-item action list (pin/mute/copy/delete, ...) as a bottom sheet |
| `ExternalUrlWarningDialog` | `ui/components/ExternalUrlWarningDialog.kt` | TOR warning before external URL |
| `AmberSignEffect` | `ui/components/AmberSignEffect.kt` | Amber signing LaunchedEffect |
| `EmptyState` | `ui/components/EmptyState.kt` | Empty/no-results states |
| `LoadingSpinner` | `ui/components/LoadingSpinner.kt` | All circular progress indicators |
| `SectionHeader` | `ui/components/SectionHeader.kt` | Section titles with optional action |
| `ChipBadge` | `ui/components/ChipBadge.kt` | Hashtags, NIP badges, filter chips |
| `KeyValueCopyRow` | `ui/components/KeyValueCopyRow.kt` | Label + truncated value + copy |
| `ErrorBanner` | `ui/components/ErrorBanner.kt` | Error messages in screens |
| `NostrTextRenderer` | `ui/components/NostrTextRenderer.kt` | Nostr event content rendering entry point (delegates to `TextRenderPrimitives.kt` and `ui/components/media/RenderInlineMediaSegments.kt`) |
| `TimeFormatter` | `ui/components/Formatters.kt` | All timestamp formatting |
| `MenuItemRow` | `ui/components/MenuItemRow.kt` | Icon + title + subtitle rows |
| `GatedImagePainter`/`ImageAttachment`/`ImageGalleryAttachment`/`FullscreenImageViewer` | `ui/components/media/` | Shared gated (`ImageLoadGate`) image-loading engine and its call sites — every image entry point (feed, avatar, banner, gallery, fullscreen) goes through this, not a one-off `AsyncImage` |
| `InlineVideoAttachment`/`FullscreenVideoDialog`/`VideoPlayerController` | `ui/components/media/` | Shared ExoPlayer wrapper; the only files where `@UnstableApi` may appear |

### 5.2 Rules

- If a UI pattern appears in 2+ files: extract to `ui/components/`
- No private reimplementation of existing components
- All user-visible strings via `stringResource(R.string.*)` in Composables
- `UiMessage.Res(R.string.x)` for ViewModel error/status messages
- `UiMessage.ResWithArgs(R.string.x, vararg args)` for formatted messages
- `UiMessage.Literal(text)` only for relay notices and dynamic server messages
- No inline `AlertDialog` for patterns shared across screens — extract as component
- No `android.app.AlertDialog.Builder` — use Material3 `AlertDialog` composable

**What to flag:**
- Any private `@Composable` that duplicates an existing component
- Hardcoded English strings in ViewModel state fields
- `UiMessage.Literal("hardcoded english")` where a string resource exists
- `android.app.AlertDialog` in any Composable

---

## Part 6 — Project structure reference

```
com.umbra.app/ (top level, outside data/domain/ui)
  di/             Hilt modules not scoped under data/ (AuthModule, DatabaseModule, MediaModule,
                   MediaContextModule, RepositoryModule, TorModule, UseCaseModule)
  util/           Android-level utilities (BlurHash, MediaMetadataStripper,
                   BatteryOptimizationHelper, ImagePrefetcher, UrlPrefetcher, MediaLoadPriorityGate,
                   ImageLoadGate) — distinct from domain/util/'s pure-Kotlin helpers
    logging/      Logger, UmbraLog, LogScrubber — Android-backed implementation of
                   domain/logging/UmbraLogger, the single entry point every log call site goes
                   through (`UmbraLog.tag(TAG)`)

data/
  amber/          AmberConnector, AmberSignerGatewayImpl — ONLY place Amber is called
  crypto/         EventCrypto (BIP-340 Schnorr verification)
  db/
    dao/          EventDao, EventTagDao, FeedFilterDao, RelayDao, UserProfileDao
    entities/     EventEntity, EventTagEntity, FeedFilterEntity, RelayEntity, UserProfileEntity
    mapper/       Entity↔Domain mappers (EventMapper, FeedFilterMapper, RelayMapper)
    pojo/         Room @Relation projections (NoteWithProfile)
  di/             NetworkModule (the single TOR OkHttpClient) — the other Hilt modules live in
                   the top-level di/ above, not here
  media/          TorMediaDataSourceProvider, Media3Wrappers
  nostr/          UmbraNostrClient, NostrSessionManager, RelayBackoffPolicy, BoundedEventCache
  preferences/    UserPreferencesImpl
  repository/     All repository implementations
  security/       SecurePreferences (Android Keystore)
  tor/            TorRuntimeManager

domain/
  crypto/         PubkeyUtils
  feed/           FeedFilter, FilterDefaults
  logging/        UmbraLogger — pure-Kotlin logging port implemented by util/logging/Logger;
                   this is what keeps domain/ free of direct android.util.Log calls
  media/          MediaDataSourceProvider interface
  model/          NostrChannels, ChannelPriority, NoteView
  nip01/          Event, NostrEventBuilder, NostrValidation, KindNames — core protocol (NIP-01)
  nip02/          ContactList (follow list)
  nip05/          Nip05Identifier, Nip05VerificationState
  nip11/          RelayInfo (relay information document)
  nip44/          Nip44Gateway interface (encrypt/decrypt) — see nip55/ for the sibling signing gateway
  nip55/          AmberSignerGateway interface (signing) — the only abstraction exposed to ViewModels
  nip17/          DmRelayList
  nip19/          Bech32Encoder
  nip21/          NostrUri (`nostr:` URI resolution)
  nip22/          Comment
  nip25/          ReactionSemantics
  nip30/          CustomEmoji
  nip36/          ContentWarning
  nip44/          Nip44Payload (encrypted payload envelope)
  nip45/          RelayCountResult (COUNT)
  nip51/          MuteList, PinList, BookmarkList, CommunitiesList, BlockedRelaysList,
                   SearchRelaysList, InterestsList
  nip65/          RelayListMetadata
  nip68/          PictureEvent
  nip7d/          ForumThread
  nip92/          ImetaTag
  nipa4/          PublicMessage
  nipb7/          BlossomBlobDescriptor, BlossomHash, UserServerList (BUD-03 kind:10063),
                   BlossomServerUrl (domain/host extraction, sha256-from-URL, BUD-03 client-
                   retrieval fallback candidates, server URL validation)
  nipc7/          ChatMessage
                   (see docs/nip-social-coverage.md for what each NIP number covers and Umbra's
                   implementation status — new NIP work follows this one-package-per-NIP layout
                   rather than scattering across generic model/usecase files)
  nostr/          NostrSessionController — whole-app session lifecycle interface (start/stop),
                   implemented by data/nostr/NostrSessionManager
  preferences/    UserPreferences (StateFlow-backed, re-emits on login/logout)
  profile/        UserProfile
  relay/          Relay, DefaultRelays, RelayIssue/RelayIssueLog, RelayRequestInfo/
                   RelayRequestLedger, SubscriptionType/SubscriptionId, RelayUrlNormalizer,
                   TorCircuitHealthTracker
  repository/     All repository interfaces
  tor/            TorRuntimeController, TorRuntimeState
  usecase/        Individual UseCase classes (plain Kotlin, no Hilt)
  util/           JsonUtils, TrackingTokenSanitizer

ui/
  auth/           LoginScreen, LoginViewModel
  common/         UiMessage sealed class, ImmutableCollections (ImmutableListSnapshot/
                   ImmutableMapSnapshot), InteractionActionsCoordinator — shared
                   sign/publish/mute/pin/delete plumbing manually constructed by both
                   FeedViewModel and ProfileViewModel (see Part 7's coordinator-extraction note)
  components/     All reusable composables (see table above), including the components/media/
                   subpackage (gated image/video engine — GatedImagePainter, ImageAttachment,
                   ImageGalleryAttachment, FullscreenImageViewer, InlineVideoAttachment,
                   FullscreenVideoDialog, VideoPlayerController, UserAvatar,
                   RenderInlineMediaSegments)
  feed/           FeedScreen, FeedViewModel, EventCard, plus manually-constructed collaborators
                   RelayIssueBannerCoordinator, FeedStateMergeCoordinator,
                   FeedEngagementSchedulingCoordinator
  feedconfig/     Feed filter configuration
  profile/        ProfileScreen, ProfileViewModel, plus ProfileObserversCoordinator
  relay/          RelayConfigScreen, RelayDetailsScreen, ActiveSubscriptionsScreen,
                   RelayConfigViewModel, plus RelayCrudCoordinator, RelayListPublishingCoordinator,
                   and the pure computeRelayDerivedState() function (RelayDerivedState.kt)
  settings/       SettingsScreen
  theme/          UmbraTheme + selectable dark color palettes (UmbraThemeOption)
  tor/            TorGateScreen, TorGateViewModel, TorSideEffect
```

---

## Part 7 — Key design decisions (do not revisit)

| Decision | Rationale |
|---|---|
| Amber-only signing | No nsec ever on device — maximum key security |
| Anonymous = 64-zero pubkey sentinel | Simple, no keypair generation needed for read-only |
| Single OkHttpClient singleton | Guarantees all traffic goes through TOR proxy |
| `Proxy.Type.SOCKS` remote DNS | Prevents DNS leaks, enables .onion hostname resolution |
| Android Keystore + AES/GCM | Superior to deprecated EncryptedSharedPreferences |
| BIP-340 Schnorr via BouncyCastle | Real signature verification, no fake accept-all |
| Encrypted Room for own events, in-memory-only for everyone else's | Matches Amethyst's pure in-memory event graph — no public event database to keep in sync, evict, or clean up |
| Single encrypted database, no second unencrypted one | All local state (events, profiles, relays, feed filters) protected at rest; one schema to maintain instead of two kept in sync |
| WAL journal mode | Concurrent reads + writes during event streaming |
| `@Immutable` on all UI state | Prevents unnecessary Compose recomposition |
| `CompletableDeferred` init gate | Cached data shown before relay connection |
| `SharedFlow<Intent>` for Amber | No mutable var callbacks, proper lifecycle |
| `SharedFlow<SideEffect>` for navigation | ViewModels never call startActivity directly |
| `@UnstableApi` isolated at lowest level | Prevents annotation propagating up call chain |
| `DownloadManager` forbidden | Downloads must go through TOR proxy via OkHttp |
| `jvmTarget = "17"` | Must match JDK 17 and compileSdk 37 — do not downgrade to 1.8 |
| AGP 9.3+ required | Needed for compileSdk 37 and Gradle 9.x compatibility |
| User events encrypted archive only | Historical rule, superseded by "single encrypted database" above — there is no public database left to accidentally put them in |
| `DefaultRelays` in `domain/relay/DefaultRelays.kt` | Bootstrap relay list is a domain concern, not tied to any entity |
| Content moderation is `FeedFilter` state, never hardcoded | User-controlled, fully editable/removable defaults — matches Nostr's censorship-resistance guarantee; the app itself never makes an unconditional "hide this" decision |
| Large `ViewModel`/repository files decompose into manually-constructed (never Hilt-injected) collaborator classes, one cluster of related methods per collaborator | Established across `EventRepositoryImpl` (`EventChannelRouting`, `EventIngestCache`), `FeedViewModel`/`ProfileViewModel` (`RelayIssueBannerCoordinator`, `FeedStateMergeCoordinator`, `FeedEngagementSchedulingCoordinator`, `ProfileObserversCoordinator`, shared `InteractionActionsCoordinator`), and `RelayConfigViewModel` (`RelayCrudCoordinator`, `RelayListPublishingCoordinator`) — keeps each extraction independently testable and reviewable without changing the owning class's public contract; a collaborator field must be declared after the owning class's own `_state`/`_uiState` property or Kotlin's forward-property-reference behavior produces an NPE |

---

## Part 8 — What NOT to suggest (ever)

- Storing `nsec` locally in any form
- Direct network connections bypassing TOR
- `LiveData` (project uses `StateFlow` exclusively)
- `Math.random()` — use `kotlin.random.Random`
- `org.json.JSONObject/JSONArray` — use `kotlinx.serialization`
- `runBlocking` in production code
- `GlobalScope`
- DAO injection directly into ViewModels
- New `OkHttpClient` instances outside `NetworkModule`
- New `ImageLoader` instances outside `NetworkModule`
- A content filter/moderation rule that isn't a user-editable `FeedFilter` default (e.g. a hashtag/author/keyword exclusion baked directly into ingestion or display logic with no settings surface)
- `DownloadManager` for any download — use OkHttp with proxy
- `trust-all TLS` or `hostnameVerifier { true }`
- `EncryptedSharedPreferences.create()` or `MasterKey.Builder()` — use `MasterKey(context)` directly
- `hiltViewModel()` in list items or reusable components
- Adding new screens without checking for reusable components first
- Private reimplementation of existing `ui/components/` composables
- `@Suppress` to hide deprecation warnings — fix the deprecated API instead
- Propagating `@UnstableApi` up the call chain — isolate at the lowest level
- Downgrading `jvmTarget` to `"1.8"` as a workaround — fix the root cause instead
- Re-adding a second, unencrypted database ("public"/"non-encrypted" Room DB) for any kind of local state — profiles, relays, feed filters, and events all belong in the single `EncryptedUmbraDatabase`, by design, not by omission
- Persisting non-owned events to Room at all, encrypted or not — they're in-memory only (`EventLruCache`), matching Amethyst

---

## Master audit checklist

Use this as a final pass after every audit session.

### Security / privacy
- [ ] Zero `OkHttpClient.Builder()` outside `NetworkModule`
- [ ] Zero `ImageLoader.Builder()` outside `NetworkModule`
- [ ] Zero `DownloadManager` usage
- [ ] Zero direct connections bypassing SOCKS5 proxy
- [ ] Zero `Intent(ACTION_VIEW)` without `ExternalUrlWarningDialog`
- [ ] Zero sensitive logs without `Log.isLoggable` gate
- [ ] Log scrub helpers (`scrubUrlForLogs`, `scrubPubkeyForLogs`) used consistently
- [ ] `scrubRelay()` returns `"[relay]"` in release builds
- [ ] `TorProxyConfig.isReady` checked before every network call
- [ ] No `nsec` anywhere (except `Bech32Encoder.kt` and `LogScrubber.kt`)
- [ ] No profile fields in any log statement
- [ ] No event content in any log statement
- [ ] `EventCrypto.verifyEvent()` called before every Room persist
- [ ] Non-owned events never inserted into Room at all (in-memory `EventLruCache` only)
- [ ] No second, unencrypted Room database re-introduced
- [ ] All user data in `SecurePreferences` (Keystore-backed)
- [ ] No `RawQuery` or concatenated SQL
- [ ] `@UnstableApi` in ONE file only
- [ ] Media uploads strip EXIF/container metadata via `MediaMetadataStripper` before leaving the device, and fail closed (refuse upload) when `stripped == false`

### Architecture
- [ ] Zero ViewModels importing from `com.umbra.app.data.*`
- [ ] Zero `hiltViewModel()` in non-screen composables
- [ ] Zero `var onSignEvent` — replaced with `SharedFlow<Intent>`
- [ ] Zero `_state.value = _state.value.copy()` — all use `_state.update {}`
- [ ] Zero files with both `@HiltViewModel` and `@Composable`
- [ ] Zero `@Provides` where `@Binds` applies
- [ ] Zero use cases with multiple public functions
- [ ] All use cases named `XxxUseCase` (PascalCase)
- [ ] Zero `AmberConnector.*` calls outside `data/amber/`
- [ ] Zero `context.startActivity()` in ViewModels
- [ ] `DefaultRelays` defined in `domain/relay/DefaultRelays.kt`

### Build
- [ ] `jvmTarget = "17"` in `build.gradle.kts`
- [ ] `compileOptions` set to `JavaVersion.VERSION_17`
- [ ] AGP version ≥ 9.3.0
- [ ] `compileSdk { version = release(37) }`

### Room
- [ ] Verification before every persist
- [ ] `initialCacheLoaded.await()` before relay connection
- [ ] `isFresh()` checked before relay metadata request
- [ ] `getNewestTimestampByKind()` used as `since` on reconnect
- [ ] No N+1 profile or event lookups
- [ ] `clearCache()` clears the in-memory `EventLruCache`; `clearAllData()`/`clearAll()` clear Room (no public `events` table exists to clear)
- [ ] All DAO calls on `Dispatchers.IO`
- [ ] Content ≤ 64KB, tags ≤ 500, tag values ≤ 1KB
- [ ] Batch inserts used for bursts

### Performance
- [ ] All UI state `@Immutable`
- [ ] No `hiltViewModel()` in `LazyColumn` items
- [ ] Regex as file-level constants
- [ ] `JsonUtils` singleton used — no inline `Json {}`
- [ ] `remember(key)` wrapping all expensive computations
- [ ] No `CopyOnWriteArrayList.removeAt(0)` in hot paths

### UI components
- [ ] No reimplementation of existing components
- [ ] No hardcoded English strings in ViewModels or Composables
- [ ] `UiMessage.Res` for all ViewModel errors
- [ ] `AmberSignEffect` used in all signing screens
- [ ] No `android.app.AlertDialog` in Composables