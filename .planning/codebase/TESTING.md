# Testing Patterns

**Analysis Date:** 2026-09-02

## Test Framework

**Runner:**
- JUnit 4 (no JUnit 5 / Jupiter)
- Config: `app/build.gradle.kts`, `testOptions.unitTests.isReturnDefaultValues = true`
- See `build.gradle.kts` (lines 66-75) for configuration

**Assertion Library:**
- `org.junit.Assert.*` (Assert.assertEquals, Assert.assertTrue, Assert.assertNull, etc.)
- No AssertJ, Hamcrest, or custom assertions framework

**Run Commands:**
```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.umbra.app.domain.nip01.EventJsonParsingTest"

# Run a single test method
./gradlew testDebugUnitTest --tests "com.umbra.app.domain.nip01.EventJsonParsingTest.given a well-formed event json when fromJson then round-trips every field"
```

**Async/Coroutines:**
- `kotlinx.coroutines.test.runTest` — virtual time, manages dispatcher
- `kotlinx.coroutines.runBlocking` — blocks thread (used for synchronous fallbacks)
- No custom test coroutine scopes

## Test File Organization

**Location:**
- Mirror source structure exactly: `src/test/java/com/umbra/app/domain/...` mirrors `src/main/java/com/umbra/app/domain/...`
- One test file per class under test (co-located by package)
- Instrumented tests in `src/androidTest/java/...` (requires device/emulator, opt-in per CLAUDE.md)

**Naming:**
- File: `[ClassName]Test.kt` (e.g., `EventJsonParsingTest.kt`, `ComposerViewModelTest.kt`)
- Class: `[ClassName]Test` (matches file name)
- Method: backtick-wrapped given/when/then pattern

**Structure:**
```
package com.umbra.app.domain.nip01

import org.junit.Assert.*
import org.junit.Test

class EventJsonParsingTest {
    // Helper functions
    private fun sampleEvent(...): Event = ...

    // Test methods
    @Test
    fun `given a well-formed event json when fromJson then round-trips every field`() { ... }

    @Test
    fun `given missing fields when fromJsonObject then defaults match the tolerant contract`() { ... }
}
```

## Test Structure

**Suite Organization:**
- No base test classes or mixins
- No `@Before`/`@After` setup (helpers used instead)
- No test fixtures or parameterized tests (data created inline)
- Each test is completely independent

**Patterns:**
- **Setup:** Create test data inline with helper functions:
  ```kotlin
  private fun sampleEvent(
      id: String = "a".repeat(64),
      pubkey: String = "b".repeat(64),
      tags: List<List<String>> = emptyList()
  ): Event {
      return Event(
          id = id,
          pubkey = pubkey,
          createdAt = 42L,
          kind = Event.KIND_TEXT_NOTE,
          tags = tags,
          content = "text",
          sig = "c".repeat(128)
      )
  }
  ```

- **Execution:** Call the function/method under test
  ```kotlin
  val event = Event.fromJson(json)
  ```

- **Assertion:** Multiple assertions per test if logically related
  ```kotlin
  assertEquals(Event(...), event)
  assertTrue(event.tags.isEmpty())
  ```

**Test Method Naming (Given/When/Then):**
All test methods follow the "given X when Y then Z" backtick pattern:
```kotlin
// Element: `given <precondition> when <action> then <expected result>`
fun `given a well-formed event json when fromJson then round-trips every field`()
fun `given missing fields when fromJsonObject then defaults match the tolerant contract`()
fun `given wrong-typed fields when fromJsonObject then falls back to defaults instead of throwing`()
fun `given target event when building reaction then returns kind7 with e and p tags`()
fun `given an unicode emoji when building reaction then content is that emoji and no emoji tag is added`()
fun `given permits available when acquiring then completes immediately`() = runTest { ... }
fun `given all permits held when a new acquire is requested then it suspends until release`() = runTest { ... }
```

The backticks allow any characters (spaces, special punctuation) in the method name for readability.

## Mocking

**Framework:** None (no Mockito, no PowerMock)

**Manual Fake Implementations:**
Tests create minimal fake implementations of interfaces, not mocks:

```kotlin
// Example: FakeRelayRepository from UseCaseDelegationSuiteTest.kt
private class FakeRelayRepository(
    private val allRelaysFlow: Flow<List<Relay>>
) : RelayRepository {
    var addedRelay: Relay? = null
    var updatedRelay: Relay? = null
    var removedRelayId: String? = null

    override fun getAllRelays(): Flow<List<Relay>> = allRelaysFlow
    override suspend fun getRelayById(id: String): Relay? = null
    override suspend fun addRelay(relay: Relay) { addedRelay = relay }
    override suspend fun updateRelay(relay: Relay) { updatedRelay = relay }
    override suspend fun removeRelay(id: String) { removedRelayId = id }
    override suspend fun bootstrapDefaultsOnFirstLogin() = Unit
    override suspend fun clearUserRelayConfig() = Unit
}
```

**What to Mock:**
- Dependencies that are interfaces (repositories, gateways, providers)
- Create a `Fake[InterfaceName]` class that records calls and returns test data

**What NOT to Mock:**
- Domain models (Event, UserProfile, etc.) — create instances directly
- Kotlin collections or standard library — use real implementations
- Util functions — call them directly; they're testable

**Assertion on Fakes:**
Check that methods were called with expected arguments:
```kotlin
AddRelayUseCase(repo)(relayA)
assertEquals(relayA, repo.addedRelay)  // Verify the call was made

RemoveRelayUseCase(repo)("1")
assertEquals("1", repo.removedRelayId)
```

## Fixtures and Factories

**Test Data:**
Create helper functions in the test class or use inline builders:

```kotlin
// Helper function pattern
private fun sampleEvent(...): Event = Event(...)

// Use in test
val target = sampleEvent(id = "1".repeat(64), pubkey = "2".repeat(64))

// Inline construction for single use
val filter = FeedFilter(
    id = "f1",
    name = "Home",
    hideNsfw = true,
    mutedPubkeys = emptySet(),
    excludedTags = emptySet(),
    excludedHashtags = emptySet(),
    isActive = false
)
```

**Location:**
- Test helper functions: inside the test class (private)
- No separate factories or fixture files
- Duplication between test files is acceptable (each test is self-contained)

**JSON Test Data:**
Use inline string literals for JSON parsing tests:
```kotlin
val json = """
    {
      "id": "abc123",
      "pubkey": "def456",
      "created_at": 1700000000,
      "kind": 1,
      "tags": [["e", "eventid"], ["p", "pubkeyid"]],
      "content": "hello world",
      "sig": "sig789"
    }
""".trimIndent()

val event = Event.fromJson(json)
```

## Coverage

**Requirements:** None enforced

**View Coverage (optional):**
```bash
# Not a standard gradle task in this project
# Manual inspection only via IDE or external tools
```

## Test Types

**Unit Tests:**
- Scope: Single class in isolation
- Location: `src/test/java/com/umbra/app/domain/...` (pure Kotlin, no device)
- Examples:
  - `EventJsonParsingTest.kt` — Event deserialization
  - `FeedFilterTest.kt` — filter merging logic
  - `NostrEventBuilderTest.kt` — event construction
- Speed: Microseconds to milliseconds per test
- Database not involved (Room DAOs tested separately in repository tests)

**Integration Tests:**
- Scope: Multiple classes + behavior (repositories, use cases with fake dependencies)
- Location: `src/test/java/com/umbra/app/data/repository/...`, `src/test/java/com/umbra/app/ui/...`
- Examples:
  - `UseCaseDelegationSuiteTest.kt` — use cases with fake repositories
  - `RepositoryPolicySuiteTest.kt` — repository behavior across multiple repositories
  - `EventRepositoryIngestionIntegrationTest.kt` — event ingestion and caching
  - `NostrTextParsingUtilitiesTest.kt` — rendering and tag extraction
- Speed: Milliseconds per test (Flow collection, async operations)
- No device needed (plain JVM)

**Instrumented Tests (Device/Emulator):**
- Scope: Real device, Android framework integration
- Location: `src/androidTest/java/com/umbra/app/...`
- Examples:
  - `BlurHashInstrumentedTest.kt` — BlurHash decoding with Android bitmap
  - `MediaMetadataStripperInstrumentedTest.kt` — EXIF stripping on real files
- Speed: Seconds per test (framework overhead, I/O)
- Run: `./gradlew installDebug`, then `adb shell am instrument ...` (manual or via `run-umbra` skill)
- Status: Opt-in only per CLAUDE.md — never invoke emulator on your own initiative

**Test Count:**
- 122 test files, ~16,000 lines of test code
- Distributed across unit (domain/models), integration (repository/usecase/component), and instrumented (media handling)

## Common Patterns

**Async Testing (runTest):**
```kotlin
@Test
fun `given all permits held when a new acquire is requested then it suspends until release`() = runTest {
    val gate = ImageLoadGate()
    repeat(6) { gate.acquire() }

    var acquired = false
    val waiter = async { gate.acquire(); acquired = true }
    yield()

    assertFalse(acquired)

    gate.release()
    waiter.await()

    assertTrue(acquired)
}
```

**Flow Testing:**
```kotlin
@Test
fun `given relayRepository when executingUseCases then delegatesAndExposes`() = runBlocking {
    val relayA = Relay(id = "1", url = "wss://one.example")
    val relayB = Relay(id = "2", url = "wss://two.onion", isOnion = true)
    val repo = FakeRelayRepository(
        allRelaysFlow = flowOf(listOf(relayA, relayB))
    )

    val all = GetAllRelaysUseCase(repo)().first()  // Collect first item

    assertEquals(listOf(relayA, relayB), all)
}
```

**Exception Testing:**
```kotlin
@Test
fun `given non-json text when fromJson then returns null`() {
    assertNull(Event.fromJson("not json at all"))
}

@Test
fun `given a json array instead of an object when fromJson then returns null`() {
    assertNull(Event.fromJson("""["EVENT", "sub", {}]"""))
}
```

**State Testing (with Fakes):**
```kotlin
@Test
fun `given feedRepository when executingUseCases then delegatesAndExposes`() = runBlocking {
    val repo = FakeFeedRepository(...)
    
    AddFeedFilterUseCase(repo)(base)
    UpdateFeedFilterUseCase(repo)(updated)
    RemoveFeedFilterUseCase(repo)("f1")

    assertEquals(base, repo.addedFilter)
    assertEquals(updated, repo.updatedFilter)
    assertEquals("f1", repo.removedFilterId)
}
```

**Coroutine Cancellation Testing:**
```kotlin
@Test
fun `given all permits held when a suspended acquirer is cancelled then no permit is leaked`() = runTest {
    val gate = ImageLoadGate()
    repeat(6) { gate.acquire() }

    val waiter = launch { gate.acquire() }
    yield()
    waiter.cancel()
    waiter.join()

    repeat(6) { gate.release() }
    // If cancelled acquire leaked a permit, only 5 releases would be valid
}
```

## Test Organization Best Practices

**Layout per test class:**
1. Import statements
2. Test class declaration
3. Private helper functions (factories, sample data)
4. Private fake implementation classes
5. `@Test` methods (grouped logically, given/when/then order)

**Naming clarity:**
- Test name should be readable as a sentence when spoken aloud
- Use "given/when/then" to structure behavior specifications
- Avoid implementation details in test names (they can change)

**Isolation:**
- Each test is completely independent
- No shared state between tests
- No test execution order dependencies
- Fakes created fresh per test (not cached)

**Assertions:**
- One logical assertion per test (may be multiple assertEqual/assertTrue calls for a single concept)
- Clear assertion messages (use `assertEquals(expected, actual, "description")` if needed)
- No assertion frameworks beyond JUnit's Assert.*

---

*Testing analysis: 2026-09-02*
