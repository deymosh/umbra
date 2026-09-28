# UI snapshots

Umbra's UI is reviewed visually on the JVM — no emulator — with
[Roborazzi](https://github.com/takahirom/roborazzi) on top of Robolectric's native graphics.
Goldens live in `app/src/test/snapshots/` and are committed, so every UI change shows up as an
image diff in review.

```bash
./gradlew recordRoborazziDebug    # render and (over)write the goldens
./gradlew verifyRoborazziDebug    # fail if any render differs from its golden
./gradlew compareRoborazziDebug   # write *_compare.png diff images to build/outputs/roborazzi
```

A plain `testDebugUnitTest` also renders every snapshot (catching crashes) without touching the
goldens.

## Writing one

Snapshot tests live next to the code they cover (`ui/snapshot/` for shared components, or the
screen's own package when it needs `internal` access) and follow this shape:

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class MyScreenSnapshotTest {
    @Test
    fun default() = snapshot("MyScreen_default") { MyScreenContent(state = sampleState) }
}
```

- `snapshot()` wraps the content in `UmbraTheme` and the CompositionLocals `MainActivity` provides.
- Render **stateless content composables** with sample state — never a ViewModel. Screens split
  into `XScreen(viewModel)` → `XContent(state, callbacks)` for exactly this reason.
- Use `SnapshotFixtures` for profiles/notes. Its fake `UserRepository` answers "nothing cached",
  and fixture profiles have no picture URL, so nothing ever reaches the network.
- `application = Application::class` keeps the Hilt app (Tor, database, relays) from starting.

## How it's wired

Robolectric's Android framework jar is resolved by Gradle (`robolectricRuntime` configuration in
`app/build.gradle.kts`) and Robolectric runs with `robolectric.offline=true` against it, so CI
caches it like any dependency. Only tests using `RobolectricTestRunner` get the Android runtime;
every other unit test still runs against the stubbed `android.jar`.

Paparazzi was evaluated first and rejected: its layoutlib runtime replaces `android.jar` for every
unit test in the module, which breaks the existing plain-JVM tests that touch `android.util.Log`.
