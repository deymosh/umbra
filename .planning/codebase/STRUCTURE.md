# Codebase Structure

**Analysis Date:** 2026-09-02

## Directory Layout

```
app/src/main/
├── AndroidManifest.xml        # Permissions, intent-filters, deep-link routes (nostr: URI)
├── java/com/umbra/app/
│   ├── MainActivity.kt         # Single activity entry point
│   ├── UmbraApp.kt            # Application subclass, singleton prewarming
│   ├── NavHost.kt             # Central navigation orchestration
│   │
│   ├── di/                    # Top-level Hilt modules
│   │   ├── AuthModule.kt      # AmberSignerGateway bindings
│   │   ├── DatabaseModule.kt  # EncryptedUmbraDatabase, DAOs
│   │   ├── RepositoryModule.kt # Repository @Binds
│   │   ├── TorModule.kt       # TOR-related singletons
│   │   ├── UseCaseModule.kt   # UseCase injection (domain dependencies)
│   │   ├── MediaModule.kt     # Media3 data source provider
│   │   └── MediaContextModule.kt
│   │
│   ├── util/                  # Android-level platform utilities
│   │   ├── logging/
│   │   │   ├── Logger.kt      # UmbraLog wrapper; implements UmbraLogger interface
│   │   │   └── UmbraLog.kt    # Tag-based logger factory
│   │   ├── LogScrubber.kt     # Scrub relay URLs, pubkeys, profile fields from logs
│   │   ├── BatteryOptimizationHelper.kt # Request battery optimization exemption
│   │   ├── ImagePrefetcher.kt # Preload images via Coil
│   │   ├── UrlPrefetcher.kt   # Warm HTTP cache for URLs
│   │   ├── MediaMetadataStripper.kt # Strip EXIF/container metadata before upload
│   │   ├── ImageLoadGate.kt   # Rate-limit image loads
│   │   └── MediaLoadPriorityGate.kt # Prioritize video > images
│   │
│   ├── data/                  # Data layer
│   │   ├── di/
│   │   │   └── NetworkModule.kt # Single @Named("tor") OkHttpClient + Coil ImageLoader
│   │   │
│   │   ├── amber/             # Amber signer integration (ONLY place Amber is called)
│   │   │   ├── AmberConnector.kt # Construct/parse Amber intents
│   │   │   └── AmberSignerGatewayImpl.kt # Implement AmberSignerGateway + Nip44Gateway
│   │   │
│   │   ├── crypto/
│   │   │   └── EventCrypto.kt # BIP-340 Schnorr signature verification
│   │   │
│   │   ├── db/
│   │   │   ├── EncryptedUmbraDatabase.kt # Single SQLCipher Room database
│   │   │   ├── dao/
│   │   │   │   ├── EventDao.kt
│   │   │   │   ├── EventTagDao.kt
│   │   │   │   ├── UserProfileDao.kt
│   │   │   │   ├── RelayDao.kt
│   │   │   │   └── FeedFilterDao.kt
│   │   │   ├── entities/
│   │   │   │   ├── EventEntity.kt
│   │   │   │   ├── EventTagEntity.kt
│   │   │   │   ├── UserProfileEntity.kt
│   │   │   │   ├── RelayEntity.kt
│   │   │   │   └── FeedFilterEntity.kt
│   │   │   ├── mapper/          # Entity ↔ Domain conversions
│   │   │   │   ├── EventMapper.kt
│   │   │   │   ├── UserProfileMapper.kt
│   │   │   │   ├── RelayMapper.kt
│   │   │   │   └── FeedFilterMapper.kt
│   │   │   └── pojo/
│   │   │       └── NoteWithProfile.kt # Room @Relation projection
│   │   │
│   │   ├── nostr/              # Nostr protocol client
│   │   │   ├── NostrSessionManager.kt # Relay connection lifecycle
│   │   │   ├── NostrClient.kt  # WebSocket subscription/publish
│   │   │   ├── NostrRequestBuilder.kt # Build NIP-01 filter/auth/count requests
│   │   │   ├── RelayBackoffPolicy.kt # Exponential backoff + circuit breaker
│   │   │   ├── RelaySubscriptionRegistry.kt # Track active subscriptions per relay
│   │   │   ├── RelayMessageHandling.kt # Parse relay EVENT/NOTICE/OK/EOSE messages
│   │   │   ├── RelayNoticeClassifier.kt # Categorize relay NOTICE messages
│   │   │   ├── OrBotConnectivityCheck.kt # Detect Orbot readiness
│   │   │   ├── BackfillAnchorStore.kt # Persist backfill progress
│   │   │   └── BoundedEventCache.kt # Deprecated (moved to EventLruCache)
│   │   │
│   │   ├── repository/
│   │   │   ├── EventRepositoryImpl.kt # Core event ingestion + caching
│   │   │   ├── EventRepositoryRelayRouting.kt # Relay selection logic
│   │   │   ├── EventRepositoryFeedResolution.kt # Feed computation
│   │   │   ├── EventRepositoryFeedSelection.kt # Filter application
│   │   │   ├── EventChannelRouting.kt # Route relay events to subscribers
│   │   │   ├── EventIngestCache.kt # Dedup events in flight
│   │   │   ├── cache/
│   │   │   │   ├── EventLruCache.kt # Bounded in-memory cache (access-ordered LRU)
│   │   │   │   ├── OwnerTagSetCache.kt # Mute/pin/block lists (user-owned tags)
│   │   │   │   └── [other caches]
│   │   │   ├── policy/
│   │   │   │   ├── RelayConnectionPolicy.kt # When/how to connect relays
│   │   │   │   ├── OutboxProfilePolicy.kt # NIP-65 outbox relay selection
│   │   │   │   ├── FeedRelaySincePolicy.kt # "since" filter on reconnect
│   │   │   │   └── [other policies]
│   │   │   ├── UserRepositoryImpl.kt # Profile caching + profile hydration
│   │   │   ├── RelayRepositoryImpl.kt # Relay list management
│   │   │   ├── FeedRepositoryImpl.kt # Feed filter application
│   │   │   ├── ContactListRepositoryImpl.kt # NIP-02 follow list
│   │   │   ├── MuteListRepositoryImpl.kt # NIP-51 mute list
│   │   │   ├── PinListRepositoryImpl.kt # NIP-51 pin list
│   │   │   ├── BroadcastRepositoryImpl.kt # Track publish progress
│   │   │   ├── MediaUploadRepositoryImpl.kt # Upload via @Named("tor") OkHttp
│   │   │   ├── NegentropySyncOrchestrator.kt # NIP-77 negentropy sync
│   │   │   └── [other repository implementations]
│   │   │
│   │   ├── preferences/        # User preferences persistence
│   │   │   ├── UserPreferencesImpl.kt
│   │   │   ├── SyncPreferencesImpl.kt
│   │   │   ├── AppearancePreferencesImpl.kt
│   │   │   └── DeveloperPreferencesImpl.kt
│   │   │
│   │   ├── security/
│   │   │   └── SecurePreferences.kt # Android Keystore + AES/GCM
│   │   │
│   │   ├── media/
│   │   │   ├── TorMediaDataSourceProvider.kt # Media3 DataSource using @Named("tor") client
│   │   │   └── [media utilities]
│   │   │
│   │   └── tor/
│   │       └── TorRuntimeManager.kt # Monitor TOR connectivity
│   │
│   ├── domain/                # Domain layer (pure Kotlin, no Android imports)
│   │   ├── logging/
│   │   │   └── UmbraLogger.kt # Pure interface (no android.util.Log)
│   │   │
│   │   ├── nip01/             # NIP-01 Core Protocol
│   │   │   ├── Event.kt       # Core event model (@Immutable, @Serializable)
│   │   │   ├── EventFilter.kt # NIP-01 subscription filter
│   │   │   ├── NostrEventBuilder.kt # Build unsigned events
│   │   │   ├── NostrValidation.kt # Event/signature validation
│   │   │   └── KindNames.kt  # Event kind descriptions
│   │   │
│   │   ├── nip02/             # NIP-02 Contact List
│   │   │   └── ContactList.kt
│   │   │
│   │   ├── nip05/             # NIP-05 Identity Verification
│   │   │   ├── Nip05Identifier.kt
│   │   │   └── Nip05VerificationState.kt
│   │   │
│   │   ├── nip11/             # NIP-11 Relay Information
│   │   │   └── RelayInfo.kt
│   │   │
│   │   ├── nip17/             # NIP-17 Private Direct Messages
│   │   │   └── DmRelayList.kt
│   │   │
│   │   ├── nip18/             # NIP-18 Reposts
│   │   │   ├── extractRepostTarget.kt
│   │   │   └── [repost utilities]
│   │   │
│   │   ├── nip19/             # NIP-19 Bech32 Encoding
│   │   │   ├── Bech32Encoder.kt # Encode/decode npub, nsec (detection only), note, etc.
│   │   │   └── [bech32 utilities]
│   │   │
│   │   ├── nip21/             # NIP-21 nostr: URIs
│   │   │   ├── NostrUri.kt
│   │   │   └── resolveNostrUri.kt
│   │   │
│   │   ├── nip22/             # NIP-22 Event created_at Limits
│   │   │   └── Comment.kt
│   │   │
│   │   ├── nip25/             # NIP-25 Reactions
│   │   │   ├── ReactionSemantics.kt
│   │   │   └── ReactionEmoji.kt
│   │   │
│   │   ├── nip30/             # NIP-30 Custom Emoji
│   │   │   └── CustomEmoji.kt
│   │   │
│   │   ├── nip36/             # NIP-36 Sensitive Content / Content Warning
│   │   │   └── ContentWarning.kt
│   │   │
│   │   ├── nip44/             # NIP-44 Encrypted Payloads
│   │   │   ├── Nip44Gateway.kt # Encrypt/decrypt interface (impl via Amber)
│   │   │   └── Nip44Payload.kt
│   │   │
│   │   ├── nip45/             # NIP-45 Event Counts
│   │   │   └── RelayCountResult.kt
│   │   │
│   │   ├── nip51/             # NIP-51 Lists (Mute, Pin, Bookmark, etc.)
│   │   │   ├── MuteList.kt
│   │   │   ├── PinList.kt
│   │   │   ├── BookmarkList.kt
│   │   │   ├── CommunitiesList.kt
│   │   │   └── [other lists]
│   │   │
│   │   ├── nip55/             # NIP-55 Amber Signing
│   │   │   └── AmberSignerGateway.kt # Domain interface (impl in data/amber)
│   │   │
│   │   ├── nip65/             # NIP-65 Relay List Metadata
│   │   │   └── RelayListMetadata.kt
│   │   │
│   │   ├── nip68/             # NIP-68 Picture Event
│   │   │   └── PictureEvent.kt
│   │   │
│   │   ├── nip7d/             # NIP-7D Forum Threads
│   │   │   └── ForumThread.kt
│   │   │
│   │   ├── nip92/             # NIP-92 Image metadata
│   │   │   └── ImetaTag.kt
│   │   │
│   │   ├── nipa4/             # NIPA-04 Public Message
│   │   │   └── PublicMessage.kt
│   │   │
│   │   ├── nipb7/             # NIP-B7 Blossom
│   │   │   ├── BlossomBlobDescriptor.kt
│   │   │   ├── BlossomHash.kt
│   │   │   ├── UserServerList.kt
│   │   │   └── BlossomServerUrl.kt
│   │   │
│   │   ├── nipc7/             # NIP-C7 Chat Message
│   │   │   └── ChatMessage.kt
│   │   │
│   │   ├── crypto/
│   │   │   └── PubkeyUtils.kt # Pubkey utilities (pure Kotlin)
│   │   │
│   │   ├── feed/              # Content Moderation (User-Controlled)
│   │   │   ├── FeedFilter.kt # @Immutable user mute/NSFW/filter state
│   │   │   └── FilterDefaults.kt # Default filters (all editable/removable)
│   │   │
│   │   ├── media/
│   │   │   ├── MediaDataSourceProvider.kt # Media3 data source interface
│   │   │   └── VideoCacheDataSourceProvider.kt # Video cache wrapper
│   │   │
│   │   ├── model/             # UI-centric domain models
│   │   │   ├── NoteView.kt   # Rendered note view model
│   │   │   ├── NostrChannels.kt # Channel definitions
│   │   │   ├── PendingRepost.kt # Repost awaiting resolution
│   │   │   ├── EventCacheStats.kt
│   │   │   └── FeedNotesResult.kt
│   │   │
│   │   ├── nostr/
│   │   │   └── NostrSessionController.kt # Session lifecycle interface
│   │   │
│   │   ├── preferences/
│   │   │   ├── UserPreferences.kt # Interface for user preferences
│   │   │   ├── AppearancePreferences.kt
│   │   │   ├── SyncPreferences.kt
│   │   │   └── DeveloperPreferences.kt
│   │   │
│   │   ├── profile/
│   │   │   └── UserProfile.kt # @Immutable user profile model
│   │   │
│   │   ├── relay/             # Relay Management & Discovery
│   │   │   ├── Relay.kt       # @Immutable relay model
│   │   │   ├── DefaultRelays.kt # Bootstrap relay list
│   │   │   ├── RelayIssue.kt  # Relay error tracking
│   │   │   ├── RelayRequestInfo.kt # Relay subscription metadata
│   │   │   ├── SubscriptionType.kt # REQ subscription categorization
│   │   │   ├── RelayUrlNormalizer.kt # Normalize relay URLs (.onion support)
│   │   │   ├── TorCircuitHealthTracker.kt # Detect TOR circuit decay
│   │   │   └── [relay utilities]
│   │   │
│   │   ├── repository/        # Repository interfaces (contracts only)
│   │   │   ├── EventRepository.kt
│   │   │   ├── UserRepository.kt
│   │   │   ├── RelayRepository.kt
│   │   │   ├── FeedRepository.kt
│   │   │   ├── AmberSignerGateway.kt # (in nip55/ actually, but contract lives here)
│   │   │   ├── ContactListRepository.kt
│   │   │   ├── MuteListRepository.kt
│   │   │   ├── PinListRepository.kt
│   │   │   ├── BroadcastRepository.kt
│   │   │   ├── MediaUploadRepository.kt
│   │   │   ├── TorStatusRepository.kt
│   │   │   ├── RelayInfoRepository.kt
│   │   │   └── [other repositories]
│   │   │
│   │   ├── tor/
│   │   │   ├── TorRuntimeController.kt # TOR lifecycle interface
│   │   │   └── TorRuntimeState.kt    # @Immutable TOR status
│   │   │
│   │   ├── usecase/           # Pure Kotlin business logic
│   │   │   ├── PublishEventUseCases.kt # PublishSignedEventUseCase, PublishAuthEventUseCase
│   │   │   ├── DeleteNoteUseCase.kt
│   │   │   ├── BootstrapOwnProfileUseCase.kt
│   │   │   ├── BuildProfileHydrationRequestsUseCase.kt
│   │   │   ├── BuildEngagementFiltersUseCase.kt
│   │   │   ├── BuildEventShareUrlUseCase.kt
│   │   │   ├── CheckTorStatusUseCase.kt
│   │   │   ├── TrackReferencedAuthorUseCase.kt
│   │   │   ├── TrimMemoryCachesUseCase.kt
│   │   │   ├── LogoutUseCase.kt
│   │   │   ├── ObserveResourceUsageUseCase.kt
│   │   │   └── [other use cases]
│   │   │
│   │   ├── broadcast/         # Broadcast event handling
│   │   │   └── BroadcastEventHandler.kt
│   │   │
│   │   ├── lightning/         # Lightning address resolution
│   │   │   └── LightningAddress.kt
│   │   │
│   │   └── util/             # Pure Kotlin utilities
│   │       ├── JsonUtils.kt  # Singleton kotlinx.serialization instances
│   │       └── TrackingTokenSanitizer.kt
│   │
│   └── ui/                    # Presentation layer (Jetpack Compose)
│       ├── UmbraNavHost.kt    # Nav graph integration
│       ├── Screen.kt          # Screen route definitions
│       ├── UiState.kt         # Shared UI state shapes
│       │
│       ├── auth/
│       │   ├── LoginScreen.kt
│       │   └── LoginViewModel.kt (@HiltViewModel, @Immutable state)
│       │
│       ├── feed/
│       │   ├── FeedScreen.kt  # Main feed display
│       │   ├── FeedViewModel.kt # Feed state, subscription, engagement
│       │   ├── EventCard.kt   # Event render (reusable component)
│       │   ├── ThreadScreen.kt # Event thread view
│       │   ├── ThreadViewModel.kt
│       │   ├── FeedStateMergeCoordinator.kt # Feed state derivation
│       │   └── [feed utilities]
│       │
│       ├── profile/
│       │   ├── ProfileScreen.kt # User profile display
│       │   ├── ProfileViewModel.kt
│       │   ├── EditProfileScreen.kt
│       │   ├── EditProfileViewModel.kt
│       │   └── [profile utilities]
│       │
│       ├── relay/
│       │   ├── RelayConfigScreen.kt # Relay management UI
│       │   ├── RelayDetailsScreen.kt # Relay info
│       │   ├── ActiveSubscriptionsScreen.kt # Active subscriptions list
│       │   ├── RelayConfigViewModel.kt
│       │   └── [relay utilities]
│       │
│       ├── composer/
│       │   ├── ComposerScreen.kt # New note/reply/quote composition
│       │   ├── ComposerViewModel.kt
│       │   └── [composer utilities]
│       │
│       ├── feedconfig/
│       │   ├── FeedConfigScreen.kt # Edit feed filters
│       │   ├── FeedFilterEditScreen.kt
│       │   ├── FeedConfigViewModel.kt
│       │   └── [feedconfig utilities]
│       │
│       ├── settings/
│       │   ├── SettingsScreen.kt # App settings
│       │   ├── AppearanceScreen.kt # Theme selection
│       │   ├── AppearanceViewModel.kt
│       │   └── [settings utilities]
│       │
│       ├── tor/
│       │   ├── TorGateScreen.kt # TOR connectivity gate
│       │   ├── TorGateViewModel.kt
│       │   ├── TorSideEffect.kt # Side effect types
│       │   └── TorState.kt
│       │
│       ├── blossom/
│       │   ├── BlossomServersScreen.kt # Blossom media server selection
│       │   └── BlossomServersViewModel.kt
│       │
│       ├── broadcast/
│       │   ├── BroadcastBanner.kt # Publish progress feedback
│       │   └── BroadcastViewModel.kt
│       │
│       ├── devoptions/
│       │   ├── DeveloperOptionsScreen.kt # Dev/debug options
│       │   ├── DeveloperOptionsViewModel.kt
│       │   └── dbinspector/
│       │       ├── DbInspectorScreen.kt # Database inspection
│       │       └── DbInspectorViewModel.kt
│       │
│       ├── resourceusage/
│       │   ├── AppResourceUsageScreen.kt # Memory/cache stats
│       │   └── AppResourceUsageViewModel.kt
│       │
│       ├── components/        # Reusable composables (do NOT re-implement)
│       │   ├── UserAvatar.kt # Profile picture + fallback
│       │   ├── UserIdentityBadge.kt # NIP-05 badge
│       │   ├── ActionsBottomSheet.kt # Action menu
│       │   ├── ExternalUrlWarningDialog.kt # TOR warning
│       │   ├── AmberSignEffect.kt # Amber signing launcher
│       │   ├── LoadingSpinner.kt
│       │   ├── EmptyState.kt
│       │   ├── SectionHeader.kt
│       │   ├── ChipBadge.kt
│       │   ├── KeyValueCopyRow.kt
│       │   ├── ErrorBanner.kt
│       │   ├── NostrTextRenderer.kt # Content rendering engine
│       │   ├── Formatters.kt (TimeFormatter, etc.)
│       │   ├── MenuItemRow.kt
│       │   ├── media/
│       │   │   ├── ImageViewer.kt
│       │   │   ├── VideoPlayer.kt
│       │   │   └── [media components]
│       │   ├── LocalImageLoadGate.kt # CompositionLocal for gate
│       │   └── LocalMediaLoadPriorityGate.kt
│       │
│       ├── common/
│       │   ├── UiMessage.kt # Sealed class for error/status messages
│       │   ├── ImmutableListSnapshot.kt # Stable wrapper for lists
│       │   ├── ImmutableMapSnapshot.kt # Stable wrapper for maps
│       │   ├── InteractionActionsCoordinator.kt # Engagement dedup
│       │   ├── ViewportTracking.kt # Lazy-load coordination
│       │   └── [common utilities]
│       │
│       └── theme/
│           ├── UmbraTheme.kt # Material3 theme
│           ├── UmbraThemeOption.kt # Selectable palettes
│           └── [color/typography]
│
└── res/
    ├── drawable/           # Vector drawables, icons
    ├── mipmap-anydpi/       # Adaptive app icon
    ├── xml/                 # System integration (network security, backup)
    └── values/
        ├── strings.xml     # User-visible strings (for stringResource)
        ├── colors.xml      # Color definitions
        └── [other resources]
```

## Directory Purposes

**`app/src/main/java/com/umbra/app/di/`** — Top-Level Dependency Injection Modules
- Purpose: Declare and scope singletons shared across all layers
- Key modules: `AuthModule` (Amber bindings), `DatabaseModule`, `RepositoryModule`, `TorModule`, `UseCaseModule`, `MediaModule`
- Pattern: `@Module @InstallIn(SingletonComponent::class)` abstract classes with `@Binds` for interface→impl
- No network config here (see `data/di/NetworkModule`); modules should not mix concerns

**`app/src/main/java/com/umbra/app/util/`** — Android Platform Utilities
- Purpose: Utilities that don't fit a single layer (logging, image prefetch, battery optimization)
- Contains: `LogScrubber` (scrub logs), `BatteryOptimizationHelper`, `ImagePrefetcher`, `UrlPrefetcher`, `MediaMetadataStripper`, load gates
- Key distinction: These are Android-aware (Context, Framework), but not part of Clean Architecture layers

**`app/src/main/java/com/umbra/app/data/`** — Data Layer Implementation
- Purpose: Persistence, networking, external service integration, entity mapping
- Contains: All repository implementations, DAOs, Room entities, mappers, network clients, caching
- Depends on: Domain layer (interfaces, models), Android Framework, third-party libraries
- Never imported by: `domain/`, `ui/` (except implementations in `data/` itself)

**`app/src/main/java/com/umbra/app/data/db/`** — Database Layer
- DAOs (`*Dao.kt`): Room @Dao interfaces with suspend functions for IO
- Entities (`*Entity.kt`): Room @Entity data classes mapping to tables
- Mappers (`*Mapper.kt`): Bidirectional Entity ↔ Domain conversions
- `EncryptedUmbraDatabase.kt`: Single Room database with SQLCipher encryption
- All migrations handled by `@Database(version = X)` and `Migration` objects

**`app/src/main/java/com/umbra/app/data/nostr/`** — Nostr Protocol Client
- Purpose: WebSocket relay connections, subscription management, message parsing
- Key classes: `NostrSessionManager` (relay lifecycle), `NostrClient` (publish/subscribe), `RelayBackoffPolicy`
- Entry point: `NostrSessionManager.start()` called from `UmbraApp.onCreate()`

**`app/src/main/java/com/umbra/app/data/repository/`** — Repository Implementations
- Purpose: Implement domain repository contracts, handle data source selection
- Core: `EventRepositoryImpl` (6700+ lines, handles event ingestion, caching, relay routing)
- Caching: `EventLruCache`, `OwnerTagSetCache` for bounded in-memory data
- Policies: `RelayConnectionPolicy`, `OutboxProfilePolicy`, `FeedRelaySincePolicy` for relay behavior
- All methods: take domain models, return domain models (mappers in `db/mapper/`)

**`app/src/main/java/com/umbra/app/domain/`** — Domain Layer (Pure Kotlin)
- Purpose: Business logic, data models, use cases, repository interfaces, NIP protocols
- Dependencies: Standard Kotlin only (kotlinx.serialization, kotlinx.coroutines) — no Android
- NIPs: Organized as `domain/nipXX/` packages (one per NIP), e.g., `domain/nip01/`, `domain/nip55/`, `domain/nip65/`
- Models: `@Immutable` data classes for UI state (`Event`, `UserProfile`, `FeedFilter`, `Relay`)
- Use Cases: Plain Kotlin classes, one `operator fun invoke()` per class, return `Result<T>` or `Flow<T>`

**`app/src/main/java/com/umbra/app/domain/repository/`** — Repository Interfaces
- Purpose: Define contracts for data access, no implementation
- Examples: `EventRepository`, `UserRepository`, `RelayRepository`, `AmberSignerGateway`
- Methods: Return domain models only (no entities, no platform types)
- Implemented by: Repository implementations in `data/repository/`

**`app/src/main/java/com/umbra/app/domain/usecase/`** — Use Cases
- Purpose: Encapsulate reusable business operations
- Pattern: One class = one public `suspend operator fun invoke(params): Result<T>` or `suspend operator fun invoke(): Flow<T>`
- Injected into: ViewModels (which collect results/flows)
- Never injected into: Composables (only ViewModels use use cases)

**`app/src/main/java/com/umbra/app/ui/`** — Presentation Layer (Jetpack Compose)
- Purpose: Screens, ViewModels, reusable components
- Depends on: Domain layer only (never `data.*`)
- Screens: `FeedScreen`, `ProfileScreen`, `LoginScreen`, `TorGateScreen`, etc.
- ViewModels: `@HiltViewModel`, state via `StateFlow<@Immutable State>`
- Components: Reusable composables in `ui/components/` (check before re-implementing)

**`app/src/main/java/com/umbra/app/ui/components/`** — Reusable Composables (DO NOT RE-IMPLEMENT)
- Check here before adding any new composable
- Core components: `UserAvatar`, `NostrTextRenderer`, `EventCard`, `ExternalUrlWarningDialog`, `AmberSignEffect`, `LoadingSpinner`
- If you need a UI pattern that appears in 2+ files, extract it here instead of duplicating
- Media components: `ImageViewer.kt`, `VideoPlayer.kt` (isolated `@UnstableApi` annotation)

**`app/src/main/java/com/umbra/app/ui/feed/`** — Feed Feature
- Core: `FeedScreen` (composable), `FeedViewModel` (state + subscription logic)
- Components: `EventCard` (event render), `ThreadScreen`/`ThreadViewModel` (thread view)
- Coordination: `FeedStateMergeCoordinator` (state derivation helpers)
- Prefetching: viewport tracking, image/profile/URL preload

**`app/src/main/java/com/umbra/app/ui/components/media/`** — Media Components
- `ImageViewer.kt`: Coil-based image display (automatically uses `@Named("tor")` client)
- `VideoPlayer.kt`: Media3 ExoPlayer wrapper (ONLY place `@UnstableApi` appears; not propagated up)
- Video cache prewarming happens in `UmbraApp.onCreate()` on `Dispatchers.Default`

## Key File Locations

**Entry Points:**
- `MainActivity.kt`: Single activity, sets Compose content
- `UmbraApp.kt`: Application subclass, dependency prewarm, singleton factory
- `ui/NavHost.kt`: Navigation graph, screen routes, authentication gates

**Configuration:**
- `AndroidManifest.xml`: Permissions, deep-link intent-filters, app icon
- `build.gradle.kts`: Gradle config (AGP 9.3+, compileSdk 37, minSdk 26, jvmTarget 17)
- `.env` (gitignored): Local secrets (API keys, debug config)
- `data/di/NetworkModule.kt`: Single OkHttpClient with TOR proxy config
- `di/DatabaseModule.kt`: EncryptedUmbraDatabase setup, SQLCipher passphrase

**Core Logic:**
- `domain/nip01/Event.kt`: Core event model
- `domain/usecase/PublishEventUseCases.kt`: Event publishing
- `data/repository/EventRepositoryImpl.kt`: Event ingestion + relay routing
- `data/nostr/NostrSessionManager.kt`: Relay connection lifecycle
- `data/amber/AmberSignerGatewayImpl.kt`: Amber signing mediation

**Testing:**
- `app/src/test/` (not in main): Unit tests for ViewModels, UseCases, Repositories
- Test pattern: Mock repositories (domain interfaces), assert ViewModel state changes
- Example: `EventRepositoryImplTest`, `FeedViewModelTest`

**Resources:**
- `res/values/strings.xml`: All user-visible strings
- `res/drawable/`: Vector drawables, icons
- `res/xml/`: Network security config, backup rules

## Naming Conventions

**Files:**
- Kotlin files: `PascalCase.kt` (one public class per file typical, but multiple OK for related types)
- Packages: `lowercase` (never `PascalCase`)
- Test files: `XyzTest.kt` for unit tests, `XyzScreenTest.kt` for UI tests

**Functions:**
- ViewModels: `@HiltViewModel` annotation required; state field: `_state: MutableStateFlow`, public: `state: StateFlow`
- Use cases: `suspend operator fun invoke(params): Result<T>` — never `execute()` or `call()`
- Composables: `@Composable fun SomethingScreen()`, `@Composable fun SomethingCard()`, `@Composable private fun SomethingHelper()`
- Repository methods: verb-noun, e.g., `publishEvent()`, `observeProfile(pubkey)`, `getEventsByIds(ids)`
- DAO methods: verb-noun with return type hint, e.g., `insertEvent()`, `deleteAll()`, `getUserProfile(pubkey): Flow<UserProfileEntity>`

**Variables:**
- State properties: camelCase
- Private mutable state: leading underscore `_state`, public read-only `state` property
- Immutable data: no prefix, e.g., `val events: List<Event>`
- Parameters in data classes: camelCase

**Types:**
- Data classes: `@Immutable` if used in UI state
- Sealed classes: for ADTs (e.g., `UiMessage`, `Screen`, `TorSideEffect`)
- Interfaces: verb or noun, no "I" prefix, e.g., `EventRepository`, `AmberSignerGateway`
- Implementations: `SomethingImpl` for primary impl, or `XyzRepositoryImpl` for consistency

## Where to Add New Code

**New Feature (e.g., NIP implementation):**
1. **Domain models** → `domain/nipXX/` (NIP-specific) or `domain/model/` (shared)
2. **Repository interface** → `domain/repository/XyzRepository.kt`
3. **Repository implementation** → `data/repository/XyzRepositoryImpl.kt`
4. **Use cases** → `domain/usecase/XyzUseCases.kt`
5. **ViewModel** → `ui/feature/XyzViewModel.kt` with `@HiltViewModel`
6. **Screen/Components** → `ui/feature/XyzScreen.kt`, reusable components in `ui/components/`
7. **Hilt bindings** → `di/` (if module doesn't exist yet) or update existing module

**New Reusable Component:**
1. Check `ui/components/` first — if similar exists, refactor it instead of creating duplicate
2. If truly new: `ui/components/XyzComponent.kt`, no `hiltViewModel()` inside (pass state as parameters)
3. Add to reference table in CLAUDE.md if it's a flagship pattern

**Utilities:**
- Pure Kotlin helpers → `domain/util/` (no Android imports)
- Android platform utilities → `util/` or `util/logging/` (application-level)
- Data-layer utilities → `data/util/` or grouped in feature packages (e.g., `data/repository/policy/`)

**Database Changes:**
1. Add `@Entity` in `data/db/entities/`
2. Add `@Dao` in `data/db/dao/`
3. Add `@Relation`/POJO in `data/db/pojo/` if needed
4. Add mapper in `data/db/mapper/`
5. Create `Migration` object for version increment in `EncryptedUmbraDatabase`
6. Update `@Database(version = X)` in `EncryptedUmbraDatabase`

**Hilt Module (New Injectable Singleton):**
1. Create `@Module @InstallIn(SingletonComponent::class)` object/abstract class
2. Add to `di/` (top-level) or `data/di/` (data-specific, e.g., `NetworkModule`)
3. Use `@Binds` for interface→impl (preferred), `@Provides` only if factory logic needed
4. Scope: `@Singleton` for app-wide, `@ActivityScoped` for activity lifetime (rare)

## Special Directories

**`build/`** — Generated Build Artifacts
- Purpose: Gradle build outputs (compiled classes, APK, intermediates)
- Generated: Yes (never commit)
- Committed: No

**`.gradle/`** — Gradle Cache
- Purpose: Gradle wrapper cache, dependency downloads
- Generated: Yes (never commit, safe to delete)
- Committed: No

**`toolchain/`** — Local JDK + Android SDK (Linux only)
- Purpose: `scripts/install-toolchain.sh` output for CI/local dev
- Generated: Yes (via script, safe to delete and re-run)
- Committed: No (gitignored)

**`.planning/`** — Planning workflow state
- Purpose: Planning documents and codebase maps (STRUCTURE.md, ARCHITECTURE.md, etc.)
- Generated: Yes (by planning tools)
- Committed: Yes (workflow artifacts)

**`docs/`** — Project Documentation
- `KNOWN_ISSUES.md`: Open bugs with LOG-N IDs
- `TODO.md`: Backlog items with LOG-N IDs
- `DONE.md`: Completed work archive
- `nip-social-coverage.md`: NIP implementation status
- `nip-priority-roadmap.md`: NIP sequencing priorities

---

*Structure analysis: 2026-09-02*
