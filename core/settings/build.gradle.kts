// Shared app settings (docs/platform-parity-plan.md, target shape). Planned: DataStore preferences
// on Android and iOS with Android's existing keys, detail levels, clock sync mode and the permission
// policy. Empty until wave 2 moves code here.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Multiplatform since DataStore 1.1. The pinned version publishes jvm, iosArm64 and
            // iosSimulatorArm64 variants.
            api(libs.androidx.datastore.preferences.core)
        }
    }
}
