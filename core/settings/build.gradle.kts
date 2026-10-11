// Shared app settings (docs/platform-parity-plan.md, `:core:settings`). One Preferences DataStore
// on Android and iOS with Android's existing file name and keys, so Android users keep their values.
// The detail level, the Smart Sync clock consent, the Discover network switch, the units and the
// cache quotas. Pure
// Kotlin, no Compose, no Hilt. Android passes the file path from its Hilt module, iOS gets it from
// iosMain (Application Support).
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Multiplatform since DataStore 1.1. The pinned version publishes jvm, iosArm64 and
            // iosSimulatorArm64 variants.
            api(libs.androidx.datastore.preferences.core)
            api(project(":core:units"))
            // RetentionPolicy, the cache quotas the janitor and the map cache read at start (#61).
            api(project(":core:storage"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
