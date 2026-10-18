// The My devices screen (docs/screens.md, My devices), shared Compose in `commonMain` for the Android
// app and the iOS framework. One list of nearby, known and Wi-Fi sensors with Forget and Collect all.
// A plain DevicesController reads narrow ports (known devices, the radios, the archive glance and
// Collect all). Android backs them with Room, BleScanner and WorkManager, iOS with the Keychain
// credential, CoreBluetooth, NWBrowser and the receipt index. No transport work in composables.
// opensensor.kmp.compose-feature sets the Android and iOS targets, Compose and the resources package.
plugins {
    id("opensensor.kmp.compose-feature")
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:settings"))
            api(project(":core:history"))
            api(project(":ui:designsystem"))
            api(project(":ui:permissions"))
            implementation(project(":ui:strings"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // The Scan clearance test renders the screen headless on the iOS simulator (verifyIos).
        iosTest.dependencies {
            implementation(libs.compose.ui.test)
        }
    }
}
