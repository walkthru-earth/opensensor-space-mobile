// The History screen (docs/history-pipeline.md, docs/screens.md), shared Compose in `commonMain` for
// the Android app and the iOS framework. A plain HistoryController over the `:core:history` ports
// (HistorySeriesReader, HistoryTiers, ArchiveIndex) exposes a StateFlow. Hilt wraps it on Android,
// AppRoot builds it on iOS. The charts draw with a TextMeasurer, never a platform canvas, and the
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
