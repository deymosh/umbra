# Contributing to Umbra

Thank you for your interest in contributing to Umbra! We welcome bug reports, feature requests, and code contributions.

## Before You Start

Please read [AUDIT.md](AUDIT.md) — it contains mandatory security, privacy, and architecture rules. All code must follow these rules.

**Key principles:**
- **Security first** — No compromises on TOR routing, key management, or privacy
- **Censorship-resistant** — Content moderation (muting, NSFW hiding, feed filters) is always user-controlled and fully editable/removable; defaults exist, nothing is hardcoded or forced by the app
- **Clean Architecture** — UI → Domain → Data (no circular dependencies)
- **Tests required** — Every feature or fix must include unit tests
- **English only** — All comments, commits, and documentation in English

## Setting Up

### Prerequisites

- JDK 17 (for development)
- Android SDK 37 (API level)
- Gradle 9.x (included via wrapper)
- Git

### Local build

```bash
# Clone the repo
git clone https://github.com/deymosh/umbra.git
cd umbra

# Create local.properties with your SDK path
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build and install on device/emulator
./gradlew installDebug        # Unix/macOS/Linux
.\gradlew.bat installDebug    # Windows
```

Linux without a JDK/Android SDK yet: run `scripts/install-toolchain.sh` first — see the root [README.md](README.md#quick-start) for the one-time setup.

### Running tests

```bash
./gradlew testDebugUnitTest       # Unix/macOS/Linux — full unit suite
./gradlew compileDebugKotlin      # fast type-check only
./gradlew assembleDebug           # assemble APK, no install
```

Use the `.\gradlew.bat` equivalents on Windows. All tests must pass before submitting a PR.

## How to Contribute

### Reporting bugs

Open a GitHub issue with:
1. **What you did** — Steps to reproduce
2. **What you expected** — What should happen
3. **What happened** — What actually occurred
4. **Device info** — Android version, Umbra version

Example:
```
Feed shows hex pubkey instead of NIP-05 badge when viewing profile.

Expected: Badge shows "alice@example.com ✓"
Actual: Shows "a1b2c3d4..." hex
Device: Android 14, Umbra 0.1.0
```

### Requesting features

Open a GitHub issue titled `[Feature] Your idea` with:
1. **Problem statement** — What's the pain point?
2. **Proposed solution** — How should it work?
3. **Impact on privacy** — Does this leak data or require TOR bypass? (Usually answer is "no")

### Submitting code

1. **Fork and branch**
   ```bash
   git checkout -b fix/nip05-badge-display
   ```

2. **Make changes**
   - Read the file before editing it
   - One concern per commit (small, focused changes)
   - Write tests for every new feature or bug fix

3. **Run tests and lint**
   ```bash
   ./gradlew testDebugUnitTest compileDebugKotlin    # .\gradlew.bat on Windows
   ```

4. **Commit with clear messages**
   ```
   type(scope): short description

   - bullet point explaining what changed and why
   - another bullet point if needed
   ```

   Valid types: `feat` `fix` `refactor` `perf` `security` `test` `docs` `chore`

   Example:
   ```
   fix(feed): surface nip05 state from user profile

   - EventDao now joins p.nip05VerificationState in feed queries
   - EventCard displays badge when state is Verified
   - Added EventCardNip05StateTest to verify rendering
   ```

5. **Create a pull request**
   - Link to related issues
   - Describe what you changed and why
   - Confirm all tests pass

## PR Checklist

Before submitting, verify:

- [ ] `./gradlew testDebugUnitTest` passes (`.\gradlew.bat` on Windows)
- [ ] `./gradlew compileDebugKotlin` succeeds with no errors
- [ ] All new code has unit tests (minimum: happy path + error case)
- [ ] No secrets in code (no API keys, no hardcoded URLs, no `nsec` anywhere)
- [ ] No absolute file paths (e.g., `C:\Users\...`)
- [ ] No machine/username references
- [ ] All comments in English
- [ ] Commit message follows the format above
- [ ] Read [AUDIT.md](AUDIT.md) and followed the rules

## Architecture Guidelines

### File structure

The full directory-by-directory layer map lives in [AUDIT.md](AUDIT.md) Part 6 — read that instead of a second copy here, since a tree duplicated across two files drifts out of sync as the codebase evolves (this one already had). In short: `data/`, `domain/`, and `ui/` follow Clean Architecture layering, with NIPs organized one package per protocol under `domain/nipXX/` (see [docs/nip-social-coverage.md](docs/nip-social-coverage.md) for what each NIP covers).

### Key rules

The complete, current rule set is [AUDIT.md](AUDIT.md) — read it before writing code, not just this summary:

- **ViewModels never import from `data.*`** — always go through domain (UseCase or Repository interface)
- **All UI state is `@Immutable`**, via `StateFlow<T>` only — never `LiveData`
- **All DAO calls on `Dispatchers.IO`**
- **TOR is mandatory** — every network call checks `TorProxyConfig.isReady` first
- **Tests required** — every UseCase, Repository method, mapper, and ViewModel needs tests
- **Use cases are plain Kotlin** — one `operator fun invoke()` method, no Hilt

## Testing Standards

### What to test

- **UseCase logic** — Happy path + error cases
- **Repository methods** — With fake/mock data sources
- **Event builders** — Verify tag structure matches Nostr spec
- **Mappers** — Test round-trip (Entity → Domain → Entity)
- **Security-critical code** — Verify crypto, Tor checks, key handling

### Test naming

Use BDD pattern:
```kotlin
@Test
fun `given_X_when_Y_then_Z`() {
    // Arrange
    val input = ...
    val useCase = ...

    // Act
    val result = useCase(input)

    // Assert
    assertEquals(expected, result)
}
```

### Test location

- Unit tests: `app/src/test/java/com/umbra/app/`
- Mirror package structure of class under test
- Use existing test infrastructure (FakeXxx, runTest, etc.)

Example:
```
app/src/test/java/com/umbra/app/
  domain/usecase/
    VerifyNip05UseCaseTest.kt
  data/repository/
    UserRepositoryImplTest.kt
```

## Security Review

Security-sensitive changes (network, crypto, key handling, logging, persistence, signing) require extra attention — see [AUDIT.md](AUDIT.md) Part 1 for the full rule set and its "what to flag" checklists per topic.

If you're unsure, ask before implementing. Better to discuss than to rework.

## Code Style

- **Kotlin idioms** — Prefer functional style where it improves clarity
- **Naming** — Use descriptive names; abbreviations only for well-known terms (e.g., `pubkey`, `nip05`)
- **Comments** — Explain *why*, not *what*. Code should be self-documenting.
- **Line length** — Aim for ~120 characters
- **Imports** — Use short imports; `import R` for resources

## Reporting Security Issues

Do **not** open a public GitHub issue for security vulnerabilities.

See [SECURITY.md](SECURITY.md) for private disclosure instructions.

## Questions?

Open a GitHub discussion or issue. The maintainers are happy to help!

---

Thank you for contributing to Umbra. ❤️
