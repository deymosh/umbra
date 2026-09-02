# Umbra coding instructions

Before answering or generating code, read the project guidance in [README.md](../README.md), [CONTRIBUTING.md](../CONTRIBUTING.md), and [AUDIT.md](../AUDIT.md). Those documents define the repository’s mandatory rules.

## Core expectations
- Umbra is a privacy-first Android app written in Kotlin. Preserve the TOR-only design and never introduce a network path that bypasses the proxied client.
- Follow Clean Architecture: UI → domain → data. Keep ViewModels and composables on the domain boundary, not the data layer.
- Use immutable UI state, StateFlow, and repository/use-case abstractions rather than ad-hoc state handling.
- Security and privacy rules are non-negotiable: no plaintext fallback, no private-key material in code or logs, and no signing logic outside the approved Amber integration path.
- Write or update tests for behavior changes and bug fixes.

## Working conventions
- Read relevant files before editing them. Prefer small, focused changes.
- Reuse existing UI components before creating new ones.
- Keep comments and documentation in English.
- Use the Gradle wrapper for local verification, matching whichever shell you're in — `./gradlew` on Unix/macOS/Linux, `.\gradlew.bat` on Windows:
  - `compileDebugKotlin`
  - `testDebugUnitTest`

## Important references
- [AUDIT.md](../AUDIT.md) — mandatory security, privacy, architecture, and persistence rules
- [CONTRIBUTING.md](../CONTRIBUTING.md) — development workflow, tests, and PR expectations
- [agents/umbra.agent.md](agents/umbra.agent.md) — agent-specific workflow for this repository