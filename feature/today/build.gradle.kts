// The Today screen of one device (docs/screens.md), shared Compose in `commonMain` for the Android
// app and the iOS framework. A plain TodayController over the TodaySource port exposes a StateFlow and
// the confirmation dialogs. Android adapts DeviceFacade, iOS adapts the companion state.
// The live reading is the shared protocol model, its meaning comes from the metric registry, and the
// copy is a Compose resource, one catalogue for both apps.
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
            api(project(":core:units"))
            api(project(":ui:designsystem"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
