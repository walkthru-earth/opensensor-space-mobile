// The Discover map (docs/devices-and-discovery.md Part 2). Shared Compose in `commonMain` for the
// Android app and the iOS framework. It targets Android directly (not the JVM like :core:*),
// because maplibre-compose ships a separate Android artifact and its desktop JVM variant needs
// Java 25. The bundled sample feed is a Compose resource (tools/discover-fixture).
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
            api(project(":core:publicfeed"))
            // DiscoverScreen takes the resolved UnitSystem from its host.
            api(project(":core:units"))
            api(project(":ui:designsystem"))
            // Near me follows the shared permission policy and its settings link.
            api(project(":ui:permissions"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.maplibre.compose)
        }
        androidMain.dependencies {
            // One render backend for Android. OpenGL first, Vulkan after a phone check.
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
            // The "Near me" coarse location prompt (NearMe.android.kt).
            implementation(libs.androidx.activity.compose)
        }
    }
}
