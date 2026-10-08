// Declare Kotlin plugins once so Android, JVM and Native modules (and the build-logic conventions,
// which only compile against the Kotlin Gradle plugin) share the same plugin classloader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

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
