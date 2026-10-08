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
            implementation(project(":feature:discover"))
            implementation(project(":ui:designsystem"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
    }
}
