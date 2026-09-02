<!-- refreshed: 2026-09-02 -->
# Architecture

**Analysis Date:** 2026-09-02

## System Overview

Umbra is a privacy-first Nostr client for Android, enforcing TOR-only network traffic and Amber-only signing. The architecture follows strict Clean Architecture principles with unidirectional dependencies across UI, Domain, and Data layers.

```text
┌─────────────────────────────────────────────────────────────────┐
│                      Presentation Layer (UI)                     │
├──────────────────┬──────────────────────┬───────────────────────┤
│   Screens        │    ViewModels         │   Components          │
│   (LoginScreen   │   (@HiltViewModel)    │   (UserAvatar,        │
│    FeedScreen    │   (StateFlow<State>)  │    EventCard,          │
│    ProfileScreen)│   (@Immutable)        │    NostrTextRenderer)  │
│                  │                       │                        │
│   `ui/auth/`     │   `ui/feed/`          │   `ui/components/`    │
│   `ui/feed/`     │   `ui/profile/`       │   `ui/theme/`         │
│   `ui/profile/`  │   `ui/relay/`         │                       │
└──────────────────┴──────────────────────┴───────────────────────┘
         │
         │ injects (domain only)
         ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Domain Layer                                 │
├──────────────────┬──────────────────────┬───────────────────────┤
│  Use Cases       │  Repository          │  Domain Models         │
│  (Plain Kotlin   │  Interfaces          │  (Event, UserProfile,  │
│   classes)       │  (contracts only)    │   FeedFilter,          │
│                  │                      │   Relay, etc.)         │
│ - PublishEvent   │ - EventRepository    │                        │
│ - DeleteNote     │ - UserRepository     │  NIP-specific          │
│ - BootstrapProfile│ - RelayRepository   │  (nip01/, nip05/,      │
│ - DeleteNote     │ - FeedRepository     │   nip55/, nip65/, etc.)│
│                  │ - AmberSignerGateway │                        │
│ `domain/usecase/`│ `domain/repository/` │  `domain/`             │
│                  │ `domain/nipXX/`      │  with JsonUtils        │
└──────────────────┴──────────────────────┴───────────────────────┘
         │
         │ implemented by
         ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Data Layer                                   │
├──────────────────┬──────────────────────┬───────────────────────┤
│  Network         │  Database            │  Implementation        │
│  (NostrClient    │  (Room + SQLCipher)  │  (EventRepositoryImpl  │
│   OkHttpClient   │  - EncryptedUmbra    │   UserRepositoryImpl   │
│   TOR proxy)     │    Database          │   RelayRepositoryImpl  │
│                  │  - EventDao          │   AmberSignerGateway  │
│  `data/nostr/`   │  - UserProfileDao    │   Impl)               │
│  `data/di/`      │  - RelayDao          │                        │
│  (NetworkModule) │  - FeedFilterDao     │  Mappers               │
│                  │                      │  (EventMapper,         │
│                  │  `data/db/`          │   FeedFilterMapper)    │
│                  │                      │                        │
│                  │  Caching             │  `data/repository/`    │
│                  │  (EventLruCache)     │  `data/db/mapper/`     │
│                  │                      │  `data/amber/`         │
└──────────────────┴──────────────────────┴───────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────┐
│   Android Framework & External Services                       │
│   (Context, Hilt, Amber Signer, OkHttp, Coil, Media3, Room) │
└──────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| **MainActivity** | Single entry point, Compose setup, theme provision, deep-link handling (NIP-21 `nostr:` URIs) | `MainActivity.kt` |
| **UmbraApp** | App singleton, dependency prewarm, Coil ImageLoader factory, onTrimMemory response | `UmbraApp.kt` |
| **NavHost** | Central navigation orchestration, screen routing, authentication/TOR gates | `ui/NavHost.kt` |
| **FeedViewModel** | Feed state (events, profiles, engagement), relay management, prefetch coordination | `ui/feed/FeedViewModel.kt` |
| **LoginViewModel** | Authentication state, Amber connectivity, anonymous mode toggle | `ui/auth/LoginViewModel.kt` |
| **EventRepository** | Event ingestion, caching, relay routing, persistence (signed-in user only) | `data/repository/EventRepositoryImpl.kt` |
| **NostrSessionManager** | Relay connection lifecycle, subscription management, relay backoff policy | `data/nostr/NostrSessionManager.kt` |
| **AmberSignerGateway** | Signing contract (domain interface); Amber intent mediation (impl in data/amber) | `domain/nip55/` / `data/amber/` |
| **NetworkModule** | Single `OkHttpClient` with TOR proxy, Coil ImageLoader, Media3 DataSource | `data/di/NetworkModule.kt` |
| **EventLruCache** | Bounded in-memory cache for non-owned events, access-ordered eviction | `data/repository/cache/` |
| **Bech32Encoder** | NIP-19 URI encoding/decoding, pure Kotlin utility with injected logger | `domain/nip19/` |

## Pattern Overview

**Overall:** Strict Clean Architecture with unidirectional dependency flow.

**Key Characteristics:**
- UI layer never imports `data.*`, only `domain.*`
- Domain layer pure Kotlin, never Android/Androidx imports (except compiler annotations)
- State exclusively `StateFlow<@Immutable UiState>`, updated via `_state.update { it.copy(...) }`
- Use cases are plain Kotlin classes, one `operator fun invoke()` per class
- All network traffic routes through single `@Named("tor")` OkHttpClient with SOCKS5 proxy
- Signing exclusively through Amber; no private keys on device
- Persistence: single encrypted Room database for user's own events; everyone else's events cached in-memory only
- All side effects (navigation, Amber signing) via `SharedFlow<SideEffect>`, never direct `startActivity()` or mutable callbacks

## Layers

**Presentation Layer (UI):**
- Purpose: Render UI state, collect user input, emit side effects
- Location: `ui/` (screens, ViewModels, components, theme)
- Contains: Composable functions, `@HiltViewModel` classes, Material3 components
- Depends on: Domain layer only (UseCases, Repositories interfaces, Models)
- Used by: Compose runtime, Navigation framework

**Domain Layer:**
- Purpose: Define business logic, data models, use cases, repository contracts
- Location: `domain/` (usecase, model, repository interfaces, nip-specific packages)
- Contains: Plain Kotlin classes, sealed classes, interfaces, data models
- Depends on: Standard Kotlin library only (kotlinx.serialization, kotlinx.coroutines)
- Used by: UI layer (ViewModels) and Data layer (implementations)
- Note: NIP implementation is organized as `domain/nipXX/` packages, one per NIP

**Data Layer:**
- Purpose: Implement repository contracts, handle persistence, networking, external services
- Location: `data/` (repository implementations, database, network, Amber, caching)
- Contains: Repository implementations, DAOs, Room entities, mappers, network clients
- Depends on: Domain layer (interfaces, models) and Android/third-party libraries
- Used by: Domain layer (through interfaces only)

**Dependency Injection Layer:**
- Purpose: Centralized module/singleton configuration
- Location: `di/` and `data/di/`
- Contains: Hilt modules (`@Module`, `@Provides`, `@Binds`) for scoping and wiring
- Key modules: `AuthModule`, `DatabaseModule`, `NetworkModule`, `RepositoryModule`, `TorModule`, `UseCaseModule`

**Utility Layer:**
- Purpose: Shared Android/platform utilities outside layer boundaries
- Location: `util/` (LogScrubber, BatteryOptimizationHelper, ImagePrefetcher, UrlPrefetcher)

## Data Flow

### Primary Request Path: Loading Feed Events

1. **Initialization** (`MainActivity.onCreate()`, `UmbraApp.onCreate()`)
   - Prewarm `NostrSessionManager` on `Dispatchers.Default` to avoid blocking main thread
   - Initialize Coil ImageLoader with `@Named("tor")` OkHttpClient
   - Prewarm Media3 video cache

2. **Authentication** → `LoginViewModel` → `AuthModule` → Amber intent
   - User taps "Login with Amber"
   - `AppSessionEffects` (global effects handler) launches Amber signing intent
   - Amber returns signed `kind:10002` event (relay list) and `kind:0` (profile)
   - `LoginViewModel` validates and stores pubkey in `SecurePreferences`

3. **TOR Gate** → `TorGateScreen` → `TorGateViewModel` → `TorRuntimeManager`
   - Gate waits for Orbot connectivity via `OrBotConnectivityCheck`
   - Verifies `TorProxyConfig.isReady` before any network call
   - Routes to Feed on success

4. **Feed Loading** → `FeedScreen` → `FeedViewModel`
   - ViewModel injects use cases and repositories (domain interfaces only)
   - Calls `EventRepository.observeFeed(feedFilter, pubkey)` (returns `Flow<FeedNotesResult>`)
   - Flow emission triggers:
     ```
     EventRepositoryImpl.observeFeed()
       → computes relay subscription filters
       → calls NostrSessionManager.subscribe(filters)
       → relay WebSocket message handler receives EVENT messages
       → EventRepositoryImpl.acceptEvent(event)
           → EventCrypto.verifyEvent(event) (Schnorr BIP-340 verification)
           → eventDao.insertEvent(event) [only if user's own pubkey]
           → EventLruCache.put(event) [everyone's events]
           → EventRepositoryImpl's internal engagement/cache index updates
       → observable Flow emits new FeedNotesResult
     ```

5. **ViewModel State Update**
   - `FeedViewModel._state.update { it.copy(events = ..., profiles = ..., reactionCounts = ...) }`
   - UI recomposition only on changed `@Immutable` state fields

6. **UI Render** (`EventCard`, `NostrTextRenderer`, `UserAvatar` components)
   - EventCard renders `Event` domain model
   - `NostrTextRenderer` renders content with NIP-08 mentions, NIP-23 hashtags, links
   - Image loading via Coil (automatically uses `@Named("tor")` client)
   - All external URLs wrapped with `ExternalUrlWarningDialog`

### Secondary Flow: Publishing a Note (with Amber Signing)

1. **Composer Launch** → `ComposerViewModel` → draft event construction
   - Build unsigned `Event` with `NostrEventBuilder`
   - Collect signed version via `SharedFlow<Intent>` (not a mutable var callback)

2. **Amber Signing** (in Composable via `AmberSignEffect`)
   - `AmberSignEffect` LaunchedEffect collects from `_amberSigningEvents` SharedFlow
   - Launches Amber intent with unsigned event JSON
   - Android ActivityResult callback delivers signed event JSON

3. **Event Publish** → `PublishSignedEventUseCase` → `EventRepository.publishEvent(event)`
   - Parse signed JSON (integrity checks: id, sig present)
   - Persist to Room (user's own event)
   - Broadcast to relays via `NostrClient.publish(event, relays)`
   - Track publish completion via `BroadcastRepository`

### Third Flow: Profile Hydration (On-Demand Relay Fetch)

1. **Profile Cache Miss** → `FeedViewModel.prefetchViewportProfiles(pubkeys)`
2. **Build REQ** → `BuildProfileHydrationRequestsUseCase`
   - Filter out fresh profiles (< 24h old via `isFresh()`)
   - Construct NIP-01 filters for `kind:0` (metadata)
3. **Subscribe** → `NostrSessionManager.subscribe(filters)` → relay routing
4. **Fetch** → relay responses → `EventRepositoryImpl.acceptEvent()` → `userProfileDao.insertUserProfile()`
5. **Collect** → `UserRepository.observeProfile(pubkey: String)` → ViewModel → UI update

**State Management:**
- Central point: `FeedViewModel._state: MutableStateFlow<FeedState>`
- Derived flows:
  - `computedFeedFlow`: filters/transforms raw events
  - `feedState`: combines events + profiles + engagement + relay status
- Viewport tracking: `collectViewportEventIds`, `collectViewportImagePrefetchUrls` for lazy loads
- No global state except Hilt singletons (repositories, DAOs, network client)

## Key Abstractions

**Event (NIP-01):**
- Purpose: Core protocol data model — immutable representation of a Nostr event
- Examples: `domain/nip01/Event.kt`
- Pattern: `@Immutable @Serializable data class Event(id, pubkey, createdAt, kind, tags, content, sig)`

**Repository (Domain/Data split):**
- Purpose: Isolate data source concerns from business logic
- Examples: `domain/repository/EventRepository.kt` (interface) → `data/repository/EventRepositoryImpl.kt` (impl)
- Pattern: Interface returns domain models; impl maps entities ↔ models; all error handling in impl

**UseCase:**
- Purpose: Encapsulate a single, reusable business operation
- Examples: `PublishSignedEventUseCase`, `DeleteNoteUseCase`, `BuildProfileHydrationRequestsUseCase`
- Pattern: Plain Kotlin class, one `suspend operator fun invoke(params): Result<T>` or `Flow<T>`

**FeedFilter (User-Controlled Content Moderation):**
- Purpose: Represent user's muting, NSFW hiding, and content filtering preferences
- Examples: `domain/feed/FeedFilter.kt`, `domain/feed/FilterDefaults.kt`
- Pattern: `@Immutable data class` with tag/author/prefix exclusions; defaults are editable, not hardcoded

**AmberSignerGateway (Signing Abstraction):**
- Purpose: Hide Amber's intent-based API behind a domain-level interface
- Examples: `domain/nip55/AmberSignerGateway.kt` (interface) → `data/amber/AmberSignerGatewayImpl.kt` (impl)
- Pattern: Returns `Result<Event>` after Amber signs; no nsec exposure

**NIP-Specific Packages:**
- Purpose: Organize protocol features by Nostr Improvement Proposal number
- Examples: `domain/nip01/` (core), `domain/nip05/` (NIP-05 identity), `domain/nip65/` (relay list metadata)
- Pattern: One `domain/nipXX/` per NIP; models + validation + domain logic in domain; data impl in `data/`

## Entry Points

**MainActivity:**
- Location: `MainActivity.kt`
- Triggers: App launch (first activity)
- Responsibilities: Hilt `@AndroidEntryPoint`, set Compose content, theme setup, deep-link URI capture, battery optimization request

**UmbraApp (Application subclass):**
- Location: `UmbraApp.kt`
- Triggers: Process startup (before MainActivity)
- Responsibilities: Prewarm singleton dependency graph on background dispatcher, set Coil ImageLoader factory, respond to OS memory pressure via `onTrimMemory()`

**NavHost (Central Navigator):**
- Location: `ui/NavHost.kt`
- Triggers: First Compose composition in MainActivity
- Responsibilities: Define all screen routes, navigation graph, authentication/TOR gates, deep-link resolution

**TorGateScreen:**
- Location: `ui/tor/TorGateScreen.kt`
- Triggers: After user authentication
- Responsibilities: Wait for Orbot connectivity, gate network access, show TOR exit IP/country

**FeedScreen:**
- Location: `ui/feed/FeedScreen.kt`
- Triggers: After TOR gate, main app view
- Responsibilities: Render event list, manage relay status, handle pagination, prefetch images/profiles

**LoginScreen:**
- Location: `ui/auth/LoginScreen.kt`
- Triggers: App launch (before authentication)
- Responsibilities: Offer Amber/anonymous login, check Amber installed, show Amber install prompt if needed

## Architectural Constraints

- **Threading:** Single-threaded event loop (main thread for Compose), IO operations on `Dispatchers.IO`, CPU work on `Dispatchers.Default`, proper scope cancellation via `SupervisorJob()`
- **Global state:** Hilt `@Singleton` scoped: `EncryptedUmbraDatabase`, `NostrSessionManager`, `OkHttpClient`, `ImageLoader`, `UserRepository`, `EventRepository`. No module-level var state except pure Kotlin immutable constants
- **Circular imports:** None; dependency graph is strictly hierarchical (UI → Domain → Data → Android)
- **Network isolation:** All traffic through single `@Named("tor")` OkHttpClient; no alternative paths
- **Database isolation:** Single encrypted Room database; no plaintext fallback database
- **Signing isolation:** Only `AmberSignerGateway` (domain) → `AmberConnector` (data/amber) → Amber; no in-app key generation or signing
- **Cache strategy:** `EventLruCache` for non-owned events (in-memory, access-ordered eviction); Room for user's own events (persisted, encrypted)

## Anti-Patterns

### Mutable ViewState Callbacks

**What happens:** A ViewModel with `var onSignEvent: ((String) -> Unit)? = null` or `var onNavigate: (Screen) -> Unit = {}`

**Why it's wrong:** 
- Lifecycle mismatch: callback may fire after ViewModel cleared but Composable still holds it
- No coroutine scope: callback can't safely dispatch to IO or collect flows
- Race conditions: multiple threads may set/invoke concurrently

**Do this instead:** 
Use `SharedFlow<SideEffect>` in ViewModel, collect in LaunchedEffect in Composable. Example: `AmberSignEffect` in `ui/components/AmberSignEffect.kt` mediates Amber signing via `viewModel._amberSigningEvents: SharedFlow<Intent>`.

### Direct Network in ViewModel

**What happens:** `ViewModel(private val okHttpClient: OkHttpClient)` or injecting `NetworkClient` directly

**Why it's wrong:** 
- Breaks Clean Architecture layer boundary (ViewModel should never know about OkHttp)
- Makes testing hard (can't mock network behavior at ViewModel level)
- Violates the single-point-of-control rule for TOR proxy configuration

**Do this instead:** 
Inject repositories (domain interfaces) only. Network calls are handled in data-layer repository implementations which reuse the single `@Named("tor")` client. Example: `FeedViewModel` injects `EventRepository` (domain interface), never `OkHttpClient`.

### N+1 Event Lookups

**What happens:** 
```kotlin
val eventIds = listOf("e1", "e2", "e3")
val events = eventIds.map { eventRepository.getEventById(it) }  // 3 queries
```

**Why it's wrong:** Scales poorly with large result sets; for a 1000-event feed, that's 1000 room queries

**Do this instead:** 
```kotlin
val events = eventRepository.getEventsByIds(eventIds)  // 1 query with IN clause
```
Same pattern for profiles: `userRepository.getProfiles(pubkeys)` not `getProfile(pubkey)` in a loop.

### Hardcoded Content Filtering

**What happens:** 
```kotlin
if (event.pubkey == SPAM_PUBKEY || event.content.contains(BANNED_WORD)) {
    return  // silently drop event
}
```

**Why it's wrong:** 
- Violates Umbra's non-negotiable privacy contract: user controls ALL moderation
- App makes unilateral decisions about what content is "allowed"
- Users can't opt out or modify the filter

**Do this instead:** 
Any new content filtering logic must be built as a `FeedFilter` default (in `domain/feed/FilterDefaults.kt`), editable and fully removable by the user via `FeedConfigScreen`. Example: `FilterDefaults.kt` pre-loads muted noise hashtags as defaults, but every one is a normal `FeedFilter` entry the user can see and turn off.

### hiltViewModel() in List Items or Components

**What happens:** 
```kotlin
@Composable
fun EventCard(eventId: String) {
    val viewModel = hiltViewModel<EventCardViewModel>()  // WRONG
    // ...
}
```

**Why it's wrong:** 
- Each item in LazyColumn gets its own ViewModel, bypassing the list's viewport tracking
- Uncontrolled recomposition of ViewModel state for every item
- State is scoped to item lifetime, not list lifetime (items get recreated on scroll)

**Do this instead:** 
Pass state as parameters from parent. Example: `EventCard(event: Event, profiles: ImmutableMapSnapshot<String, UserProfile>, ...)` — data comes from parent's ViewModel, not a child ViewModel.

### Persisting Non-Owned Events to Room

**What happens:** 
```kotlin
if (event.pubkey != userPubkey) {
    eventDao.insertEvent(event)  // public event database — WRONG
}
```

**Why it's wrong:** 
- Creates a "public events" table that has no cleanup strategy
- Scales indefinitely (billions of events from the entire Nostr network)
- Synchronization nightmare: which events to evict? when?

**Do this instead:** 
Non-owned events go in-memory only via `EventLruCache` (bounded, access-ordered eviction). User's own events go to Room (encrypted). If a non-owned event is referenced (quoted, replied-to), fetch it on-demand from relays. Example: `EventRepositoryImpl.acceptEvent()` inserts to Room only if `event.pubkey == userPubkey`, otherwise adds to `EventLruCache` only.

## Error Handling

**Strategy:** Result types and logging, never silent swallows.

**Patterns:**
- Use cases return `Result<T>` (Kotlin stdlib), not throw exceptions
- Repositories implement error mapping: platform-specific errors (OkHttp exceptions, Room errors) are caught in impl, mapped to domain-level errors or wrapped in `Result<T>`
- All log statements gated by `Log.isLoggable` (injected via `UmbraLog.tag()`, not direct `android.util.Log` calls)
- Network errors logged with `scrubThrowableMessageForLogs()` to remove sensitive URLs/pubkeys in release builds
- Event verification failures logged at debug level only, no content logged (see AUDIT.md §1.5)

**Example:**
```kotlin
class PublishSignedEventUseCase(
    private val eventRepository: EventRepository,
    private val logger: UmbraLogger
) {
    suspend operator fun invoke(signedEventJson: String): Result<Event> =
        withContext(Dispatchers.Default) {
            runCatching {
                val event = parseSignedEventJson(signedEventJson)
                eventRepository.publishEvent(event).getOrThrow()
                event
            }.onFailure { e ->
                logger.d { "Failed to publish: ${scrubThrowableMessageForLogs(e)}" }
            }
        }
}
```

## Cross-Cutting Concerns

**Logging:** 
All logs via `UmbraLog.tag(TAG)` → `Logger` instance → `logger.d { }`, `logger.w { }`, `logger.e(throwable) { }`. 
- Gated behind `Log.isLoggable` (evaluated lazily via message lambda).
- Content scrubbed: relay URLs, pubkeys, profile fields, event content never logged.
- Helpers: `LogScrubber.scrubUrlForLogs()`, `scrubPubkeyForLogs()`, `scrubThrowableMessageForLogs()`.

**Validation:**
- Event structure: `NostrValidation.isValidEvent(event)` (checks id, sig, kind, content size)
- Event signature: `EventCrypto.verifyEvent(event)` (BIP-340 Schnorr) before any persistence
- Relay URLs: `RelayUrlNormalizer.normalizeUrl(url)` with `.onion` support
- Local network relays rejected (security: TOR-only, no local network fallback)

**Authentication:**
- Gate: `canSignWithAmber()` (checks Amber installed + user logged in)
- Amber integration: `AmberSignerGateway` (domain interface) → `AmberSignerGatewayImpl` (intent mediation in data layer)
- Session: `NostrSessionController.start()` (prewarm in UmbraApp) → `NostrSessionManager.activateUserSession(pubkey, feedFilter)`
- Logout: `LogoutUseCase` → clears pubkey from `SecurePreferences`, clears all Room data, resets relay subscriptions

---

*Architecture analysis: 2026-09-02*
