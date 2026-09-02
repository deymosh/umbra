---
name: umbra-gradle
description: Use when touching app/build.gradle.kts, gradle/libs.versions.toml, proguard-rules.pro, or the benchmark build type. Adapted from a broader 10-module gradle-expert skill — Umbra is single-module (:app), so there's no cross-module dependency graph to troubleshoot; the real hazards here are R8/minification-only bugs that assembleDebug can never catch.
---

# Gradle in Umbra

Single-module Gradle project (`:app`), standard Gradle wrapper — `./gradlew`/`.\gradlew.bat` depending on the shell, never a system Gradle install (CLAUDE.md). No `buildSrc`, no included builds, no convention plugins. There's no module dependency graph to reason about the way a multi-module KMP setup would require; the interesting complexity here is entirely inside R8/minification behavior that only shows up in a non-debug build type.

## Versions (source of truth: `gradle/libs.versions.toml` — always re-check there, this list has already gone stale once)

As of the last check (2026-09): AGP 9.3.1 · Kotlin 2.4.10 · Compose BOM 2026.08.00 · Hilt 2.60.1 · KSP 2.3.11 · Room 2.8.4 · OkHttp 5.4.0 · kotlinx.coroutines 1.11.0 · Navigation 2.9.8 · SQLCipher 4.17.0 (`net.zetetic:sqlcipher-android`, moved from the older `net.zetetic:android-database-sqlcipher` for 16 KB page-size support — see the R8 keep-rule note below, the package rename bit that migration) · BouncyCastle 1.85.2 · Coil 3.5.0 (a major-version bump from 2.x — group id is `io.coil-kt.coil3`, not `io.coil-kt`) · Media3 1.11.0. No standalone Compose-compiler version — Compose compilation goes through the `org.jetbrains.kotlin.plugin.compose` Kotlin plugin (K2-integrated), not the old separate `composeCompiler` artifact version.

`compileSdk = 37`, `minSdk = 26`, `targetSdk = 37`, `jvmTarget = 17` — per CLAUDE.md, none of these get downgraded to work around a build issue; fix the root cause instead.

**Don't trust the numbers above without checking `gradle/libs.versions.toml` first** — this exact list drifted behind by several major versions once already (caught during a documentation audit, not by a build failure), which is exactly the failure mode a hardcoded version list invites. When touching this file for an unrelated reason, take the moment to re-sync it from the version catalog.

## The `benchmark` build type — why CI builds it, not just `assembleDebug`

```kotlin
create("benchmark") {
    initWith(getByName("release"))
    signingConfig = signingConfigs.getByName("debug")
    matchingFallbacks += listOf("release")
}
```

Release-shaped (R8 minified, `isDebuggable = false`) but debug-keystore signed, so it still `adb install`s. This exists because `assembleDebug` never runs R8 at all — CI running `assembleBenchmark` (see the root `Commands` section of CLAUDE.md) is what actually exercises minification before a real release does. Two real incidents, both documented directly in `proguard-rules.pro`, are why this build type exists:

## The SQLCipher R8 keep-rule gap (already fixed, but instructive)

SQLCipher's native (JNI) layer binds to Java fields/methods by fixed name (`mConnectionPtr` on `SQLiteDatabase`/`SQLiteConnection`). `-dontwarn` only silences build-time warnings — it doesn't stop R8 renaming/stripping those members, which crashes at first native call with `NoSuchFieldError`. This was invisible for a long time because the project's CI only ever built `assembleDebug`. Current fix, in `proguard-rules.pro`:

```
-keep class net.zetetic.database.sqlcipher.** { *; }
-keepclassmembers class net.zetetic.database.sqlcipher.** { *; }
```

**Package name is not a fixed target — it moved once already.** The library shipped under `net.sqlcipher.*` (`android-database-sqlcipher`) originally; the 4.10.0 upgrade to `sqlcipher-android` (16 KB page-size support) relocated every class to `net.zetetic.database.sqlcipher.*`, and the keep rules above had to move with it — a dependency bump that changes the artifact/package, not just the version number, silently invalidates a `-keep` rule pinned to the old package and reopens this exact crash. Verify the keep rule still targets a real package by checking the dependency's actual class names (e.g. unzip the `-sources.jar` from the Gradle cache) whenever its coordinates change, not just when it's first added.

**The lesson generalizes: any dependency with a JNI/reflection layer that binds to Java members by name needs an explicit `-keep`, and the only way to actually verify one is missing is to build a minified variant** — reading the dependency's docs or `-dontwarn` output won't catch it.

## The coroutines/R8 keep block — same failure shape, different dependency

`proguard-rules.pro` also carries a documented keep block for `kotlin.coroutines.jvm.internal.BaseContinuationImpl` fields, added after a real crash (NPE in `DispatchedTask`/`EventLoopImplPlatform`) that was only reproducible under R8/`benchmark`, traced via `mapping.txt`. Same pattern as the SQLCipher gap: caught in practice under a minified build, not by code inspection.

**If you add a new dependency with native bindings, reflection, or coroutine-internals-sensitive code, build `assembleBenchmark` and actually exercise the feature on-device before assuming R8 didn't break it** — `compileDebugKotlin`/`lintDebug`/`testDebugUnitTest` (Umbra's default verification loop per CLAUDE.md) will all pass regardless, because none of them run R8.

## Other keep rules already in place

Media3 keep rules and a kotlinx.serialization `$$serializer` keep block are also in `proguard-rules.pro` — check there before adding a new one; the shape (dontwarn vs explicit keep vs keepclassmembers) for a similar dependency type may already be established.

## Don't

- Don't assume `compileDebugKotlin`/`testDebugUnitTest` passing means a new dependency is R8-safe — those never invoke R8. Use `assembleBenchmark` (installable, minified) for that check.
- Don't downgrade `compileSdk`/`jvmTarget`/AGP to route around a build error — CLAUDE.md is explicit that this is a root-cause-fix situation, not a version-pinning one.
- Don't add a `-dontwarn` for a new native/reflective dependency and call it done — verify with an actual `-keep` and a minified build, per the SQLCipher precedent above.
