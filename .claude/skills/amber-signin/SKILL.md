---
name: amber-signin
description: Log in to Umbra via Amber (NIP-55 signer) or approve a pending Amber sign request during a live on-device UI session. Use when driving the app with run-umbra and you need an authenticated session (not anonymous), or when a compose/like/repost/follow action needs Amber approval.
---

Depends on [`run-umbra`](../run-umbra/SKILL.md) — start there (`avd-start` → `install` → `launch`)
before using this. This skill only covers the Amber side of things: getting past Umbra's login
screen, and approving individual sign requests if the connected account isn't set to
auto-approve.

**Driver**: [`amber_login.sh`](amber_login.sh), same Git Bash + adb shape as `run-umbra`'s driver
(sets `MSYS2_ARG_CONV_EXCL` itself — no setup needed beyond having `adb` reach the device).

```bash
.claude/skills/amber-signin/amber_login.sh login     # from Umbra's login screen
.claude/skills/amber-signin/amber_login.sh approve   # from Amber's approval sheet mid-session
```

Verified live this session: logged out of an authenticated Umbra session, ran `login`, watched it
tap through both screens unattended, and landed back in Umbra's Home feed under the same account.

## What it does

Amber (`com.greenart7c3.nostrsigner`) talks to Umbra over NIP-55 intents
(`AmberConnector.kt`) — Umbra fires `ACTION_VIEW` with `data="nostrsigner:..."` and Amber's own
UI decides whether to approve. That approval tap is a real security boundary and can't be
scripted around with a plain intent — this driver's job is just finding the right button to tap,
because Amber's dialog layout isn't at a fixed pixel position (a naive guess from a scaled
screenshot was off by ~800px in y the first time this was investigated). It dumps the UI tree
with `uiautomator` and taps by matched button text instead.

**Prerequisite**: the target device needs Amber installed with at least one account already set
up. Check with `adb shell pm list packages | grep nostrsigner`. The dev AVD
(`Medium_Phone_API_36`) already has both — see [[dev-avd-amber-signin]] memory for the exact
account details, which this skill's driver supersedes as the *scripted* path (that memory
documents the manual tap sequence this automates).

## The flow (what `login` does)

1. Tap "Login with AMBER" on Umbra's entry screen. Fires `ACTION_VIEW`,
   `data="nostrsigner:"`, `type=get_public_key`, targeting `com.greenart7c3.nostrsigner`.
2. Amber opens its own approval bottom sheet: requesting app, the account to use, and a
   permission-level radio group. **"Approve basic actions" (the default) auto-approves most
   future requests without showing this sheet again** — compose, like, repost, follow, etc. all
   go through silently for the rest of the session. If the account was set up with "Manually
   approve each permission" instead, expect Amber's sheet to reappear per action — that's what
   `amber_login.sh approve` is for.
3. Tap "Connect". Amber returns the pubkey via activity result; Umbra logs in and shows the
   real npub/hex in the top bar.

## Gotchas

- **Session persists across app/emulator restarts once logged in.** Don't assume a fresh
  `run-umbra launch` needs this skill — check first (`run-umbra screenshot`); if the top bar
  already shows a real pubkey instead of "Login with AMBER", you're already authenticated.
- **Don't guess Amber's button coordinates from a screenshot.** Every coordinate in this driver
  comes from a live `uiautomator dump`, not a scaled-screenshot estimate — see `run-umbra`
  SKILL.md's own coordinate-scaling gotcha for why that estimate is unreliable in general, and
  this skill's own first-draft miss (~800px off) for a concrete case.
- **PIN/biometric unlock isn't handled.** If Amber's own app lock is enabled, `amber_login.sh`
  will report "still in Amber after tapping Connect" and stop rather than guess at unlocking it.
