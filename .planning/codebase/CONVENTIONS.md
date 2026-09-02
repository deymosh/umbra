# Coding Conventions

**Analysis Date:** 2026-09-02

## Naming Patterns

**Files:**
- Kotlin files: PascalCase (e.g., `Event.kt`, `ComposerViewModel.kt`, `NostrEventBuilder.kt`)
- Data files: match class name (one public class per file)
- Composables: match function name (e.g., `ComposerScreen.kt`, `UserAvatar.kt`)
- Test files: class name + `Test` suffix (e.g., `EventJsonParsingTest.kt`)

**Functions:**
- Regular functions: camelCase (e.g., `detectMentionQuery()`, `mergeActiveFeedFilters()`, `parseNip05Identifier()`)
- Operator functions: `invoke()` for use cases (`operator fun invoke()`)
- Private utility functions: camelCase with underscore prefix optional (e.g., `fun parseImetaFields()`)
- Composables: PascalCase (e.g., `ComposerScreen()`, `UserAvatar()`, `NostrTextRenderer()`)
- Extensions: camelCase (e.g., `fun ImetaTag.toTag(): List<String>`)
- Test methods: backtick-wrapped given/when/then pattern (e.g., `` `given a well-formed event json when fromJson then round-trips every field` ``)

**Variables:**
- Local variables: camelCase (e.g., `textState`, `currentUserPubkey`, `newQuotes`)
- Private fields: camelCase prefixed with underscore (e.g., `_state`, `_published`)
- Public fields (properties): camelCase (e.g., `state`, `published`, `canSign`)
- Constants: UPPER_SNAKE_CASE at top level (e.g., `KIND_TEXT_NOTE`, `MAX_CONTENT_SIZE`, `DRAFT_EVENT_ID`)
- String constants inside functions: UPPER_SNAKE_CASE (e.g., `TAG = "UmbraComposerVM"`)

**Types/Classes:**
- Data classes: PascalCase (e.g., `ComposerState`, `Event`, `UserProfile`)
- Enums: PascalCase (e.g., `SyncDirection`, `DeveloperFlag`, `ThemePreference`)
- ViewModels: `XxxViewModel` PascalCase (e.g., `ComposerViewModel`, `AppSessionViewModel`)
- UseCases: `XxxUseCase` PascalCase (e.g., `PublishSignedEventUseCase`, `GetAllRelaysUseCase`)
- Repositories: `XxxRepository` interface, `XxxRepositoryImpl` implementation (e.g., `EventRepository`, `EventRepositoryImpl`)
- Composables: PascalCase (e.g., `ComposerScreen()`, `EventCard()`)
- Companion objects: `Companion` (standard)

**Parameters:**
- Constructor parameters: camelCase (e.g., `createdAt`, `pubkey`, `kind`)
- Function parameters: camelCase (e.g., `targetEvent`, `content`, `emoji`)
- Receiver parameters (extensions): `this` (standard)

## Code Style

**Formatting:**
- 4-space indentation (Kotlin standard)
- Line length: no hard limit enforced, but code is generally kept concise
- Spacing: standard Kotlin conventions
- No trailing commas (Kotlin versions before 2.0 support)

**Linting:**
- `lintDebug` enforced (see `build.gradle.kts`)
- Lint treats warnings as errors: `warningsAsErrors = true`
- Disabled rules: `AndroidGradlePluginVersion`, `GradleDependency`, `LocalContextGetResourceValueCall`
- Run: `./gradlew lintDebug`

**Compilation:**
- Target: JDK 17, Kotlin 2.4.10
- No compiler plugins beyond KSP and Kotlin Compose
- All code must compile warning-free via `./gradlew compileDebugKotlin`

## Import Organization

**Order (by section):**
1. Package declaration
2. Standard library imports (kotlin.*)
3. Android imports (android.*)
4. AndroidX imports (androidx.*)
5. kotlinx imports (kotlinx.*)
6. Third-party imports (com.*, org.*, etc.)
7. Project imports (com.umbra.app.*)

**Path Aliases:**
- No explicit path aliases configured, but standard package structure enforces clarity:
  - `domain/` imports only domain and kotlin.* (never data or android)
  - `data/` imports domain and data (never ui directly)
  - `ui/` imports domain and ui (never data directly)

**Wildcard Imports:**
- Discouraged
- `import kotlinx.serialization.json.*` occasionally used for JSON builder DSLs
- Otherwise explicit imports preferred

## Error Handling

**Patterns:**
- Use `try/catch` for exception handling in domain layer operations (e.g., `LogoutUseCase.kt`)
- Catch and log, or re-throw with additional context
- Use `runCatching { }.getOrElse { fallback }` pattern for safe fallbacks (e.g., in `ComposerViewModel.kt` mention search)
- Data layer repositories catch and map exceptions before returning to domain
- ViewModels catch use-case errors and convert to UI state (e.g., error messages via `UiMessage`)

**Exception Handling:**
```kotlin
// Pattern 1: Try with fallback
val results = runCatching {
    userRepository.searchLocalProfiles(query, MENTION_SUGGESTION_LIMIT)
}.getOrElse { emptyList() }

// Pattern 2: Try/catch with logging
try {
    // operation
} catch (_: Exception) {
    // handle silently (debug log via logger.d { })
}

// Pattern 3: Catch specific exception with rethrow
try {
    eventRepository.insertEvent(event)
} catch (e: VerificationException) {
    logger.d { "Event verification failed: ${e.message}" }
    // return null or error result
}
```

**No exceptions thrown to ViewModels:** Use cases return `Result<T>`, `Flow<T>`, or `null` instead of throwing.

## Logging

**Framework:** `util/logging/Logger` (domain-agnostic, wraps `android.util.Log`)

**Patterns:**
- Obtain a tagged logger in every class: `private val logger = UmbraLog.tag(TAG)`
- Tag as a class-level constant: `private const val TAG = "UmbraClassName"`
- Use lazy message lambdas to avoid evaluation when level is not loggable:
  ```kotlin
  logger.d { "User profile fetched: $pubkeyShortened" }  // lambda deferred
  logger.w { "Relay connection failed" }
  logger.e(throwable) { "Exception occurred" }  // throwable auto-scrubbed
  ```

**Scrubbing (CRITICAL for privacy):**
- Relay URLs → `LogScrubber.scrubRelay()` returns `"[relay]"` in release
- Public keys (full 64 hex) → `LogScrubber.scrubPubkeyForLogs()` returns first 8 chars in debug only
- Profile fields (name, picture, nip05, about, lud16) → never log them
- Event content → never log it
- Throwable messages → `LogScrubber.scrubThrowableMessageForLogs()` for manual logging
- `logger.e(throwable)` auto-scrubs throwable; other logs require explicit scrub calls

**Example:**
```kotlin
logger.d { "Fetching profile for ${scrubPubkeyForLogs(pubkey)}" }
logger.w { "Failed to fetch from ${scrubRelay(relayUrl)}" }
```

## Comments

**When to Comment:**
- Explain *why* (intent), not *what* (code is self-explanatory)
- Clarify non-obvious algorithmic decisions
- Document NIP-specific behavior and constraints
- Flag known workarounds or temporary solutions
- Explain exceptions to the architecture (see `AUDIT.md` §2.1 for known exceptions)

**JSDoc/KDoc:**
- Use for public APIs: functions, classes, properties (especially ViewModels and UseCases)
- Format: standard KDoc style with `/**` opening
- Include param/return documentation for complex signatures
- Example:
  ```kotlin
  /**
   * Scan backward from [caret] in [text] for an in-progress "@query" mention.
   * Returns null when the caret isn't inside such a run.
   *
   * @param text The input text to scan
   * @param caret The current cursor position
   * @return A [MentionQuery] if found, null otherwise
   */
  fun detectMentionQuery(text: String, caret: Int): MentionQuery? { ... }
  ```

**Annotations:**
- `@Immutable` on all UI state data classes (e.g., `ComposerState`, `Event`)
- `@Stable` when needed for Compose performance (rare; prefer `@Immutable`)
- `@Suppress("UnstableApiUsage")` only in Media3 instantiation file
- `@HiltViewModel` on all ViewModels
- `@Inject` on constructor parameters

## Function Design

**Size:**
- Keep functions short and focused (single responsibility)
- Break complex logic into named helper functions
- Use trailing lambdas for readability in DSLs and high-order functions

**Parameters:**
- Prefer explicit parameters over context passing
- Use destructuring for pairs/data classes when helpful:
  ```kotlin
  viewModelScope.launch {
      snapshotFlow { textState.text to textState.selection }.collectLatest { (text, selection) ->
          // use text and selection
      }
  }
  ```
- Avoid boolean parameters (use named parameters or sealed classes)

**Return Values:**
- Functions returning results use `Result<T>`, `T?`, or `Flow<T>` (never throw to caller)
- Suspend functions return the value directly or use `Flow<T>` for streaming
- Use context-appropriate types: `StateFlow<UiState>` for reactive state, `List<T>` for collections

**Scope:**
- Use `viewModelScope` for ViewModel coroutines (automatically cancelled on clear)
- Use `withContext(Dispatchers.IO)` for blocking I/O inside suspend functions
- Never use `GlobalScope`

## Module Design

**Exports:**
- Public API surface is the primary export (functions/classes without `private`)
- Use `internal` for implementation details
- No private constructors outside sealed classes (favor `private fun factory()` pattern)

**Barrel Files:**
- No index files or re-exports by convention
- Each package is a namespace; consumers import what they need

**Dependency Injection:**
- Constructor injection via Hilt (see `@HiltViewModel`, `@Inject`)
- ViewModels receive dependencies in constructor, never call `getOrNull()`
- No service locator pattern

## State Management

**StateFlow Pattern (UI State):**
- Private mutable: `private val _state = MutableStateFlow(InitialState())`
- Public read-only: `val state: StateFlow<UiState> = _state.asStateFlow()`
- Mutation always via `_state.update { it.copy(...) }` (thread-safe)
- Never `_state.value = _state.value.copy(...)`

**State Data Classes:**
- Always `@Immutable`
- Implement `copy()` via data class (automatic)
- Default constructor arguments for optional fields
- Example:
  ```kotlin
  @Immutable
  data class ComposerState(
      val currentUserPubkey: String? = null,
      val isPublishing: Boolean = false,
      val attachments: List<ImetaTag> = emptyList()
  )
  ```

**Side Effects (SharedFlow):**
- Emit discrete events (not state)
- Private mutable: `private val _published = MutableSharedFlow<Unit>()`
- Public read-only: `val published: SharedFlow<Unit> = _published.asSharedFlow()`
- Example: signing results, navigation events

## Type System

**Data Classes:**
- Immutable by default (all `val` properties)
- Serialize-safe: annotate with `@Serializable` when stored/transmitted
- UI state: annotate with `@Immutable`

**Sealed Classes:**
- Use for Result/State/Event ADTs (algebraic data types)
- Exhaustiveness checking by compiler
- Rare in this codebase (prefer domain models + state)

**Type Aliases:**
- Discouraged; use explicit types
- Example not found in codebase

**Nullability:**
- Non-null by default; use `T?` only when semantically optional
- Avoid `Optional` or `Maybe` (not idiomatic Kotlin)

## Testing

**Test Classes:**
- Minimal setup (no BaseTest class)
- Package mirror the code (e.g., test for `domain.feed.FeedFilter` → `test/domain/feed/FeedFilterTest.kt`)
- No fixtures or factories (create test data inline with helper functions)

**Test Methods:**
- Name: backtick-wrapped, follows "given X when Y then Z" pattern
- No CamelCase test names
- Examples:
  ```kotlin
  fun `given a well-formed event json when fromJson then round-trips every field`()
  fun `given all permits held when a new acquire is requested then it suspends until release`()
  fun `given a custom emoji when building reaction then content is the shortcode and an emoji tag is added`()
  ```

(See TESTING.md for full testing patterns, framework setup, and mocking strategy.)

---

*Convention analysis: 2026-09-02*
