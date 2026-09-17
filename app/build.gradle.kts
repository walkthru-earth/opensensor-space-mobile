/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// CI (.github/workflows/release.yml) stamps the build through the environment; a local build keeps
// the defaults. Read through providers so the configuration cache stays valid.
val ciVersionCode = providers.environmentVariable("AQSYNC_VERSION_CODE").orNull?.toIntOrNull()
val ciVersionName = providers.environmentVariable("AQSYNC_VERSION_NAME").orNull
val releaseKeystore = providers.environmentVariable("AQSYNC_KEYSTORE_FILE").orNull?.let { rootProject.file(it) }

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
        // Release key only when CI (or a developer) provides one; the file never enters the repo
        // (*.jks is ignored). Without it the release build type is signed with the debug key below,
        // so a CI artifact is always installable — as a *test* build, not a store release.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("AQSYNC_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("AQSYNC_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("AQSYNC_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
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

    // Preferences (Advanced mode switch)
    implementation(libs.androidx.datastore.preferences)

    // Auto-sync: WorkManager periodic/expedited worker with a Hilt-injected factory
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
