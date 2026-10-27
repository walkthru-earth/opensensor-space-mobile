// The iOS app's shared Kotlin: the Compose screens and the Swift-facing API, built as the static
// OpenSensorShared framework (opensensor.kmp.ios-framework). iOS only, with no JVM target, so
// `commonMain` consumes the Android and iOS Compose feature modules directly. `commonTest` runs on the
// iOS simulator in `verifyIos`.
plugins {
    id("opensensor.kmp.ios-framework")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:protocol"))
            implementation(project(":core:history"))
            implementation(project(":core:units"))
            implementation(project(":core:settings"))
            implementation(project(":core:sync"))
            implementation(project(":core:archive"))
            // The Thing kinds the app lists at launch (ThingKindsIos.kt).
            implementation(project(":core:thing"))
            // The Room index and removed-file marks, shared with Android (IosArchiveDatabase).
            implementation(project(":core:database"))
            implementation(project(":feature:discover"))
            implementation(project(":feature:history"))
            // Files, the inspector and export over the phone archive (docs/screens.md, Files).
            implementation(project(":feature:files"))
            implementation(project(":feature:settings"))
            // Today of the connected sensor, the same screen and confirmations as Android.
            implementation(project(":feature:today"))
            // My devices, the shared list of nearby, known and Wi-Fi sensors.
            implementation(project(":feature:devices"))
            implementation(project(":feature:debug"))
            // Device settings of a Bluetooth sensor, the same cards and confirmations as Android.
            implementation(project(":feature:device"))
            // The Add sensor wizard, the same steps as Android.
            implementation(project(":feature:setup"))
            // The shared shell: bottom bar, per-tab stacks, Back and the iOS edge swipe.
            implementation(project(":feature:shell"))
            // viewModel { } holders for controllers kept per navigation entry.
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(project(":ui:designsystem"))
            // The companion copy, resolved from UiText at the composable edge.
            implementation(project(":ui:strings"))
            implementation(libs.compose.components.resources)
            implementation(project(":ui:permissions"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
        commonTest.dependencies {
            // The background run bridge suspends on the Swift ports (AutoSyncTest).
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
