plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// CI (.github/workflows/release.yml) stamps the build through the environment; a local build keeps
// the defaults. Read through providers so the configuration cache stays valid. A variable that is
// set but blank (GitHub exports an unset secret as "") counts as absent.
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val ciVersionCode = env("AQSYNC_VERSION_CODE")?.toIntOrNull()
val ciVersionName = env("AQSYNC_VERSION_NAME")
val releaseSigningVariables = listOf(
    "AQSYNC_KEYSTORE_FILE",
    "AQSYNC_KEYSTORE_PASSWORD",
    "AQSYNC_KEY_ALIAS",
    "AQSYNC_KEY_PASSWORD",
)
val requestedReleaseTasks = gradle.startParameter.taskNames.any { task ->
    task.substringAfterLast(':').contains("release", ignoreCase = true)
}
val missingReleaseSigningVariables = releaseSigningVariables.filter { env(it) == null }

// Local R8 check without the project key. `-Paqsync.localRelease=true` signs the minified release
// with the debug key so `./gradlew :app:assembleRelease -Paqsync.localRelease=true` works on any
// machine. Such an APK cannot update a release install and must never be published, so the flag is
// refused on CI (GitHub Actions sets CI=true) and the release workflow never passes it.
val localRelease = providers.gradleProperty("aqsync.localRelease").orNull == "true"
if (localRelease && env("CI") != null) {
    throw GradleException("aqsync.localRelease is a local check only and is refused on CI")
}
if (requestedReleaseTasks && !localRelease && missingReleaseSigningVariables.isNotEmpty()) {
    throw GradleException(
        "Release tasks require project signing credentials; missing: ${missingReleaseSigningVariables.joinToString()}" +
            " (for a local minified build without them pass -Paqsync.localRelease=true)",
    )
}
val releaseKeystore = env("AQSYNC_KEYSTORE_FILE")?.let { rootProject.file(it) }
if (requestedReleaseTasks && !localRelease && releaseKeystore?.isFile != true) {
    throw GradleException("AQSYNC_KEYSTORE_FILE must name an existing regular file")
}

android {
    namespace = "earth.walkthru.aqsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "earth.walkthru.aqsync"
        minSdk = 31
        targetSdk = 37
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0"

        testInstrumentationRunner = "earth.walkthru.aqsync.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (!localRelease && releaseKeystore != null && missingReleaseSigningVariables.isEmpty()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = env("AQSYNC_KEYSTORE_PASSWORD")
                keyAlias = env("AQSYNC_KEY_ALIAS")
                keyPassword = env("AQSYNC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // AGP 9.3+ DSL: R8 code optimization and resource shrinking together, with the default
            // Android rules (proguard-android-optimize). Project rules live in src/main/keepRules/*.keep.
            // https://developer.android.com/topic/performance/app-optimization/enable-app-optimization
            optimization {
                enable = true
            }
            signingConfig = if (localRelease) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.findByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // Lets JVM tests exercise classes that call android.util.Log (TcpTransport loopback test).
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Offline-first: fail the build on lint errors so an insecure API or a
        // permission nobody reviewed cannot slip in unnoticed. INTERNET is declared
        // deliberately for the mDNS-discovered LAN transport only (see the manifest).
        // Existing warnings are recorded in lint-baseline.xml; any new warning fails the build.
        // Regenerate only after review: ./gradlew :app:updateLintBaseline
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        baseline = file("lint-baseline.xml")
        // Version notices change whenever an upstream library ships, not when this code changes.
        // Dependabot (.github/dependabot.yml) owns updates, so these checks would only make the
        // gate fail on a date.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

composeCompiler {
    // Strong skipping and intrinsic remember are on by default in this Kotlin
    // (the explicit flag is deprecated and slated for removal in 2.5.0).
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android.sourceSets.getByName("test") {
    // The Parquet fixtures (real device files + PyArrow expectations) live once, in :core:parquet.
    resources.srcDir(rootProject.file("core/parquet/src/test/resources"))
}

dependencies {
    // Pure modules: contract mirror, file format, analysis. Nothing Android below :app.
    implementation(project(":core:protocol"))
    implementation(project(":core:parquet"))
    implementation(project(":core:history"))
    // ZSTD pages of external GeoParquet (PyArrow/GDAL default in many pipelines): the one native library,
    // registered into :core:parquet's codec SPI by data/history/parquet/AppParquetCodecs. Prebuilt AAR;
    // the pure module never links it (docs/history-ux-and-data-layer.md §7, G1).
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    testImplementation(libs.zstd.jni)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt and instrumented tests.
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    // Hilt and Robolectric tests.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.android.compiler)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: jUnit rules and runners

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Serialization (info/status/live JSON) and coroutines on Android
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Icons and SAF helpers
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.documentfile)
    // System-hosted barcode UI; no app camera permission. Manual entry remains available offline.
    implementation(libs.google.code.scanner)

    // Preferences (Advanced mode switch)
    implementation(libs.androidx.datastore.preferences)

    // Auto-sync: WorkManager periodic/expedited worker with a Hilt-injected factory
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
