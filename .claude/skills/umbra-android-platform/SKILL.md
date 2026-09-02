---
name: umbra-android-platform
description: Use when working with Umbra's navigation (NavHost.kt, Screen routes), permissions/ActivityResultContracts, external URL launching, Coil setup, or MainActivity's single-activity structure. Adapted from a much larger android-expert skill built for a multi-platform codebase — Umbra has no Desktop/iOS counterpart, so there's no shared-vs-platform-specific decision to make; everything here just is the app.
---

# Android platform patterns in Umbra

Umbra is single-module, Android-only — there's no "should this be shared with Desktop/iOS" question the way a KMP structure forces one. This skill is about Umbra's own conventions for navigation, permissions, external links, and image loading, not a platform-abstraction decision framework.

## Navigation: string routes, not type-safe

`ui/NavHost.kt`'s `UmbraNavHost(deepLinkUri: String? = null)` uses a plain `sealed class Screen(val route: String)` with hand-built path/query strings — **not** `@Serializable` type-safe routes:

```kotlin
object Thread : Screen("thread/{eventId}") { fun forEvent(eventId: String) = "thread/${Uri.encode(eventId)}" }
object Composer : Screen("composer?replyTo={replyTo}") { fun new() = "composer"; fun reply(eventId: String) = "composer?replyTo=${Uri.encode(eventId)}" }
```

Follow this shape for a new screen — a `Screen` object with a `route` template and a factory function that `Uri.encode`s any path/query args, not a raw string built inline at the call site.

**`Screen.RelayGraph`** is a nested `navigation()` graph wrapping `RelayConfig`/`RelayDetails`/`ActiveSubscriptions`, sharing one `RelayConfigViewModel` via `hiltViewModel(navController.getBackStackEntry(Screen.RelayGraph.route))` instead of each screen minting its own. This exists specifically because a screen-scoped `hiltViewModel()` re-ran `init{}`'s flow collection from scratch on every hop between sibling screens (see [`nostr-performance-review`](../nostr-performance-review/SKILL.md)). **If a new cluster of screens shares state and users navigate between them frequently, this nested-graph-plus-shared-ViewModel pattern is the one to reach for**, not a screen-scoped `hiltViewModel()` per screen.

Custom forward/back slide transitions (300ms `tween`) exist with a `shouldSkipNavAnimation` bypass for the bootstrap→feed transition — check that flag before adding a new animated transition that might need the same bypass for a cold-start path.

## Permissions: only photo-picker contracts, no runtime prompts

`ActivityResultContracts`/`rememberLauncherForActivityResult` shows up in exactly `ComposerScreen.kt` (`PickMultipleVisualMedia`, `PickVisualMediaRequest(ImageAndVideo)`), `EditProfileScreen.kt` (`PickVisualMedia()` for avatar/banner), and `AppSessionEffects.kt`. **There are no runtime dangerous-permission requests anywhere** (no camera/location/notifications prompts, no Accompanist `rememberPermissionState`) — the photo picker doesn't need a manifest permission on API 33+ (minSdk is 26, but the picker contract handles the pre-33 fallback internally). If a future feature genuinely needs a runtime permission (camera capture, notifications), it'll be the first of its kind in this codebase — there's no existing pattern to copy, so check Android's current `rememberPermissionState`/`ActivityResultContracts.RequestPermission()` guidance directly rather than assuming an Umbra convention exists.

## External URLs: shared launcher + dialog, called at each site

`ui/components/ExternalUrlLauncher.kt` exposes `launchExternalUrl(context, rawUrl): Boolean` — validates via `normalizeAndValidateExternalUrl`, restricts to http/https, fires `ACTION_VIEW` with `FLAG_ACTIVITY_NEW_TASK`. Paired with `ui/components/ExternalUrlWarningDialog.kt`. **It is not a single all-in-one function** — every call site (`EventCard.kt`, `ProfileScreen.kt`, `RelayConfigScreen.kt`, `LoginScreen.kt`, `TorGateScreen.kt`) owns its own `pendingExternalUrl` state and wires the dialog manually:

```kotlin
pendingExternalUrl?.let { url ->
    ExternalUrlWarningDialog(url = url, onConfirm = { launchExternalUrl(context, url); pendingExternalUrl = null }, onDismiss = { pendingExternalUrl = null })
}
```

CLAUDE.md's rule ("every externally-opened URL shows `ExternalUrlWarningDialog` first, except Amber intents") means: **a new screen that opens external links reproduces this exact `pendingExternalUrl` + dialog pattern, it does not call `launchExternalUrl` directly.** A raw `launchExternalUrl(context, url)` call with no preceding dialog is a review flag every time, not just in these five files.

## Coil: the one and only allowed `OkHttpClient.Builder()`

Built in `data/di/NetworkModule.kt`'s `provideImageLoader`, explicitly injecting `@Named("tor") OkHttpClient` via `.okHttpClient(torOkHttpClient)`. The comment there states this is the **only** allowed place for `OkHttpClient.Builder()` — everything else must use the injected `@Named("tor")` client (CLAUDE.md's no-second-client rule). Also builds a custom `DeferredDeleteFileSystem` for disk-cache eviction on its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, `maxSizePercent(0.25)` memory cache, 256MB disk cache, `respectCacheHeaders(false)`. **Don't build a second `ImageLoader` or a raw `OkHttpClient` anywhere else** — Coil, Media3, and the relay WebSocket client all share this one instance by design.

## `MainActivity.kt`

Single `@AndroidEntryPoint class MainActivity : ComponentActivity()`. `enableEdgeToEdge()` before `setContent`; content is `UmbraTheme { UmbraNavHost(deepLinkUri = deepLinkUri) }`; provides `LocalMediaLoadPriorityGate`/`LocalImageLoadGate` via `CompositionLocalProvider`; parses a `nostr:` deep link from `intent.data` on `ACTION_VIEW` and hands it to `UmbraNavHost`. Also fires a one-time battery-optimization exemption request (`BatteryOptimizationHelper`) — relevant if you're touching anything about background Tor/relay connectivity persistence.

## Don't

- Don't add a `@Serializable` type-safe-navigation route as a one-off — the whole `NavHost` is string-route-based; a mixed scheme is worse than either pure approach. If type-safe nav is ever adopted, it's a deliberate whole-file migration, not a per-screen choice.
- Don't call `launchExternalUrl` without the preceding `ExternalUrlWarningDialog` — this is an AUDIT.md rule, not a style preference.
- Don't instantiate `OkHttpClient.Builder()` or a second Coil `ImageLoader` for a new feature's image/network needs — inject the existing `@Named("tor")` client.
