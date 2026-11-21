// The setup wizard (docs/screens.md, Add sensor), shared Compose in `commonMain` for the Android app
// and the iOS framework. A plain SetupController over a SetupPort walks the sensor's SetupPlan, the
// steps its board template declares. Every step reuses the Device settings forms and
// DeviceConfigController, so first setup and later settings send the same SET_CONFIG keys.
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
            api(project(":feature:device"))
            api(project(":ui:permissions"))
            implementation(libs.kotlinx.coroutines.core)
            // System back, the Android gesture or the iOS edge swipe, asks before leaving (NavigationBackHandler).
            implementation(libs.jetbrains.navigationevent.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // The back and saved-location tests render the screen headless on the iOS simulator (verifyIos).
        iosTest.dependencies {
            implementation(libs.compose.ui.test)
        }
    }
}
