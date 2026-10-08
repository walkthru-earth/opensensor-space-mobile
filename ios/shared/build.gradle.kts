// The first iOS screen consumes the same protocol and sensor-domain code as Android.
// opensensor.kmp.ios-framework adds the static OpenSensorShared framework for every iOS target.
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
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
        // The Discover tab. iOS only: :feature:discover targets Android and iOS, not the JVM.
        iosMain.dependencies {
            implementation(project(":feature:discover"))
            implementation(project(":ui:designsystem"))
        }
    }
}
