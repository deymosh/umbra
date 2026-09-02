#!/usr/bin/env bash
# Driver for building, installing, launching, and poking at the Umbra Android app
# via adb. Written for Git Bash on Windows (the project's primary shell — see
# CLAUDE.md) but the adb/gradlew invocations are the same on any shell; only the
# path quoting would need to change.
#
# Every subcommand here is a command that was actually run (and worked) while
# authoring this skill. See SKILL.md for narrative + screenshots.
#
# IMPORTANT (Git Bash / MSYS path mangling): any adb argument that starts with
# / gets silently rewritten to a Windows path (e.g. "/sdcard/foo.png" becomes
# "C:/Program Files/Git/sdcard/foo.png"), breaking device-side paths. The fix is
# NOT `MSYS_NO_PATHCONV=1` — that disables conversion for EVERY argument on the
# command line, which then also breaks `adb pull`'s second (local, Windows-side)
# argument ("cannot create file/directory ... No such file or directory").
# MSYS2_ARG_CONV_EXCL scopes the exclusion to just paths matching the prefix,
# leaving local-path arguments converted normally. Verified against both
# `adb shell screencap -p /sdcard/x.png` (remote-only) and
# `adb pull /sdcard/x.png <local>` (mixed remote+local) while authoring this.
set -euo pipefail
export MSYS2_ARG_CONV_EXCL="/sdcard"

cd "$(dirname "${BASH_SOURCE[0]}")/../../.." # -> umbra/ (repo root)

PKG="com.umbra.app"
ACTIVITY="$PKG/.MainActivity"
AVD_NAME="${UMBRA_AVD:-Medium_Phone_API_36}"
EMULATOR_BIN="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk/emulator/emulator.exe"
OUT_DIR="${UMBRA_DRIVER_OUT:-$(pwd)/.claude/skills/run-umbra/out}"
mkdir -p "$OUT_DIR"

usage() {
  cat <<'EOF'
Usage: driver.sh <command> [args]

  avd-start [avd-name]     Boot the emulator (default AVD: Medium_Phone_API_36) and
                            block until `adb devices` reports it ready. No-ops if a
                            device is already attached — never boots a second instance.
  avd-stop                  Cleanly shut down whatever emulator is attached (`adb emu
                            kill`, not a process kill — see Gotchas on resource usage).
                            Run this when you're done verifying, not just at end of
                            session: an idle emulator still holds ~2-4GB RAM + a CPU
                            core's worth of background ticks the whole time it's up.
  avd-list                  List installed AVDs.
  gradle-stop                Stop the Gradle daemon (./gradlew.bat --stop). Run this
                            when you're done with a burst of build/test/lint calls —
                            an idle daemon sits resident (own JVM heap, can be 1GB+)
                            until its own idle timeout (default 3h) or this. Don't
                            pass --no-daemon on individual commands instead: this
                            project's build reuses the configuration cache across
                            invocations (visible as "Configuration cache entry
                            reused" in output), which --no-daemon would defeat,
                            making EVERY call pay full JVM+config startup cost again.
  build                     ./gradlew.bat assembleDebug
  install                   ./gradlew.bat installDebug (build a device first!)
  reinstall                 uninstall + installDebug — use if install fails with
                            INSTALL_FAILED_INSUFFICIENT_STORAGE (see Gotchas)
  launch                    force-stop + am start the app's MainActivity
  stop                      force-stop the app
  screenshot <name>         Screenshot to out/<name>.png (also prints the local path)
  tap <x> <y>               adb shell input tap x y (screen coords, NOT dp — see Gotchas)
  swipe <x1> <y1> <x2> <y2> [ms]   Swipe gesture
  text "<string>"           Type text into the currently focused field
  key <keyevent>            adb shell input keyevent KEYCODE_... (e.g. BACK, HOME, ENTER)
  logs [tag] [seconds]      Capture logcat for <seconds> (default 20), optionally filtered
                            to one Umbra TAG (e.g. UmbraFeedVM). See CLAUDE.md's own list
                            of tags: UmbraFeedVM, UmbraEventRepo, UmbraRelayWSBase, ...
  devices                   adb devices -l
  unit-tests [filter]       ./gradlew.bat testDebugUnitTest [--tests <filter>]
                            This is the DIRECT INVOCATION path — most PRs in this repo
                            touch domain/usecase logic, not the UI. Prefer this over
                            launching the app when you're testing pure logic.
EOF
}

wait_for_boot() {
  echo "Waiting for device to finish booting..." >&2
  for _ in $(seq 1 30); do
    if adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
      echo "Boot complete." >&2
      return 0
    fi
    sleep 5
  done
  echo "Timed out waiting for boot." >&2
  return 1
}

cmd_avd_list() {
  "$EMULATOR_BIN" -list-avds
}

cmd_avd_start() {
  local avd="${1:-$AVD_NAME}"
  if adb devices | grep -q "device$"; then
    echo "A device is already attached; skipping emulator launch." >&2
    adb devices -l
    return 0
  fi
  echo "Starting emulator '$avd' in background (logs: $OUT_DIR/emulator.log)..." >&2
  nohup "$EMULATOR_BIN" -avd "$avd" -no-snapshot -gpu swiftshader_indirect \
    > "$OUT_DIR/emulator.log" 2>&1 &
  disown || true
  # Wait for adb to see *a* device first, then for it to finish booting.
  for _ in $(seq 1 24); do
    if adb devices | awk '$2=="device"{found=1} END{exit !found}'; then
      break
    fi
    sleep 5
  done
  wait_for_boot
  adb devices -l
}

cmd_avd_stop() {
  local device
  device="$(adb devices | awk '$2=="device"{print $1; exit}')"
  if [ -z "$device" ]; then
    echo "No attached emulator to stop." >&2
    return 0
  fi
  echo "Stopping emulator $device..." >&2
  adb -s "$device" emu kill || true
}

cmd_gradle_stop() {
  ./gradlew.bat --stop
}

cmd_build() {
  ./gradlew.bat assembleDebug
}

cmd_install() {
  ./gradlew.bat installDebug
}

cmd_reinstall() {
  # installDebug replaces in place, which briefly needs room for old+new APK.
  # On a nearly-full emulator data partition that fails with
  # INSTALL_FAILED_INSUFFICIENT_STORAGE even though the APK itself fits —
  # uninstalling first avoids the transient double-space requirement.
  # NOTE: this wipes the app's on-device storage (matches Umbra's own
  # "no migrations, fallbackToDestructiveMigration" dev posture — see
  # UmbraDatabase.kt comment — so that's an acceptable default for driving
  # a fresh dev session, not for preserving state across runs).
  adb uninstall "$PKG" || true
  ./gradlew.bat installDebug
}

cmd_launch() {
  adb shell am force-stop "$PKG" || true
  sleep 1
  adb shell am start -n "$ACTIVITY"
  echo "Launched $ACTIVITY. Tor/Orbot handshake + first relay connect can take 5-15s" \
       "before the feed renders — see SKILL.md Gotchas." >&2
}

cmd_stop() {
  adb shell am force-stop "$PKG"
}

cmd_screenshot() {
  local name="${1:?usage: driver.sh screenshot <name>}"
  local remote="/sdcard/${name}.png"
  local local_path="$OUT_DIR/${name}.png"
  adb shell screencap -p "$remote"
  adb pull "$remote" "$local_path" >&2
  echo "$local_path"
}

cmd_tap() {
  local x="${1:?x}" y="${2:?y}"
  adb shell input tap "$x" "$y"
}

cmd_swipe() {
  local x1="${1:?x1}" y1="${2:?y1}" x2="${3:?x2}" y2="${4:?y2}" ms="${5:-300}"
  adb shell input swipe "$x1" "$y1" "$x2" "$y2" "$ms"
}

cmd_text() {
  local s="${1:?usage: driver.sh text '<string>'}"
  adb shell input text "${s// /%s}"
}

cmd_key() {
  local k="${1:?usage: driver.sh key <KEYCODE_NAME or number>}"
  adb shell input keyevent "$k"
}

cmd_logs() {
  local tag="${1:-}" secs="${2:-20}"
  if [ -n "$tag" ]; then
    adb shell setprop "log.tag.$tag" DEBUG
    timeout "$secs" adb logcat -v color "$tag":D "*:S" || true
  else
    timeout "$secs" adb logcat -v color || true
  fi
}

cmd_devices() {
  adb devices -l
}

cmd_unit_tests() {
  if [ -n "${1:-}" ]; then
    ./gradlew.bat testDebugUnitTest --tests "$1"
  else
    ./gradlew.bat testDebugUnitTest
  fi
}

main() {
  local cmd="${1:-}"
  [ -n "$cmd" ] && shift || true
  case "$cmd" in
    avd-start) cmd_avd_start "$@" ;;
    avd-stop) cmd_avd_stop ;;
    avd-list) cmd_avd_list "$@" ;;
    gradle-stop) cmd_gradle_stop ;;
    build) cmd_build ;;
    install) cmd_install ;;
    reinstall) cmd_reinstall ;;
    launch) cmd_launch ;;
    stop) cmd_stop ;;
    screenshot) cmd_screenshot "$@" ;;
    tap) cmd_tap "$@" ;;
    swipe) cmd_swipe "$@" ;;
    text) cmd_text "$@" ;;
    key) cmd_key "$@" ;;
    logs) cmd_logs "$@" ;;
    devices) cmd_devices ;;
    unit-tests) cmd_unit_tests "$@" ;;
    *) usage; exit 1 ;;
  esac
}

main "$@"
