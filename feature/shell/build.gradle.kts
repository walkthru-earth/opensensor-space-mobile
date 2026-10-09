// The app shell (docs/adr/0001-shared-navigation.md). One bottom bar and one Navigation 3 host with a
// back stack per tab, shared Compose in `commonMain` for the Android app and the iOS framework.
// Screens stay per platform behind an entry slot until each moves to common. The navigation keys are
// one sealed `AppKey` hierarchy, saved through `AppNavConfig` with no reflection on either platform.
// opensensor.kmp.compose-feature sets the Android and iOS targets, Compose and the resources package.
plugins {
    id("opensensor.kmp.compose-feature")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.androidx.navigation3.runtime)
            api(libs.jetbrains.navigation3.ui)
            api(libs.jetbrains.lifecycle.viewmodel.navigation3)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            api(project(":ui:designsystem"))
            // The tab labels, from the shared catalogue.
            implementation(project(":ui:strings"))
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            // AppNavConfigTest round trips the keys through the saved state module.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
