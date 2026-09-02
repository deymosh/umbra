#!/usr/bin/env bash
# Drives Amber (NIP-55 signer, com.greenart7c3.nostrsigner) through a login/sign approval
# during a live Umbra session on the emulator. Depends on the device already being reachable
# via adb — pair with .claude/skills/run-umbra/driver.sh (avd-start/install/launch) first.
#
# Amber's own approval UI can't be bypassed via adb (that's the security boundary working as
# intended) — this script still needs a real tap on Amber's "Connect"/"Approve" button. What it
# automates is FINDING that button: it dumps the UI tree and taps by matched text instead of a
# hardcoded coordinate, because Amber's dialog layout shifts between screens/OS versions (a
# naive displayed-screenshot-coords estimate for "Connect" was off by ~800px in y once — see
# .claude/skills/run-umbra/SKILL.md Gotchas for the same class of issue on the Umbra side).
set -euo pipefail
export MSYS2_ARG_CONV_EXCL="/sdcard"

TMP_XML="${TMPDIR:-/tmp}/amber_login_ui.xml"
AMBER_PKG="com.greenart7c3.nostrsigner"

dump_ui() {
  adb shell uiautomator dump /sdcard/amber_login_ui.xml >/dev/null
  adb pull /sdcard/amber_login_ui.xml "$TMP_XML" >/dev/null 2>&1
}

# Taps the center of the first element whose exact `text` attribute matches $1. Good enough for
# Umbra's and Amber's own buttons, which are large enough that the text node's own bounds are
# close to the real touch target center (small icon-only buttons need the parent's clickable
# bounds instead — not needed for this flow, every tap here targets a labeled button).
tap_text() {
  local text="$1"
  dump_ui
  local bounds
  bounds=$(grep -oE "text=\"${text}\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$TMP_XML" \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  if [ -z "$bounds" ]; then
    echo "amber_login.sh: could not find an element with text '$text'" >&2
    return 1
  fi
  local x1 y1 x2 y2
  x1=$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/' <<<"$bounds")
  y1=$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/' <<<"$bounds")
  x2=$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/' <<<"$bounds")
  y2=$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/' <<<"$bounds")
  adb shell input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
}

in_amber() {
  dump_ui
  grep -q "package=\"$AMBER_PKG\"" "$TMP_XML"
}

cmd_login() {
  echo "Tapping 'Login with AMBER'..." >&2
  tap_text "Login with AMBER"
  sleep 2

  echo "Waiting for Amber's approval sheet..." >&2
  for _ in $(seq 1 10); do
    if in_amber; then break; fi
    sleep 1
  done
  if ! in_amber; then
    echo "amber_login.sh: Amber never came to the foreground — is it installed?" >&2
    echo "  adb shell pm list packages | grep nostrsigner" >&2
    return 1
  fi

  echo "Tapping 'Connect'..." >&2
  tap_text "Connect"
  sleep 2

  if in_amber; then
    echo "amber_login.sh: still in Amber after tapping Connect — it may be asking for a PIN" \
         "or biometric unlock, which needs a real interactive tap this script can't infer." >&2
    return 1
  fi
  echo "Amber login flow complete — back in Umbra." >&2
}

# Approves a pending sign request (compose/like/repost/etc) the same way, for accounts whose
# Amber permission level is "Manually approve each permission" instead of "Approve basic
# actions" (which auto-approves without ever showing this screen — see SKILL.md).
cmd_approve() {
  if ! in_amber; then
    echo "amber_login.sh: not currently in Amber — nothing to approve." >&2
    return 1
  fi
  echo "Tapping 'Approve'..." >&2
  tap_text "Approve" || tap_text "Connect"
  sleep 2
}

case "${1:-login}" in
  login) cmd_login ;;
  approve) cmd_approve ;;
  *) echo "Usage: amber_login.sh [login|approve]" >&2; exit 1 ;;
esac
