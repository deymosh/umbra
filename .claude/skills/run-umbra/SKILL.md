---
name: run-umbra
description: Build, install, launch, and drive the Umbra Android app on an emulator — take screenshots, tap/type/swipe, capture logcat, run unit tests. Use when asked to run Umbra, start the app, screenshot its UI, verify a change works in the real app, or interact with the running app.
---

Umbra is a single-module Android app (Kotlin/Compose, package `com.umbra.app`),
driven here via `adb` — there's no web/Electron surface, so the harness is
[`driver.sh`](driver.sh), a script wrapping `adb`/`emulator`/`gradlew`.
All paths below are relative to the repo root (`umbra/`), matching CLAUDE.md.

**Emulator/device testing is opt-in only.** Per root `CLAUDE.md`, only launch the
emulator and drive it with `driver.sh` when the user has explicitly asked to run
or test on the emulator. Otherwise — including for UI changes — verify with
**Direct invocation** below (`compileDebugKotlin` / `lintDebug` /
`testDebugUnitTest`) and stop there; don't reach for the emulator on your own
initiative just because a change touches UI code.

## Prerequisites

- JDK 17, Android SDK with `platform-tools` and an emulator image (this repo
  already assumes these — see root `CLAUDE.md`).
- **`driver.sh` itself is written for Git Bash on Windows** (`emulator.exe`,
  `%LOCALAPPDATA%\Android\Sdk`) — that's where the maintainer's actual AVD lives,
  not a CLAUDE.md-mandated shell choice (CLAUDE.md's own build/test commands are
  platform-neutral; only this specific on-device-driving workflow is
  Windows-only, because that's genuinely where the physical emulator runs today).
  On a Linux sandbox without `adb`/`emulator` installed, `driver.sh`'s AVD
  commands (`avd-start`, `install`, `launch`, `screenshot`, ...) won't work at
  all — but **Direct invocation** below still does, since it's plain Gradle with
  no emulator dependency.
- **Orbot must be installed on the device/emulator.** Umbra hard-gates all
  network traffic on Tor (`AUDIT.md`/`CLAUDE.md`: "no exceptions, no plaintext
  fallback") — without Orbot the app sits forever on its "Orbot is starting.
  Connecting to TOR…" splash. The dev AVD used while authoring this skill
  (`Medium_Phone_API_36`) already has `org.torproject.android` installed —
  confirmed via `adb shell pm list packages | grep orbot`. If you're on a fresh
  AVD, install Orbot's APK before expecting the feed to render.

## Direct invocation (primary path — no emulator needed)

Most changes in this repo are in `domain/usecase`, `data/repository`, etc. and
are verified by unit test, not by eyeballing the UI. Use whichever wrapper
matches the shell you're actually in:

```bash
./gradlew compileDebugKotlin              # fast type-check while iterating
./gradlew testDebugUnitTest                # full unit suite
./gradlew testDebugUnitTest --tests "com.umbra.app.data.repository.RepositoryPolicySuiteTest"
./gradlew lintDebug                        # CI treats warnings as errors
```

(`.\gradlew.bat` on Windows.)

`driver.sh unit-tests [filter]` wraps the same thing:

```bash
.claude/skills/run-umbra/driver.sh unit-tests
.claude/skills/run-umbra/driver.sh unit-tests "com.umbra.app.data.repository.RepositoryPolicySuiteTest"
```

Verified this session: `unit-tests "com.umbra.app.data.repository.RepositoryPolicySuiteTest"`
→ `BUILD SUCCESSFUL`.

## Run (agent path)

```bash
D=.claude/skills/run-umbra/driver.sh

"$D" avd-start          # boots Medium_Phone_API_36 if nothing's attached; no-op
                         # if a device is already connected (checks `adb devices`)
"$D" build               # ./gradlew.bat assembleDebug
"$D" install              # ./gradlew.bat installDebug
"$D" launch               # force-stop + am start MainActivity
"$D" screenshot home     # -> .claude/skills/run-umbra/out/home.png, prints the path
```

Screenshots land in `.claude/skills/run-umbra/out/` (gitignored — ephemeral).

| command | what it does |
|---|---|
| `avd-start [avd-name]` | Boot emulator, block until `sys.boot_completed=1`. Default AVD `Medium_Phone_API_36`, override with `UMBRA_AVD` env var. Skips launch if a device is already attached. |
| `avd-stop` | Clean `adb emu kill` shutdown of whatever's attached — run when done verifying, see Resource usage below. |
| `avd-list` | `emulator -list-avds` |
| `gradle-stop` | `./gradlew.bat --stop` — kill the Gradle daemon, see Resource usage below. |
| `build` | `./gradlew.bat assembleDebug` |
| `install` | `./gradlew.bat installDebug` |
| `reinstall` | `adb uninstall` then `installDebug` — use if `install` fails with `INSTALL_FAILED_INSUFFICIENT_STORAGE` (see Gotchas). Wipes app storage. |
| `launch` | force-stop then `am start` `com.umbra.app/.MainActivity` |
| `stop` | force-stop the app |
| `screenshot <name>` | `screencap` + `pull` → `out/<name>.png`, prints local path |
| `tap <x> <y>` | `input tap` — screen pixel coords (see Gotchas re: scaling) |
| `swipe <x1> <y1> <x2> <y2> [ms]` | `input swipe` |
| `text "<string>"` | `input text` into the focused field |
| `key <KEYCODE>` | `input keyevent` (`BACK`, `HOME`, `ENTER`, ...) |
| `logs [tag] [seconds]` | logcat capture; filtered to one Umbra tag if given (default all, 20s) |
| `devices` | `adb devices -l` |
| `unit-tests [filter]` | see Direct invocation above |

Full sequence verified this session on a freshly booted `Medium_Phone_API_36`
emulator with no prior Umbra state:

```bash
$D avd-start
$D build          # BUILD SUCCESSFUL
$D install        # Installed on 1 device.
$D launch
sleep 8
$D screenshot home    # showed the live Home feed, npub identity, images loading over Tor
$D tap 844 2110       # bottom-nav Settings icon → screen changed on next screenshot
$D key BACK
$D logs UmbraFeedVM 8
```

## Run (human path)

`.\gradlew.bat installDebug` then tap the icon on-device. Useless headless —
there's no way to see the UI without the driver's `screenshot`.

## Test

```bash
./gradlew testDebugUnitTest       # .\gradlew.bat on Windows
```

`237 tests completed` at time of writing (a handful `skipped`, none failing).

---

## Resource usage — stop what you started

Both the emulator and the Gradle daemon are heavy, long-lived background
processes, and neither shuts itself down promptly on its own:

- **The emulator** holds several GB of RAM and a meaningful CPU share the
  entire time it's running, whether or not anything is actively using it.
  `avd-start` deliberately reuses an already-attached device instead of
  booting a second instance — never start one "just in case" before checking
  `devices` first. When a verification pass is actually done (not "done for
  this one screenshot, might need it again in 30 seconds" — genuinely done),
  run `driver.sh avd-stop` rather than leaving it idle for the rest of the
  session.
- **The Gradle daemon** stays resident (its own JVM, easily 1GB+ heap) until
  its own idle timeout (default 3h) or an explicit stop — it does not exit
  when a `./gradlew.bat` command finishes. Do **not** reach for `--no-daemon`
  on individual commands to work around this: this project's build reuses
  Gradle's configuration cache across invocations (the "Configuration cache
  entry reused" line in output), which is what makes repeated
  `compileDebugKotlin`/`testDebugUnitTest`/`lintDebug` calls during a single
  editing session fast — `--no-daemon` throws that away and pays full
  JVM+configuration startup on every single call instead. The daemon is the
  right tool while you're actively iterating; the fix for leftover resource
  use is stopping it when the iteration burst is over, via `driver.sh
  gradle-stop`, not avoiding it in the first place.
- Rule of thumb: `avd-stop`/`gradle-stop` at the end of a verification pass,
  same as you'd close a file handle — not mandatory after every single
  command, but don't let either sit idle for the rest of an unrelated
  conversation just because it might be convenient to have warm later.

## Gotchas

- **`MSYS_NO_PATHCONV=1` breaks `adb pull`.** The obvious fix for Git Bash
  mangling `/sdcard/foo.png` into a Windows path is to set
  `MSYS_NO_PATHCONV=1` — but that disables path conversion for *every*
  argument on the command line, so `adb pull /sdcard/x.png <local-path>`
  then fails to convert the *local* destination too, producing "cannot
  create file/directory ... No such file or directory". The actual fix is
  `MSYS2_ARG_CONV_EXCL="/sdcard"`, which scopes the exclusion to just the
  device-side path and leaves the local one converted normally.
  `driver.sh` sets this at the top of the file — every subcommand in it is
  safe by construction, but if you're running raw `adb` commands yourself
  outside the driver, you'll hit this.
- **The app is a hard Tor gate, not a soft one.** On a device without Orbot,
  Umbra doesn't degrade to some offline/demo state — it sits on the "Orbot is
  starting. Connecting to TOR…" splash indefinitely. If a screenshot shows
  only that splash after 15+ seconds, the fix is installing Orbot, not
  waiting longer.
- **Cold start takes longer than you'd guess.** Even with Orbot already
  running, the first screenshot after `launch` may still show the splash;
  it took roughly 8s from `am start` to a rendered feed with images during
  this session's run. `driver.sh launch` doesn't block on this — sleep
  before your first `screenshot`.
- **`input tap` coordinates are raw screen pixels, not dp**, and this repo's
  target device reports a 1080x2400 physical screen. If you're eyeballing
  coordinates from a screenshot that Read/an image viewer displayed scaled
  down (e.g. shown at 900x2000), multiply by the scale factor before calling
  `tap` — screenshots are saved at full device resolution even when a UI
  displays them smaller.
- **`adb devices` can report a previously-fine emulator as `offline`** after
  it's sat idle for a while mid-session (observed with no obvious trigger —
  not tied to a specific command). Symptoms: `driver.sh screenshot`/any `adb`
  call fails outright even though the emulator window/process is still up.
  Fix is `adb kill-server && adb start-server` (give it ~3s), then retry —
  no need to restart the emulator itself, the adb server reconnects to the
  already-running instance. If that doesn't clear it, `avd-start` again
  (it no-ops into "already attached" once the server sees it, or boots fresh
  if the emulator process actually died).
- **`adb devices` right after `emulator` launches can lag Gradle's own view
  of the device.** `installDebug` failed once mid-session with `device
  'emulator-5554' not found` immediately after the emulator finished
  booting per `adb devices`, then succeeded on a bare retry a few seconds
  later (Gradle's bundled adb client hadn't caught up with a device-list
  change). If `installDebug` fails right after `avd-start` finishes, retry
  once before assuming something's actually wrong.
- **`installDebug` can fail with `INSTALL_FAILED_INSUFFICIENT_STORAGE` on a
  nearly-full emulator data partition even though the APK (~41MB) easily
  fits** — hit this mid-session at 94% `/data` usage
  (`MSYS2_ARG_CONV_EXCL="/data" adb shell df /data` to check). A
  replace-install needs headroom for old+new APK simultaneously; try
  `driver.sh reinstall` (uninstall first) instead of `install` when this
  happens.
  **If it still fails after `reinstall`** (hit this too, in a later session —
  `/data` was at 95%/336MB free and uninstalling Umbra alone only recovered
  its own ~41MB, not enough headroom): the AVD's other installed apps
  (this dev AVD has several other Nostr clients for cross-client
  reference) accumulate real cache/media over a long session and are fair
  game to clear, since none of them are the thing under test:
  `adb shell pm clear <package>` for each (`pm list packages` to see what's
  there). Freed enough space to install after clearing two ~unrelated apps'
  data. This is a shared/constrained AVD, not a fresh one — expect to revisit
  this occasionally on long sessions rather than treating one clear as
  permanent.

## Troubleshooting

- **`adb: error: cannot create file/directory '<path>': No such file or
  directory` on `adb pull`**: you're hitting the `MSYS_NO_PATHCONV` gotcha
  above — use `MSYS2_ARG_CONV_EXCL="/sdcard"` instead, or just use
  `driver.sh screenshot`, which already does.
- **`com.android.builder.testing.api.DeviceException: device 'emulator-XXXX'
  not found` from `installDebug`**: transient adb-server/Gradle desync right
  after emulator boot. Run `adb devices -l` to confirm the device is really
  there, then re-run `installDebug`.
- **`InstallException: INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to
  override installation location`**: emulator `/data` partition is nearly
  full (a replace-install needs room for old+new APK at once). Run
  `driver.sh reinstall` instead of `install`.
- **Screenshot shows only the "umbra" wordmark + "Orbot is starting..."**:
  Orbot isn't installed/running on this device. `adb shell pm list packages
  | grep orbot` to check; install Orbot's APK if missing.
