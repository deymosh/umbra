---
name: umbra-signer
description: Use when wiring a new flow that signs/publishes a Nostr event, encrypts/decrypts via NIP-44, debugging "Amber sign request approved but nothing happens," or touching AmberSignerGateway/AmberConnector/AmberRequestCoordinator. Umbra is Amber-only by design — no local-key signer exists or should ever be added. Adapted from a broader multi-signer auth-signers skill, which does not apply directly here: that skill covers 3 signer kinds, Umbra supports exactly 1.
---

# Signing in Umbra: Amber-only

AUDIT.md's non-negotiable: `nsec` never touches the device. There is no `NostrSignerInternal`/local-keypair path anywhere in Umbra (verified: zero hits for `NostrSigner`/`LocalSigner`/on-device `nsec` handling outside bech32 encode/decode and log-scrubbing utilities). Every signed event goes through Amber via Android intents. If you're implementing a new NIP that needs signing, this is the only path — don't design around "which signer is active," there's only one.

## The contract

`domain/nip55/AmberSignerGateway.kt`:

```kotlin
interface AmberSignerGateway {
    fun isAmberInstalled(): Boolean
    fun createLoginIntent(): Intent
    fun createSignEventIntent(eventJson: String, currentUserHex: String? = null): Intent
    fun createStoreIntent(): Intent
    fun extractPublicKeyFromResult(data: Intent?): String?
    fun extractSignedEventFromResult(data: Intent?): String?
    suspend fun trySignEventInBackground(eventJson: String, currentUserHex: String?): String?
    suspend fun signEvent(eventJson: String, currentUserHex: String?): String?
    suspend fun requestPublicKey(): String?
    fun openStore(): Boolean
}
```

Implemented by `data/amber/AmberSignerGatewayImpl.kt`, which also implements `Nip44Gateway` (encrypt/decrypt for NIP-51 private list tags — the same Amber round-trip, different intent extras).

## How a sign request actually travels

1. **`AmberConnector`** (`data/amber/AmberConnector.kt`, plain `object`) builds the Intent: `ACTION_VIEW` + a `nostrsigner:<eventJson>` URI, `package = AMBER_PACKAGE`, extras `type="sign_event"`, `event=eventJson`, `current_user=npub`, `returnLabel="Umbra"`. There's also a **ContentProvider fast path** (`trySignEventContentResolver`) that hits `content://com.greenart7c3.nostrsigner.SIGN_EVENT` synchronously with no UI, used when the user has already pre-approved that permission in Amber — this is why `signEvent()` can sometimes resolve without ever showing Amber's UI.
2. **`AmberRequestCoordinator`** (`data/amber/AmberRequestCoordinator.kt`, `@Singleton`) is the correlator — a `ConcurrentHashMap<String, CompletableDeferred<Intent?>>`, not a FIFO queue. `launchAndAwait(timeoutMs = 30_000) { buildIntent }` tags the Intent with a random UUID `"id"` extra, suspends on a `CompletableDeferred`, and `deliverResult(data: Intent?)` completes the matching deferred — including unpacking Amber's batched `"results"` JSON array when two requests land while Amber's own UI is already open (so a second sign request while the first is still pending doesn't get lost or cross-wired). One `ActivityResultLauncher` is registered app-root by `ui/AppSessionEffects.kt`, not per-screen — see [`compose-side-effects`](../compose-side-effects/SKILL.md)'s "In Umbra" section for the effect-lifecycle side of this.
3. The ViewModel calls the suspend gateway function directly and awaits it — no manual intent plumbing at the call site. In practice this round trip isn't hand-rolled per ViewModel: both `FeedViewModel` and `ProfileViewModel` manually construct their own `InteractionActionsCoordinator` (`ui/common/InteractionActionsCoordinator.kt`, never Hilt-injected) and call its `requestSignAndPublish(...)`, which owns the sign-then-publish coroutine:

```kotlin
// InteractionActionsCoordinator — the actual round trip, shared by both callers
fun requestSignAndPublish(
    eventJson: String,
    currentUserHex: String?,
    onSigned: suspend () -> Unit = {},
    onRejected: suspend () -> Unit = {}
) = requestSignAndPublish(buildEventJson = { eventJson }, currentUserHex, onSigned, onRejected)

fun requestSignAndPublish(
    buildEventJson: suspend () -> String,   // lazy overload — see below
    currentUserHex: String?,
    onSigned: suspend () -> Unit = {},
    onRejected: suspend () -> Unit = {}
) {
    scope.launch {
        val signedEvent = try { amberSignerGateway.signEvent(buildEventJson(), currentUserHex) } catch (e: Exception) { null }
        if (signedEvent != null) { onSigned(); publishSignedEvent(signedEvent) } else { onRejected() }
    }
}

// FeedViewModel.likeEvent() — a typical caller
interactionActionsCoordinator.requestSignAndPublish(eventJson, userPreferences.getPublicKey(), onSigned = { /* commit local state */ })
```

**The lazy `buildEventJson` overload matters for correctness, not just style.** For an event whose content is derived from a caller-owned list/set that could itself change while Amber's approval prompt is pending (an unbounded wait — mute/pin/follow lists are the concrete case), rebuild the event JSON from the *live* list right before signing, not from a snapshot captured before the wait started. Building it eagerly (the first overload) is fine for events whose content can't go stale during the wait, like a delete request pinned to a fixed event id. Getting this wrong was a real regression once: two overlapping same-kind actions (muting two different users back to back) could silently revert each other's already-published change on relays, because the second sign's event JSON was built from a mute list that was already stale by the time Amber returned.

## Gating every write action

`UserPreferences.canSignWithAmber()` (impl: `!pubkey.isNullOrBlank() && pubkey != ANONYMOUS_PUBKEY`) gates every mutating action — 23+ call sites across `FeedViewModel`, `ThreadViewModel`, `ProfileViewModel`, `ComposerViewModel`, `EditProfileViewModel`, `RelayConfigViewModel`, `BlossomServersViewModel`. **Every new write flow needs this same gate before attempting to sign** — anonymous/read-only sessions must fail fast in the UI, not surface an Amber error after the fact.

## Wiring a new signing flow (checklist)

1. Build the unsigned event JSON via `domain/nip01/NostrEventBuilder.kt` (see [`nostr-nip-implementation`](../nostr-nip-implementation/SKILL.md) for the established builder pattern).
2. Check `canSignEvents()`/`canSignWithAmber()` before offering the action in the UI.
3. Call `amberSignerGateway.signEvent(eventJson, currentUserHex)` from `viewModelScope.launch` (never from a repository, use case, or Composable — AUDIT.md §2.6: signing only happens in a ViewModel).
4. Commit the local state update inside `onSigned` (i.e. only after Amber confirms) — `requestSignAndPublish`'s callback, not before calling it. Both `FeedViewModel` and `ProfileViewModel` follow this same commit-after-sign shape today; there is no more optimistic-update-then-rollback path anywhere (`ProfileViewModel`'s used to work that way — deliberately deleted and converged onto `FeedViewModel`'s shape, see `nostr-nip-implementation`'s step 6 for the current pattern).
5. Publish only through `PublishSignedEventUseCase` — never call `AmberConnector`/relay publish directly from a ViewModel.

## Don't

- **Don't add a second signer kind.** No NIP-46 remote bunker signer, no local-keypair signer, no "sign in with nsec" fallback — even as a debug/dev convenience. This is the one constraint in this skill that AUDIT.md and CLAUDE.md both call out explicitly and non-negotiably.
- **Don't call `AmberConnector` or `AmberRequestCoordinator` directly from a ViewModel** — always go through `AmberSignerGateway`'s suspend functions; the coordinator's correlation logic is exactly the kind of thing a bypass would silently break (a second in-flight request without going through the shared map).
- **Don't build a second `ActivityResultLauncher`** for Amber intents — there's exactly one, registered in `AppSessionEffects.kt`. A second one racing for the same `onActivityResult` callback is how "approved but nothing happens" bugs happen.
- **Don't assume Amber's UI always opens.** The ContentProvider fast path means `signEvent()` can return without any visible Amber interaction — don't add a "waiting for Amber" UI state that assumes the intent always launches an Activity.
