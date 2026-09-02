---
name: compose-modifier-and-layout-style
description: Use when writing or reviewing Jetpack Compose layout APIs, modifier parameters, modifier chain construction, hardcoded root layout decisions, or layout wrappers around a single conditional. Technique-layer skill, framework-generic — grounded in Umbra's own `ui/components/` catalog rather than any other client's code.
---

# Compose modifier and layout style

## Core principle

A composable that emits layout is a leaf the *parent* places — the parent decides position, size, alignment, padding. The composable's job is structure (what's inside), not placement (where it goes). Three rules follow:

- **Declare a `modifier` parameter and apply it to the root**, so the parent can actually do its job. Hardcoding `.fillMaxWidth()` on a composable's root takes that decision away from every future caller.
- **Construct modifier chains as one fluent expression**, not stepwise reassignments. Both compile to the same thing, but the chain *reads* as intent in one pass.
- **Conditional rendering belongs where the condition applies.** A layout call whose only content is one `if` exists solely to hold the condition — push the `if` outside instead.

These travel together because the same composable usually triggers all three: you declare its parameters (rule 1), the caller constructs a chain to position it (rule 2), and the body has a conditional you might be tempted to wrap (rule 3).

## When to use this skill

- You're writing a `@Composable fun` that calls a layout (`Box`, `Column`, `Row`, `LazyColumn`, `Text`, `Image`, `Surface`, `Card`, `Layout { … }`, anything from `compose.foundation.layout` or `compose.material*`) and its signature has no `modifier` parameter, or has one that isn't applied to the root, or has a hardcoded `.fillMaxWidth()`/`.padding(...)` on the root.
- You see `var m = Modifier` followed by `m = m.padding(…)`, `m = m.background(…)`, etc.
- A `modifier = …` argument has three or more chained calls on a single line.
- A composable's body is `Layout { if (cond) Content() }` — one conditional, nothing else.
- You're adding a new reusable composable to `app/src/main/java/com/umbra/app/ui/components/` (CLAUDE.md's designated home for reusable components — `UserAvatar`, `NostrTextRenderer`, `AmberSignEffect`, `ExternalUrlWarningDialog`, etc.).

## 1. Declare a `modifier` parameter

For composables that emit layout, prefer a `modifier` parameter after required parameters and before content/lambda parameters, with a default of `Modifier`. The name is exactly `modifier` — not `mod`, not `m`, not `wrapperModifier`.

```kotlin
// ❌ BAD — no modifier param; caller can't position, size, or constrain this
@Composable
fun HomeScreenHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}
```

```kotlin
// ✅ GOOD — parent decides width and padding; the composable describes structure only
@Composable
fun HomeScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}
```

## 2. Apply the caller's modifier to the root, and apply it first

```kotlin
// ❌ BAD — caller's modifier ends up last, so the composable's own size wins
@Composable
fun Avatar(url: String, modifier: Modifier = Modifier) {
    Image(
        painter = rememberAsyncImagePainter(url),
        contentDescription = null,
        modifier = Modifier.clip(CircleShape).size(48.dp).then(modifier),
    )
}

// ✅ GOOD — caller's modifier first, then the composable's intrinsic chain
@Composable
fun Avatar(url: String, modifier: Modifier = Modifier) {
    Image(
        painter = rememberAsyncImagePainter(url),
        contentDescription = null,
        modifier = modifier.clip(CircleShape).size(48.dp),
    )
}
```

Order matters: the *earlier* segment in a chain is the outer wrapper. The caller's modifier should be outermost so caller-provided `.size(...)`/`.padding(...)` can override the composable's own defaults.

## 3. Don't hardcode layout decisions on the root

```kotlin
// ❌ BAD — every caller now fills max width whether they want to or not
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth()) { Text(text) }
}

// ✅ GOOD — caller adds .fillMaxWidth() if (and only if) they want it
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) { Text(text) }
}
```

The carve-out is for modifiers that are part of the composable's **identity** (`.clip(CircleShape)` on `Avatar`), not where it sits on the screen. Test: can you imagine a caller wanting this composable *without* that modifier? If yes, push it out — but keep it *after* the caller's modifier in the chain (§2).

## 4. Construct modifier chains as one fluent expression

```kotlin
// ❌ BAD
var m = Modifier
m = m.padding(16.dp)
m = m.fillMaxSize()
Box(m) { }

// ✅ GOOD
val m = Modifier.padding(16.dp).fillMaxSize()
Box(m) { }
```

Conditional segments stay on the chain via `.then(...)`, not a `var`:

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()
        .then(if (selected) Modifier.background(Color.Red) else Modifier),
)
```

## 5. Multiline formatting

Three or more chained calls on a `modifier = …` argument → one call per line, indented under the value. Below three, one line is fine.

## 6. Hoist single conditionals out of the layout

```kotlin
// ❌ BAD — Column always emitted; only its inner content is conditional
Column { if (showHeader) { Text("Title"); Text("Subtitle") } }

// ✅ GOOD — Column only exists when it has content
if (showHeader) {
    Column { Text("Title"); Text("Subtitle") }
}
```

Carve-outs: keep the layout as-is when it carries visual semantics that aren't conditional (`modifier`, `contentAlignment`, arrangement on the container), when there are sibling composables beside the `if`, or when it's `if … else …` with both branches contributing content.

## Quick reference

| Symptom | Diagnosis | Fix |
|---|---|---|
| `@Composable fun Foo(text: String)` with layout in body, no `modifier` param | §1 | Add `modifier: Modifier = Modifier`; pass to root |
| `modifier` declared but never applied, or applied to a child not the root | §2 | Apply to the outermost layout's `modifier` arg |
| `modifier = Modifier.x().y().then(modifier)` | Caller's modifier last | Reorder: `modifier.x().y()` |
| `modifier = modifier.fillMaxWidth().padding(...)` on a general-purpose component | §3 | Remove hardcoded calls; let callers add them |
| `var m = Modifier` + reassignments | §4 | One fluent chain on a `val`, or inline |
| `Layout { if (cond) X() }`, no other content, no layout-tuning args | §6 | Move the `if` outside the layout |

## When NOT to apply

- Composables that don't emit layout (`@Composable @ReadOnlyComposable fun computeColor(): Color`).
- `@Preview` functions and test-only composables — no real caller to hand a modifier to.
- Modifier assembled imperatively from `Animatable`/procedural animation state — the chain isn't the goal, readability is.

## In Umbra

Every reusable composable belongs under `app/src/main/java/com/umbra/app/ui/components/` per CLAUDE.md — before adding a new one, check that folder for something to extend instead of duplicating (`UserAvatar`, `NostrTextRenderer`, `AmberSignEffect`, `ExternalUrlWarningDialog`, `NotesFeedSection`, `RelaySections`). When reviewing or writing one of those, this skill's §1–§3 are the bar: does the component take `modifier: Modifier = Modifier` and apply it to its root first, or does it bake in layout decisions that belong to whichever screen embeds it?

## Related

- [`compose-slot-api-pattern`](../compose-slot-api-pattern/SKILL.md) — the other half of a reusable composable's public API: `@Composable () -> Unit` slots for variable content. A reusable component takes both a `modifier` parameter *and* slots.
