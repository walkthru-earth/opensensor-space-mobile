// Declare Kotlin plugins once so Android, JVM and Native modules (and the build-logic conventions,
// which only compile against the Kotlin Gradle plugin) share the same plugin classloader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// The gates (AGENTS.md). Only the :app tasks are listed here. Every shared module joins the gates
// through its convention plugin (build-logic/src/main/kotlin/Gates.kt), so no module can be missed.
val verifyIos = tasks.register("verifyIos") {
    group = "verification"
    description = "iOS simulator tests of every shared module and the OpenSensorShared framework link"
}

// On a Mac, `verify` also runs `verifyIos`. Linux CI and `-Popensensor.skipIos=true` skip it.
val runIos = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac &&
    providers.gradleProperty("opensensor.skipIos").orNull != "true"

tasks.register("verify") {
    group = "verification"
    description = "lintDebug, assembleDebug, :app unit tests, every shared module's host tests, and verifyIos on a Mac"
    dependsOn(":app:lintDebug", ":app:assembleDebug", ":app:testDebugUnitTest")
    if (runIos) dependsOn(verifyIos)
}
