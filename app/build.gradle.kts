plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// UI snapshot goldens (see docs/UI_SNAPSHOTS.md): recordRoborazziDebug writes them here,
// verifyRoborazziDebug diffs against them.
roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

// Robolectric normally downloads its Android framework jar itself at test time, outside Gradle's
// dependency resolution (no caching, no mirrors, fails behind rate limits). Resolve it through
// Gradle instead and run Robolectric offline against it.
val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }
val robolectricDependencyDir = layout.buildDirectory.dir("robolectric-deps")
val prepareRobolectricRuntime by tasks.registering(Sync::class) {
    from(robolectricRuntime)
    into(robolectricDependencyDir)
}

android {
    namespace = "com.umbra.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.umbra.app"
        minSdk {
            version = release(26)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release-shaped (R8 minified/optimized, isDebuggable = false) but debug-keystore signed
        // so it installs via `adb install` like any dev build — for local device performance
        // comparisons only, never for distribution. A plain `assembleDebug` sideload forfeits R8
        // optimization AND ART's baseline-profile/AOT compilation path, which matters far more on
        // real phone SoCs than on an emulator backed by a full desktop CPU; this build type
        // exists so "does it still lag" can be tested without that confound. Mirrors Amethyst's
        // own `benchmark` build type (see their benchmark/build.gradle.kts).
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Without this, any unit-tested code path calling an unstubbed android.util.Log
            // method (e.g. Log.isLoggable, used pervasively per AUDIT.md's logging rules) throws
            // "Method ... not mocked" — this project has no Robolectric/Mockito-static dependency
            // to stub it per-test, so returning Android's default value (false/0/null) instead of
            // throwing is what lets a class that logs still be exercised by a plain JVM unit test.
            isReturnDefaultValues = true
            // Merged resources (fonts, strings, drawables) for the Roborazzi UI snapshot tests.
            // Those tests opt in to Robolectric's Android runtime via their own runner; every
            // other unit test keeps running against the plain stubbed android.jar above.
            isIncludeAndroidResources = true
            all { test ->
                test.dependsOn(prepareRobolectricRuntime)
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty(
                    "robolectric.dependency.dir",
                    robolectricDependencyDir.get().asFile.absolutePath
                )
                // Robolectric reaches into java.io.FileDescriptor internals on JDK 17+ (via
                // SharedSecrets on 21).
                test.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED"
                )
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        disable += listOf("AndroidGradlePluginVersion", "GradleDependency")
        // New in the Compose BOM 2026.08.00 bump. Flags every context.getString(...) reached via
        // LocalContext.current, including the ones in this codebase deliberately outside
        // composition (Toast/Snackbar messages resolved once inside an onClick/coroutine at the
        // moment the user acts, not observed across recomposition) -- the "stale value on
        // Configuration change" concern the rule guards against doesn't apply to those. Disabled
        // at the module level rather than guess-fixing ~24 call sites' worth of behavior without
        // on-device verification.
        disable += listOf("LocalContextGetResourceValueCall")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Replaces the deprecated applicationVariants.all API (removed in AGP 9) for renaming
// output APKs to umbra-v$versionName.apk (release) / umbra-$variantName-v$versionName.apk (other variants).
androidComponents {
    onVariants { variant ->
        val ver = android.defaultConfig.versionName
        val outputFileName = if (variant.name == "release") {
            "umbra-v$ver.apk"
        } else {
            "umbra-${variant.name}-v$ver.apk"
        }
        variant.outputs.forEach { output ->
            output.outputFileName.set(outputFileName)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.bundles.media3)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.zetetic.sqlcipher)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    robolectricRuntime(libs.robolectric.android.all)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Registers the empty ComponentActivity that createComposeRule() launches — debug builds only,
    // used by the whole-screen (dialogs/sheets) UI snapshots.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.exifinterface)
}
