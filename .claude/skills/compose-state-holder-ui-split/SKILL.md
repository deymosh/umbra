---
name: compose-state-holder-ui-split
description: Use when a Jetpack Compose screen-level composable takes a ViewModel directly, collects state or effects, handles navigation/snackbars, or wires callbacks while also rendering layout. Technique-layer skill, framework-generic — this is already CLAUDE.md's mandated ui/screen -> ui/viewmodel architecture, so use this skill to check a screen is actually following it, not to introduce something new.
---

# Compose: state holder/UI split

## Core principle

Separate state-holder wiring from UI rendering. The state-holder composable talks to ViewModels, flows, navigation, and side effects. The UI composable takes plain immutable UI state plus callbacks and describes layout.

**This is not optional guidance for Umbra — it's already policy.** CLAUDE.md mandates the `ui/screen → ui/viewmodel → domain/usecase → domain/repository` layering and `StateFlow<UiState>` exclusively (`@Immutable` state data classes). This skill is the fine-grained version of that rule *inside* a single screen file: even within `ui/screen`, don't let ViewModel-collection and pure layout blur into one function.

## When to use this skill

Use this when a Compose screen:

- Takes a ViewModel directly and collects its state in the same function that lays out most UI, with no plain-UI overload underneath.
- Passes the whole ViewModel into child composables instead of explicit state and callbacks.
- Is hard to preview because it needs Hilt injection, navigation, or lifecycle.

## The pattern

```kotlin
@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel(), modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ProfileScreen(
        state = state,
        onNameChange = viewModel::onNameChange,
        onSaveClick = viewModel::save,
        onBackClick = viewModel::back,
        modifier = modifier,
    )
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Layout only. No ViewModel reference here.
}
```

## Rules of thumb

| Concern | State-holder composable | UI composable |
|---|---|---|
| Collect ViewModel `StateFlow`/`SharedFlow` | Yes | No |
| Collect one-shot effects (navigation, Amber sign results) | Yes, or a tiny sibling effect handler | Usually no |
| Accept immutable `@Immutable` UI state | Usually passes it through | Yes |
| Accept lambdas for user events | Wires them to ViewModel functions | Calls them |
| Own layout, modifiers, semantics | No/minimal | Yes |
| Own UI-local state (scroll, focus, text input, animation) | Sometimes seeds it | Yes |

Plain UI composables can still own UI-local framework state — `rememberScrollState`, `rememberLazyListState`, `FocusRequester`, `MutableInteractionSource.collectIsPressedAsState()` — that's not "business state," it belongs to the rendered widget.

## What to pass

- Prefer the screen's `@Immutable` UI state object over many unrelated primitives.
- Prefer explicit lambdas (`onRetryClick`, `onItemSelected`) over passing the whole ViewModel.
- Keep domain models out of the UI composable when they'd force business rules into UI — map to a UI-shaped model at the ViewModel boundary.
- Keep navigation as callbacks — the UI composable says "user clicked back," not "navigate to route X" (Umbra's `NavHost.kt` owns route strings; see [`umbra-android-platform`](../umbra-android-platform/SKILL.md)).

## Side effects

See [`compose-side-effects`](../compose-side-effects/SKILL.md) for effect APIs, keys, and cleanup. Handle effects near the state-holder composable:

```kotlin
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ProfileEffect.Saved -> snackbarHostState.showSnackbar("Saved")
            }
        }
    }

    ProfileScreen(state = state, onSaveClick = viewModel::save)
}
```

## Common mistakes

| Mistake | Why it hurts | Fix |
|---|---|---|
| `fun Screen(viewModel: MyViewModel)` contains all layout, no plain-UI overload | Hard to preview/test without Hilt/lifecycle | Add a plain UI overload that takes `state` and callbacks |
| Child composables take the ViewModel | Dependencies leak through the tree | Pass only the state/callbacks the child needs |
| UI composable launches navigation directly | UI becomes coupled to app routing | Expose `onBackClick`, `onItemClick`, etc. |
| UI composable collects `StateFlow` itself | Collection lifecycle hidden inside layout | Collect once at the state-holder composable, pass values down |

## When NOT to apply

- Tiny one-off composables that already take plain values and callbacks.
- Reusable `ui/components/` primitives (`UserAvatar`, `NostrTextRenderer`) — those should expose slots and modifiers ([`compose-slot-api-pattern`](../compose-slot-api-pattern/SKILL.md), [`compose-modifier-and-layout-style`](../compose-modifier-and-layout-style/SKILL.md)), not ViewModels.

## Related

- [`compose-side-effects`](../compose-side-effects/SKILL.md) — effect keys and cleanup in Compose.
- [`compose-state-deferred-reads`](../compose-state-deferred-reads/SKILL.md) — deferred reads for frame-rate/UI-local values passed across boundaries.
- [`umbra-android-platform`](../umbra-android-platform/SKILL.md) — navigation, routes, and where screen scaffolding actually lives in Umbra.
