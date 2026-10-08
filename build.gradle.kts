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

// Declare Kotlin plugins once so Android, JVM and Native modules share the same plugin classloader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// Root build.gradle.kts

// The one gate: lint, build, and every unit test in every module (AGENTS.md).
tasks.register("verify") {
    group = "verification"
    description = "lintDebug + assembleDebug + unit tests of :app and every :core module"
    dependsOn(":ios:shared:jvmTest", ":app:lintDebug", ":app:assembleDebug", ":app:testDebugUnitTest", ":core:protocol:test", ":core:parquet:test", ":core:history:test")
}

// Native validation is explicit: the Android/Linux verification gate never requires Xcode.
tasks.register("verifyIos") {
    group = "verification"
    description = "Shared core iOS simulator tests and the Compose iOS framework"
    dependsOn(":core:protocol:iosSimulatorArm64Test", ":core:history:iosSimulatorArm64Test", ":ios:shared:iosSimulatorArm64Test", ":ios:shared:linkDebugFrameworkIosSimulatorArm64")
}
