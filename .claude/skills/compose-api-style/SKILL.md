---
name: compose-api-style
description: Shaping a composable's API — Screen/Content split (ViewModel wiring vs stateless layout), the modifier parameter and chains, slot parameters vs piling-up content params and boolean flags.
---

# Compose API style

## 1. Screen / Content split

A screen composable taking a ViewModel only wires things up:
- collect state
- run one-shot effects (navigation, Amber, snackbars)
- create launchers
- pass plain `@Immutable` state plus lambdas to an `internal fun XContent(...)` that only lays out

```kotlin
@Composable
fun BlossomServersScreen(onNavigateBack: () -> Unit, viewModel: BlossomServersViewModel) {
    val state by viewModel.state.collectAsState()
    // LaunchedEffects for snackbars / navigation here
    BlossomServersContent(state = state, onSave = viewModel::save, /* ... */)
}
```

- `Content` never sees a ViewModel, NavController or Hilt. That is what makes it previewable and **snapshot-testable** (every Roborazzi test renders a `Content`; see `umbra-design`).
- `Content` can still own UI-local state (scroll, focus, `rememberSaveable` toggles).
- Children get only the state and callbacks they need, never the ViewModel.
- Navigation is a callback (`onBack`, `onItemClick`). Routes live in `NavHost.kt`.
- Tiny leaf composables and `ui/components/` primitives don't need this.

## 2. The `modifier` parameter

- Any composable that emits layout takes `modifier: Modifier = Modifier`, placed after required params and before trailing lambdas.
- Apply it to the **root, first**: `modifier.clip(CircleShape).size(48.dp)`. The caller's modifier is outermost so it can override.
- Don't hardcode placement on the root (`fillMaxWidth`, outer padding). The parent decides. Identity modifiers (a clip shape) are fine after the caller's modifier.
- Build chains as one fluent expression, with conditionals via `.then(if (x) Modifier.y() else Modifier)`. Never `var m = Modifier; m = m...`.
- Use one call per line once there are 3 or more.
- A layout whose only child is a single `if` → hoist the `if` outside. That doesn't apply when the container has its own modifier, alignment or siblings.

## 3. Slots over content primitives

Once a reusable component has several `title: String` / `icon: ImageVector?` / `showX: Boolean` params that vary by caller, switch to slots:

```kotlin
fun SettingsRow(
    headlineContent: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,   // optional → nullable, null default
    trailingContent: (@Composable RowScope.() -> Unit)? = null // scope receiver when it emits into a Row
)
```

- Name slots `xxxContent` (Material 3 style). Use a plain noun (`topBar`, `actions`) when it's semantically constrained.
- Put shared defaults in an `XxxDefaults` object (see `UmbraTopAppBarDefaults`).
- Don't slot single-use components or genuinely constrained params (`checked: Boolean`).
- Reusable composables live in `ui/components/`. Extend one there rather than duplicating it. Adding a 6th content param is the signal to slot it.

Effects inside these composables: see `compose-side-effects`.
