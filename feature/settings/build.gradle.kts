// The Settings screen (docs/screens.md, Settings), shared Compose in `commonMain` for the Android app
// and the iOS framework. A plain SettingsController over `:core:settings` exposes a StateFlow, like
// DiscoverController. Hilt wraps it on Android, AppRoot builds it on iOS. The copy is a Compose
// resource, one catalogue for both apps.
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
            api(project(":core:units"))
            api(project(":ui:designsystem"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
