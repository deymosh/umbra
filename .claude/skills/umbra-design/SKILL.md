---
name: umbra-design
description: Any UI/UX work — new or changed screens, components, colours, type, icons, copy, motion. Umbra's "Totality" design system and how to review UI with snapshots.
---

# Umbra design

Umbra's identity is a solar eclipse at totality: a black disc, a lavender corona, one gold
"diamond ring" flare. The app is a privacy tool, so the UI is calm, dark and precise; trust comes
from restraint, not decoration. Adapted from Anthropic's frontend-design skill: start from the
subject, make deliberate choices, **spend boldness in one place**.

## Process

1. **Plan before code.** Which existing tokens/components apply? What is the one thing on this
   screen that should be memorable (usually nothing — the corona already has that job)?
2. **Build** with tokens only (below). No raw `Color(0x…)`, no ad-hoc radii/type sizes.
3. **See it.** Add/update a snapshot test (`docs/UI_SNAPSHOTS.md`), run
   `./gradlew recordRoborazziDebug --tests '*SnapshotTest*'` and *look at the PNG*.
4. **Critique** against the checklist, fix, re-render. Commit the goldens with the change.

## Tokens (`ui/theme/`)

- **Surfaces** (darkest → lightest): `background` (void) → `surfaceContainerLowest/Low/…/Highest`.
  Group content on `surfaceContainer`; raise popovers/sheets one step. Never tint with
  `surfaceVariant.copy(alpha)`, and never rely on `tonalElevation` — tonal tint is disabled.
- **Text**: `onSurface` primary, `onSurfaceVariant` secondary. Hairlines: `outlineVariant`.
- **Semantic** (`UmbraTheme.colors`): `secure` (Tor up, verified, healthy), `caution`
  (connecting/degraded), `like`, `repost`, `zap`, `corona` (primary action + brand glow only).
  Colour always means something; never decorative.
- **Type** (`UmbraTypography`): Geist for UI/reading; `MonoStyle` (Geist Mono) for npubs, hex,
  event ids, relay URLs; Instrument Serif (`display*`) for the wordmark/hero only.
- **Shapes**: `MaterialTheme.shapes` — small 10 (chips/icons), medium 16 (cards/media), large 22
  (groups), extraLarge 28 (sheets). Pills use `CircleShape`.
- **Spacing**: 4-pt grid; 16dp screen edge; note text column starts at 70dp (avatar gutter).

## Components to reuse (`ui/components/`)

`EclipseMark` (brand, animatable `ignition`), `PubkeyEclipseAvatar` (via `UserAvatar`),
`UmbraIcons.Onion` (the Tor onion — Tor state only), `NetworkStatusPill`, `NoteAuthorLine`,
`ReactionBar`, `QuickActionBottomBar`, `SettingsGroup` + `MenuItemRow`, `ErrorBanner`,
`EmptyState`, `ActionsBottomSheet`, `ConfirmDialog`, `ExternalUrlWarningDialog`, `ChipBadge`.
Extend these rather than restyling locally.

## Rules

- **One bold element.** The corona (TorGate/Login hero, compose button, primary buttons). Everything
  else quiet: hairlines over boxes, flat rows over cards-in-cards.
- **Motion** only to show state change; the TorGate ignition is the single orchestrated moment.
  Infinite animations need an `animate` flag so snapshot tests can go idle.
- **Copy**: sentence case, plain verbs, user's point of view; buttons say what happens
  ("Open outside Tor", not "OK"). No ALL-CAPS labels, no emoji as UI.
- **Security UX**: the safe choice is the prominent one (e.g. "Stay private" is the filled button
  on the leave-Tor dialog). Never show an unverified NIP-05 as the handle.
- **Touch targets** ≥ 40dp; never `Modifier.scale()` a control down.
- **Fonts are bundled** — never the downloadable-fonts provider (it bypasses Tor).
- Screens split into `XScreen(viewModel)` → stateless `XContent(state, callbacks)` so they can be
  snapshotted.

## Review checklist

- Uses only tokens/components above; no new one-off colours or radii.
- Hierarchy readable in one glance: one primary action, secondary actions quieter.
- Identifiers in mono; timestamps compact; counts hidden at zero.
- Works with long names/handles (ellipsis), empty and error states.
- Snapshot rendered and inspected; goldens updated.
