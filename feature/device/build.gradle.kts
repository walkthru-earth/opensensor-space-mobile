// The Device section of one sensor (docs/screens.md, Device): pairing, Wi-Fi, Sync over Wi-Fi, paired
// phones and the fitted sensor sticker. Shared Compose in `commonMain` for the Android app and the iOS
// framework. A plain DeviceConfigController over the DeviceConfigSource port owns the confirmations,
// the validation and the Wi-Fi join poll. Android adapts DeviceConnectionRepository, iOS borrows the
// Bluetooth link from BluetoothCompanion. Every write is one `SET_CONFIG` built by `ConfigPatch`.
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
            api(project(":core:sync"))
            api(project(":core:history"))
            api(project(":core:settings"))
            api(project(":ui:designsystem"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
