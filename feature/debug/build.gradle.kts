// The Debug screen (docs/screens.md, Debug), shared Compose in `commonMain` for the Android app and
// the iOS framework. It is reachable only at the Developer detail level. A plain DebugController
// over a DebugSource port exposes a StateFlow. Android adapts its connection repository, iOS lends
// short links from its Swift transports. The raw command denylist and the frame redaction live in
// `:core:sync`, so both platforms refuse REBOOT and SET_CONFIG and hide the same secrets.
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
            api(project(":ui:designsystem"))
            implementation(libs.kotlinx.coroutines.core)
            // Pretty printing of the status and config JSON.
            implementation(libs.kotlinx.serialization.json)
            // Local time of day for frame and trace lines.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
