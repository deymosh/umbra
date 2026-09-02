---
name: compose-slot-api-pattern
description: Use when designing or reviewing a reusable Jetpack Compose component whose visual regions vary by caller, or when primitive content parameters and boolean shape flags are accumulating. Technique-layer skill, framework-generic — grounded in Umbra's ui/components/ catalog.
---

# Compose: slot API pattern

## Core principle

A reusable Compose component's job is to lay things out, not to enumerate what it lays out. The moment you write `title: String, subtitle: String?, leadingIcon: ImageVector?, trailingIcon: ImageVector?, trailingText: String?, showSwitch: Boolean, switchValue: Boolean, onSwitchChange: (Boolean) -> Unit?, badge: String?, …`, the component has stopped describing a layout and started enumerating call sites — and the next call site will need a parameter the component doesn't have.

The fix is to **delegate content to the caller** via `@Composable` lambda parameters. The component contributes structure (where the leading bit, headline, supporting bit, trailing bit go). The caller contributes everything that goes *in* those slots.

Material 3's `ListItem` is the canonical example: every visual piece is a slot (`headlineContent`, `supportingContent`, `leadingContent`, `trailingContent`, `overlineContent`), not a primitive.

## When to use this skill

You're designing or reviewing a Compose component intended for reuse, its visual content varies by caller, and any of these is true:

- Its signature has `title: String`, `icon: ImageVector`, `actionText: String?`, etc. — primitive types describing *content*.
- It has multiple optional-content parameters that vary by call site.
- It has boolean flags whose only purpose is to switch between content shapes (`showChevron: Boolean`, `showSwitch: Boolean`).
- It already has *one* slot (often `trailing`) and the rest of the parameters are still primitives.

## 1. Replace primitive content with `@Composable` slots

```kotlin
// ❌ BAD — primitive parameters; trailing area is the only slot
@Composable
fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) { … }

// ✅ GOOD — every visual region is a slot
@Composable
fun SettingsRow(
    headlineContent: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) { … }
```

Call sites stay short for the typical content (`headlineContent = { Text("Account") }`) but the awkward cases (a `Text` + `Badge` in the headline, an avatar instead of an icon in the leading slot) no longer require a new primitive parameter.

### Slot naming

- `xxxContent` for free-form `@Composable () -> Unit` slots (`headlineContent`, `trailingContent`) — matches Material 3.
- A singular noun (`title`, `icon`, `actions`) when the slot is semantically constrained and the component name disambiguates (`Scaffold(topBar = { … })`).
- Don't mix `content` and other `xxxContent` slots in the same component.

## 2. Scope receivers when the slot emits into a layout

If the slot's content sits inside a `Row`/`Column`/`Box` whose layout features (`Modifier.weight`, alignment) should be available to the caller, declare the slot as a receiver lambda: `@Composable RowScope.() -> Unit`. Match the receiver to the actual parent layout the slot emits into — don't bolt one on reflexively.

## 3. Optional slots — nullable with `null` default

```kotlin
// ❌ BAD — empty default; "no leading content" is the empty lambda
leadingContent: @Composable () -> Unit = {}

// ✅ GOOD — null means "no slot"; the component can skip the slot's space/padding entirely
leadingContent: (@Composable () -> Unit)? = null
```

## 4. Defaults live in `XxxDefaults`

```kotlin
object SettingsRowDefaults {
    @Composable
    fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
}
```

## Quick reference

| Symptom | Diagnosis | Fix |
|---|---|---|
| `title: String, subtitle: String?, leadingIcon: ImageVector?` on a reusable component | Primitive content params (§1) | Convert to `xxxContent: (@Composable () -> Unit)?` slots |
| Multiple boolean flags selecting trailing shapes | Enumerating shapes (§1) | One `trailingContent` slot |
| `actions: @Composable () -> Unit = {}` inside a `Row` body | Missing scope receiver (§2) | `actions: @Composable RowScope.() -> Unit = {}` |
| `slot: @Composable () -> Unit = {}` for an optional area | Empty-lambda default (§3) | `slot: (@Composable () -> Unit)? = null` |

## When NOT to apply

- **Single-use components** — no plan to reuse, so slot flexibility isn't earning its keep yet. (As soon as a second call site appears, slot it.)
- **Design-system primitives where every caller must look identical.**
- **Constrained-type parameters that genuinely are constrained** (`Switch(checked: Boolean, onCheckedChange: ...)` doesn't need its indicator to be a slot).
- **Performance-critical fast paths** (rare in app code) — a slot is an allocated lambda.

## In Umbra

Every reusable composable lives under `app/src/main/java/com/umbra/app/ui/components/` per CLAUDE.md, and duplicating one there instead of extending it is a documented review flag. Before adding a new content parameter to an existing component in that folder (`EventCard`/`NotesFeedSection`-adjacent row components, `RelaySections`, `UserAvatar`), check whether the component is trending toward primitive-parameter accumulation — that's the signal to slot it instead of adding parameter #6.

## Related

- [`compose-modifier-and-layout-style`](../compose-modifier-and-layout-style/SKILL.md) — the modifier-parameter rule travels with slot APIs. A reusable component takes both a `modifier` parameter *and* slots — caller owns placement *and* what to place.
