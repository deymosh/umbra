---
name: kotlin-types-value-class
description: Use when writing or reviewing Kotlin type declarations to choose @JvmInline value class over data class where appropriate, including Compose stability implications. Technique-layer skill — Umbra has zero value classes today, so this is about new code, not a retrofit.
---

# Kotlin value class vs data class

## Core principle

Prefer `@JvmInline value class` for single-field types that carry domain meaning. Data classes are for aggregating multiple fields. A value class gives you type safety (you can't mix up `UserId` and `String`) without the allocation overhead of a data class.

## When to use this skill

- Writing a new Kotlin type that wraps a single value.
- Reviewing a data class that has only one property.
- Seeing primitive types (`String`, `Long`, `Int`) used where a domain type would prevent misuse.
- Compose compiler reports showing unstable parameters that could be value classes.

## Decision flow

| Situation | Prefer |
|---|---|
| Single field + domain-meaningful (`UserId`, `EmailAddress`, `Percentage`) | `@JvmInline value class` |
| Single field + no domain meaning | Type alias or keep the primitive |
| Multiple fields | Data class |
| Needs custom `equals`/`hashCode`/`toString` beyond the wrapped value | Data class |
| Used as a generic type argument or nullable in hot paths | Data class or primitive (autoboxing cost) |

```kotlin
// GOOD
@JvmInline value class UserId(val value: String)

// BAD: data class wrapping a single field — unnecessary allocation
data class UserId(val value: String)

// BAD: value class with no domain meaning — just use the String
@JvmInline value class Wrapper(val value: String)
```

## Compose stability

`@JvmInline value class` is treated as `Stable` by the Compose compiler when its underlying type is stable (primitives, `String`). Value classes passed as composable parameters avoid "unstable parameter" warnings, and replacing a single-field data class with a value class at a UI boundary improves skippability without needing `@Immutable`.

## Gotchas

- **Autoboxing**: unboxed at compile time, but boxed when nullable, a generic type argument, or vararg. Matters in hot paths, not most code.
- **No backing fields**: no `init` blocks, `lateinit`, or `by lazy` — the class body is extremely constrained.
- **No `copy()`/destructuring/custom `toString()`** — need any of those, use a data class.
- **No custom `equals`/`hashCode`/`toString`** — always delegates to the underlying type.
- **kotlinx.serialization**: a `@Serializable data class A(val value: String)` serializes as `{"value":"..."}`; a `@Serializable value class A(val value: String)` serializes as the bare underlying value (`"..."`). Swapping one for the other is a breaking change for a JSON contract already in use.
- **Interop/reflection**: from Java it appears as the underlying type — the type-safety wrapper is bypassed. Reflection/DI/some serializers may see the underlying type in generic contexts.

## Packing multiple values (rare, performance-critical only)

`androidx.compose.ui.util` has `packFloats`/`packInts`/`unpackFloat1`/`unpackFloat2` etc. to store multiple primitives in one `Long` inside a value class. Only use this in genuinely performance-critical paths — a data class is simpler and safer for most UI types.

## Common mistakes

| Mistake | Fix |
|---|---|
| Data class wrapping a single domain field | Replace with `@JvmInline value class` |
| Value class with no domain meaning | Type alias or the primitive directly |
| Value class needing custom equality | Data class instead |
| `@Immutable` annotation on a single-field wrapper | Replace with value class — it's `Stable` by default |

## When NOT to apply

- Multiple fields → data class.
- Custom `equals`/`hashCode`/`toString` needed → data class.
- Heavy nullable/generic use in performance-critical code → measure autoboxing cost first.
- No real type-safety distinction needed → a type alias or primitive is sufficient.

## In Umbra

**Zero `@JvmInline value class` usages exist anywhere in the app module today** — this would be a pattern Umbra adopts fresh, not one it already has conventions for. Don't retrofit it onto existing code as a drive-by cleanup (CLAUDE.md: don't refactor beyond what a task requires).

The realistic candidate, if it ever comes up: Umbra's domain layer passes pubkeys, event ids, and other 32-byte-hex Nostr identifiers around as bare `String` throughout (`domain/nip01`, `domain/relay`, repository interfaces) — the exact "primitive types used where a domain type would prevent misuse" smell this skill describes (a `pubkey` and an `eventId` are both `String` and nothing stops one being passed where the other is expected). That's a legitimate future hardening, **not** a task to take on unprompted — it would touch a very large surface (every repository, use case, and UI state referencing a pubkey/event id) for a type-safety win, not a bug fix. Only pursue it if a task specifically calls for it, and scope it narrowly (e.g. one new NIP's parser) rather than a codebase-wide sweep.

## Related

- [`compose-stability-diagnostics`](../compose-stability-diagnostics/SKILL.md) — diagnosing unstable Compose parameters; value classes are one fix.
